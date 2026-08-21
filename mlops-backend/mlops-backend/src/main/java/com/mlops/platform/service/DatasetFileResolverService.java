package com.mlops.platform.service;

import com.mlops.platform.entity.MlExperiment;
import com.mlops.platform.entity.MlProject;
import com.mlops.platform.entity.UploadedFileType;
import com.mlops.platform.entity.UploadedProjectFile;
import com.mlops.platform.repository.UploadedProjectFileRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Résolution autoritative du dataset d'une expérience.
 *
 * Source de vérité = rôle du fichier (UploadedFileType), jamais l'extension seule.
 * Garantit que le chemin transmis comme DATASET_PATH / DATA_PATH pointe
 * TOUJOURS vers un vrai dataset (.csv, .zip, archive...) et JAMAIS vers un
 * script Python (train.py, preprocessing.py...) ou un fichier support
 * (Dockerfile, requirements.txt).
 *
 * Contrat :
 *  - TRAINING_SCRIPT, DOCKERFILE, REQUIREMENTS, AUXILIARY, MODEL_FILE, OTHER
 *    ne sont JAMAIS des datasets ;
 *  - un script .py n'est un dataset QUE s'il est déclaré
 *    DATASET_PREPARATION_SCRIPT (générateur de dataset exécuté par le runner) ;
 *  - si le fichier attaché à l'expérience est invalide ou obsolète (lignes
 *    héritées), on retombe sur les fichiers du projet pour trouver le vrai dataset.
 */
@Service
public class DatasetFileResolverService {

