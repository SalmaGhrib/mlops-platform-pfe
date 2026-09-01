package com.mlops.platform.service;

import com.mlops.platform.entity.UploadedFileType;
import com.mlops.platform.entity.UploadedProjectFile;
import com.mlops.platform.repository.UploadedProjectFileRepository;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Résolution centralisée des fichiers pour le contexte de build Docker personnalisé.
 *
 * Ce service résout les références de fichiers dans un Dockerfile vers les vrais
 * fichiers uploadés sur le PVC, quelle que soit leur surcharge (UUID, timestamp,
 * suffixe navigateur). Il gère également l'extraction de CSV depuis des archives ZIP.
 *
 * Contrat :
 *  - Ne retourne JAMAIS de contenu binaire pour un .py/.py3 (jamais de script Python
 *    comme "données") ;
 *  - Un script .py n'est un dataset QUE s'il a le rôle DATASET_PREPARATION_SCRIPT ;
 *  - Priorité à la résolution par rôle explicite, puis par nom original nettoyé,
 *    puis par nom stocké.
 */
@Service
public class UploadedFileResolverService {

    private static final Set<String> SCRIPT_EXTENSIONS = Set.of("py", "py3");

    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
            "zip", "tar", "gz", "tgz", "7z", "rar"
    );

    private static final Set<String> DATASET_EXTENSIONS = Set.of(
            "csv", "tsv", "txt", "json", "jsonl", "parquet", "xlsx", "xls",
            "zip", "tar", "tgz", "gz", "7z", "rar",
            "jpg", "jpeg", "png", "tif", "tiff"
    );

    private static final Pattern COPY_ADD_PATTERN = Pattern.compile(
            "(?im)^[\\s]*(COPY|ADD)\\s+(.+?)\\s+(.+)$",
            Pattern.MULTILINE
    );

    private final UploadedProjectFileRepository uploadedProjectFileRepository;

    public UploadedFileResolverService(
            UploadedProjectFileRepository uploadedProjectFileRepository
    ) {
        this.uploadedProjectFileRepository = uploadedProjectFileRepository;
    }

    /**
     * Résolution d'un fichier référencé dans un Dockerfile vers son contenu réel.
     *
     * @param project   le projet contenant les fichiers uploadés
     * @param reference la référence telle qu'elle apparaît dans le Dockerfile (ex: "train.py", "data.csv")
     * @return un {@link ResolvedBuildFile} si résolu, {@code null} sinon
     */
    public ResolvedBuildFile resolve(Long projectId, String reference) {
        if (projectId == null || reference == null || reference.isBlank()) {
            return null;
        }

        String cleanedRef = stripQuotes(reference.trim());
        if (cleanedRef.isBlank()) {
            return null;
        }

        if (cleanedRef.startsWith("http://") || cleanedRef.startsWith("https://")) {
            return null;
        }

        if (cleanedRef.contains("*") || cleanedRef.contains("$") || cleanedRef.contains("${")) {
            return null;
        }

        String baseName = cleanedRef.contains("/")
                ? cleanedRef.substring(cleanedRef.lastIndexOf('/') + 1)
                : cleanedRef;

        if (baseName.isBlank() || baseName.equals(".") || baseName.equals("./")) {
            return null;
        }

        List<UploadedProjectFile> projectFiles;
        try {
            projectFiles = uploadedProjectFileRepository.findByProjectId(projectId);
        } catch (Exception e) {
            System.out.println("[CUSTOM-BUILD] Impossible de lister les fichiers du projet. "
                    + "projectId=" + projectId + ", reason=" + e.getMessage());
            return null;
        }

        if (projectFiles == null || projectFiles.isEmpty()) {
            return null;
        }

        String baseNameLower = baseName.toLowerCase(Locale.ROOT);

        UploadedProjectFile bestMatch = null;
        int bestScore = -1;

        for (UploadedProjectFile candidate : projectFiles) {
            int score = scoreFileMatch(candidate, baseNameLower, cleanedRef);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = candidate;
            }
        }

        if (bestMatch != null && bestScore > 0) {
            if (bestMatch.getFilePath() != null && !bestMatch.getFilePath().isBlank()) {
                Path filePath = Path.of(bestMatch.getFilePath());
                if (Files.exists(filePath)) {
                    try {
                        String content = Files.readString(filePath, StandardCharsets.UTF_8);
                        System.out.println("[CUSTOM-BUILD] Fichier résolu pour le build. "
                                + "reference=" + reference
                                + ", resolvedTo=" + bestMatch.getOriginalFileName()
                                + ", storedAs=" + filePath.getFileName()
                                + ", role=" + safeRole(bestMatch)
                                + ", sizeBytes=" + content.length());
                        return new ResolvedBuildFile(
                                baseName,
                                content,
                                bestMatch.getOriginalFileName(),
                                safeRole(bestMatch)
                        );
                    } catch (Exception e) {
                        System.out.println("[CUSTOM-BUILD] Erreur lecture fichier résolu. "
                                + "reference=" + reference + ", path=" + filePath + ", reason=" + e.getMessage());
                    }
                }
            }
        }

        ResolvedBuildFile archiveResult = resolveArchiveEntry(
                projectId, projectFiles, baseName, cleanedRef);
        if (archiveResult != null) {
            return archiveResult;
        }

        return null;
    }

    /**
     * Résout toutes les références COPY/ADD d'un Dockerfile vers les fichiers réels du projet.
     * Retourne une map nomLogique -> contenu, prête à être ajoutée au ConfigMap de build.
     */
    public Map<String, String> resolveDockerfileReferences(Long projectId, String dockerfileContent) {
        Map<String, String> resolved = new LinkedHashMap<>();

        if (projectId == null || dockerfileContent == null || dockerfileContent.isBlank()) {
            return resolved;
        }

        Matcher matcher = COPY_ADD_PATTERN.matcher(dockerfileContent);

        while (matcher.find()) {
            String srcPart = matcher.group(2);

            for (String token : srcPart.split("\\s+")) {
                String t = stripQuotes(token.trim());

                if (t.isBlank() || t.startsWith("--")) {
                    continue;
                }

                ResolvedBuildFile resolvedFile = resolve(projectId, t);
                if (resolvedFile != null && !resolved.containsKey(resolvedFile.logicalName())) {
                    resolved.put(resolvedFile.logicalName(), resolvedFile.content());
                }
            }
        }

        System.out.println("[CUSTOM-BUILD] Résolution des références Dockerfile terminée. "
                + "projectId=" + projectId
                + ", referencesTrouvées=" + resolved.size()
                + ", noms=" + resolved.keySet());
        return resolved;
    }

    /**
     * Résout une entrée spécifique depuis une archive ZIP.
     * Si le nom de référence est un CSV et qu'un ZIP contient un CSV, retourne le CSV extrait.
     */
    private ResolvedBuildFile resolveArchiveEntry(
            Long projectId,
            List<UploadedProjectFile> projectFiles,
            String referenceName,
            String originalRef
    ) {
        for (UploadedProjectFile candidate : projectFiles) {
            if (candidate.getFilePath() == null || candidate.getFilePath().isBlank()) {
                continue;
            }
            Path candidatePath = Path.of(candidate.getFilePath());
            if (!Files.exists(candidatePath) || !isArchiveFile(candidatePath.getFileName().toString())) {
                continue;
            }

            try (InputStream is = Files.newInputStream(candidatePath);
                 ZipInputStream zis = new ZipInputStream(is)) {

                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    String entryName = entry.getName();
                    String entryBase = entryName.contains("/")
                            ? entryName.substring(entryName.lastIndexOf('/') + 1)
                            : entryName;

                    if (entryBase.equalsIgnoreCase(referenceName)) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            baos.write(buffer, 0, len);
                        }
                        String content = baos.toString(StandardCharsets.UTF_8);

                        System.out.println("[CUSTOM-BUILD] Fichier extrait depuis archive ZIP. "
                                + "reference=" + originalRef
                                + ", archive=" + candidatePath.getFileName()
                                + ", entry=" + entryName
                                + ", sizeBytes=" + content.length());

                        return new ResolvedBuildFile(
                                referenceName,
                                content,
                                entryName,
                                "DATASET_ARCHIVE"
                        );
                    }
                }
            } catch (Exception e) {
                System.out.println("[CUSTOM-BUILD] Erreur lecture archive ZIP. "
                        + "archive=" + candidatePath.getFileName() + ", reason=" + e.getMessage());
            }
        }

        return null;
    }

    private int scoreFileMatch(UploadedProjectFile candidate, String baseNameLower, String fullRef) {
        if (candidate.getFilePath() == null || candidate.getFilePath().isBlank()) {
            return 0;
        }

        String originalLower = candidate.getOriginalFileName() == null
                ? ""
                : candidate.getOriginalFileName().toLowerCase(Locale.ROOT);
        String originalStripped = UploadFileNameNormalizer.stripBrowserSuffix(
                candidate.getOriginalFileName()).toLowerCase(Locale.ROOT);

        int score = 0;

        if (originalLower.equals(baseNameLower) || originalStripped.equals(baseNameLower)) {
            score = 100;
        } else if (originalLower.contains(baseNameLower) || baseNameLower.contains(originalLower)) {
            score = 60;
        } else {
            return 0;
        }

        UploadedFileType role = effectiveRole(candidate);
        if (role != null) {
            score += switch (role) {
                case AUXILIARY -> 10;
                case TRAINING_SCRIPT -> 5;
                case DATASET_FILE, DATASET_ARCHIVE -> 5;
                default -> 0;
            };
        }

        return score;
    }

    private boolean isArchiveFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        String ext = extensionOf(fileName);
        return ext != null && ARCHIVE_EXTENSIONS.contains(ext);
    }

    private boolean isDatasetRole(UploadedProjectFile file) {
        if (file == null) {
            return false;
        }
        UploadedFileType role = effectiveRole(file);
        return role != null && switch (role) {
            case DATASET_FILE, DATASET_ARCHIVE, DATASET, IMAGE_FILE, TEXT_FILE -> true;
            default -> false;
        };
    }

    private boolean isPrepScriptRole(UploadedProjectFile file) {
        if (file == null) {
            return false;
        }
        UploadedFileType role = effectiveRole(file);
        return role == UploadedFileType.DATASET_PREPARATION_SCRIPT
                || role == UploadedFileType.PREPROCESSING_SCRIPT;
    }

    private UploadedFileType effectiveRole(UploadedProjectFile file) {
        if (file == null) {
            return null;
        }
        UploadedFileType role = file.getRole();
        if (role != null && role != UploadedFileType.OTHER) {
            return role;
        }
        return file.getFileType();
    }

    private String safeRole(UploadedProjectFile file) {
        UploadedFileType role = effectiveRole(file);
        return role == null ? "OTHER" : role.name();
    }

    private String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("^[\\\"']|[\\\"']$", "");
    }

    private String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot == -1 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Représente un fichier résolu pour le contexte de build.
     */
    public record ResolvedBuildFile(
            String logicalName,
            String content,
            String originalName,
            String role
    ) {
    }
}