    private static final Set<String> SCRIPT_EXTENSIONS = Set.of("py", "py3");

    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
            "zip", "tar", "gz", "tgz", "7z", "rar"
    );

    private static final Set<String> DATASET_EXTENSIONS = Set.of(
            "csv", "tsv", "txt", "json", "jsonl", "parquet", "xlsx", "xls",
            "zip", "tar", "tgz", "gz", "7z", "rar",
            "jpg", "jpeg", "png", "tif", "tiff"
    );

    private final UploadedProjectFileRepository uploadedProjectFileRepository;

    public DatasetFileResolverService(
            UploadedProjectFileRepository uploadedProjectFileRepository
    ) {
        this.uploadedProjectFileRepository = uploadedProjectFileRepository;
    }

    /**
     * Dérive le rôle dataset d'un nom de fichier à partir de son extension.
     *
     * Règles sûres (contrat UploadedFileType) :
     *  - .py/.py3 -> DATASET_PREPARATION_SCRIPT (jamais DATASET_FILE) ;
     *  - zip/tar/gz/tgz/7z/rar -> DATASET_ARCHIVE ;
     *  - sinon -> DATASET_FILE.
     *
     * L'extension est normalisée SANS point ("py", "csv", "zip").
     */
    public UploadedFileType deriveRoleFromFileName(String fileName) {
        String ext = extensionOf(fileName);
        if (ext == null) {
            return UploadedFileType.DATASET_FILE;
        }
        if (SCRIPT_EXTENSIONS.contains(ext)) {
            return UploadedFileType.DATASET_PREPARATION_SCRIPT;
        }
        if (ARCHIVE_EXTENSIONS.contains(ext)) {
            return UploadedFileType.DATASET_ARCHIVE;
        }
        return UploadedFileType.DATASET_FILE;
    }

    /**
     * Un rôle est un rôle de dataset s'il produit des données exploitables :
     * fichier tabulaire, archive, script générateur, ou valeurs héritées.
     */
    public boolean isDatasetRole(UploadedFileType role) {
        if (role == null) {
            return false;
        }
        return switch (role) {
            case DATASET_FILE, DATASET_ARCHIVE, DATASET_PREPARATION_SCRIPT,
                 PREPROCESSING_SCRIPT, DATASET, IMAGE_FILE, TEXT_FILE -> true;
            default -> false;
        };
    }

    /**
     * Vrai si le fichier est un script Python (.py / .py3).
     */
    public boolean isPythonScript(UploadedProjectFile file) {
        if (file == null) {
            return false;
        }
        String ext = normalizedExtension(file.getFileExtension(), file.getOriginalFileName());
        return ext != null && SCRIPT_EXTENSIONS.contains(ext);
    }

    /**
     * Résout le vrai dataset d'une expérience.
     *
     * @return le fichier dataset valide, ou {@code null} si aucun dataset
     *         exploitable n'existe.
     */
    public UploadedProjectFile resolveDatasetFile(
            MlProject project,
            MlExperiment experiment
    ) {
        UploadedProjectFile attached = experiment != null
                ? experiment.getDatasetFile()
                : null;

        if (isAcceptableDataset(attached)) {
            return attached;
        }

        if (attached != null) {
            System.out.println("[DATASET_SELECTION] Dataset attaché à l'expérience non exploitable. "
                    + "file=" + displayName(attached)
                    + ", role=" + safeRole(attached)
                    + ". Recherche du vrai dataset dans les fichiers du projet.");
        }

        return findBestProjectDataset(project);
    }

    /**
     * Un fichier est un dataset acceptable si :
     *  - son chemin existe sur disque (PVC partagé /app/uploads) ;
     *  - son rôle est un rôle de dataset ;
     *  - ce n'est PAS un script Python : un .py/.py3 n'est JAMAIS transmis
     *    comme DATASET_PATH (la plateforme ne supporte pas de dataset Python).
     */
    private boolean isAcceptableDataset(UploadedProjectFile file) {
        if (file == null
                || !hasText(file.getFilePath())
                || !Files.exists(Path.of(file.getFilePath()))) {
            return false;
        }

        if (isPythonScript(file)) {
            return false;
        }

        return isDatasetRole(effectiveRole(file));
    }

    /**
     * Sélectionne le meilleur dataset parmi les fichiers du projet.
     * Priorité aux archives, puis aux fichiers tabulaires, puis aux rôles
     * dataset hérités. Un script Python n'est JAMAIS retenu comme dataset.
     */
    private UploadedProjectFile findBestProjectDataset(MlProject project) {
        if (project == null || project.getId() == null) {
            return null;
        }

        List<UploadedProjectFile> projectFiles;
        try {
            projectFiles = uploadedProjectFileRepository.findByProjectId(project.getId());
        } catch (Exception e) {
            System.out.println("[DATASET_SELECTION] Impossible de lister les fichiers du projet. "
                    + "projectId=" + project.getId()
                    + ", reason=" + e.getMessage());
            return null;
        }

        UploadedProjectFile best = null;
        int bestScore = Integer.MIN_VALUE;

        for (UploadedProjectFile candidate : projectFiles) {
            int score = scoreDatasetCandidate(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best != null) {
            System.out.println("[DATASET_SELECTION] Dataset résolu depuis les fichiers du projet. "
                    + "file=" + displayName(best)
                    + ", role=" + safeRole(best)
                    + ", score=" + bestScore);
        } else {
            System.out.println("[DATASET_SELECTION] Aucun dataset valide dans le projet. "
                    + "projectId=" + (project.getId() == null ? "-" : project.getId()));
        }

        return best;
    }

    private int scoreDatasetCandidate(UploadedProjectFile file) {
        if (file == null
                || !hasText(file.getFilePath())
                || !Files.exists(Path.of(file.getFilePath()))) {
            return Integer.MIN_VALUE;
        }

        UploadedFileType role = effectiveRole(file);

        // Un script Python n'est JAMAIS un dataset.
        if (isPythonScript(file)) {
            return Integer.MIN_VALUE;
        }

        if (isDatasetRole(role)) {
            return switch (role) {
                case DATASET_ARCHIVE -> 100;
                case DATASET_FILE -> 90;
                case DATASET, IMAGE_FILE, TEXT_FILE -> 80;
                case DATASET_PREPARATION_SCRIPT -> 70;
                default -> 0;
            };
        }

        // Valeurs héritées (rôle indéfini/OTHER) mais extension clairement dataset.
        if ((role == null || role == UploadedFileType.OTHER)
                && looksLikeDatasetExtension(file)) {
            return 40;
        }

        return Integer.MIN_VALUE;
    }

    /**
     * Trouve un éventuel script de préprocessing parmi les fichiers du projet
     * (rôle DATASET_PREPARATION_SCRIPT / AUXILIARY, ou .py hérité).
     * Uniquement informatif (log [TRAINING-FILES]) : ce script n'est jamais
     * transmis comme DATASET_PATH.
     */
    public UploadedProjectFile findPreprocessingScript(MlProject project) {
        if (project == null || project.getId() == null) {
            return null;
        }
        List<UploadedProjectFile> projectFiles;
        try {
            projectFiles = uploadedProjectFileRepository.findByProjectId(project.getId());
        } catch (Exception e) {
            return null;
        }
        for (UploadedProjectFile candidate : projectFiles) {
            if (candidate == null) {
                continue;
            }
            UploadedFileType role = effectiveRole(candidate);
            boolean scriptRole = role == UploadedFileType.DATASET_PREPARATION_SCRIPT
                    || role == UploadedFileType.PREPROCESSING_SCRIPT
                    || role == UploadedFileType.AUXILIARY;
            if (isPythonScript(candidate) && scriptRole) {
                return candidate;
            }
        }
        return null;
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

    private boolean looksLikeDatasetExtension(UploadedProjectFile file) {
        String ext = normalizedExtension(file.getFileExtension(), file.getOriginalFileName());
        return ext != null && DATASET_EXTENSIONS.contains(ext);
    }

    private String normalizedExtension(String fileExtension, String originalFileName) {
        String ext = fileExtension;
        if (!hasText(ext) && hasText(originalFileName)) {
            ext = extensionOf(originalFileName);
        }
        if (!hasText(ext)) {
            return null;
        }
        ext = ext.trim().toLowerCase(Locale.ROOT);
        if (ext.startsWith(".")) {
            ext = ext.substring(1);
        }
        return ext.isBlank() ? null : ext;
    }

    /**
     * Extension d'un nom de fichier, normalisée SANS point et en minuscules
     * ("py", "csv", "zip"). Retourne {@code null} si absente.
     */
    private String extensionOf(String fileName) {
        if (!hasText(fileName)) {
            return null;
        }
        int lastDotIndex = fileName.lastIndexOf(".");
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return null;
        }
        String ext = fileName.substring(lastDotIndex + 1);
        ext = ext.trim().toLowerCase(Locale.ROOT);
        return ext.isBlank() ? null : ext;
    }

    private String displayName(UploadedProjectFile file) {
        if (file == null) {
            return "-";
        }
        String name = file.getOriginalFileName();
        return hasText(name) ? name : file.getStoredFileName();
    }

    private String safeRole(UploadedProjectFile file) {
        UploadedFileType role = effectiveRole(file);
        return role == null ? "-" : role.name();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}