package com.mlops.platform.service;

import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Service de récupération des fichiers depuis un dépôt GitHub via l'API REST.
 *
 * Utilise le token d'application GitHub (fine-grained, Contents: Read)
 * stocké dans la variable d'environnement {@code GITHUB_TOKEN}.
 *
 * Le token n'est JAMAIS stocké en base ni loggé.
 */
@Service
public class GitHubFileFetcherService {

    private static final String GITHUB_API = "https://api.github.com";
    private static final String TOKEN_ENV = "GITHUB_TOKEN";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final long MAX_ARCHIVE_SIZE = 50 * 1024 * 1024;

    /**
     * Télécharge l'archive ZIP d'un commit GitHub et l'extrait dans un
     * répertoire temporaire.
     *
     * @param repository format "owner/repo" (ex: "SalmaGhrib/mlops-platform-pfe")
     * @param commitSha  SHA du commit exact à récupérer
     * @return le chemin vers le répertoire contenant les fichiers extraits
     * @throws IllegalStateException si le token est manquant ou l'appel échoue
     */
    public Path fetchCommitArchive(String repository, String commitSha) {
        String token = System.getenv(TOKEN_ENV);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "GITHUB_TOKEN environment variable is not set. "
                            + "Configure a fine-grained GitHub token with Contents: Read permission."
            );
        }

        String url = GITHUB_API + "/repos/" + repository + "/zipball/" + commitSha;

        System.out.println(
                "[GITHUB_FILE_FETCHER] Downloading commit archive. "
                        + "repository=" + repository
                        + ", commitSha=" + shortSha(commitSha)
        );

        try {
            Path tempDir = Files.createTempDirectory(
                    "github-commit-" + shortSha(commitSha) + "-"
            );

            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);

            int status = conn.getResponseCode();
            if (status != 200) {
                byte[] errorBody = conn.getErrorStream() != null
                        ? conn.getErrorStream().readAllBytes()
                        : new byte[0];
                conn.disconnect();
                throw new IllegalStateException(
                        "GitHub API returned HTTP " + status
                                + " for " + repository + "@" + shortSha(commitSha)
                                + ". Ensure the token has Contents: Read and the commit exists."
                );
            }

            long contentLength = conn.getContentLengthLong();
            if (contentLength > MAX_ARCHIVE_SIZE) {
                conn.disconnect();
                throw new IllegalStateException(
                        "GitHub archive exceeds 50 MB limit. size=" + contentLength
                );
            }

            try (InputStream is = conn.getInputStream();
                 ZipInputStream zis = new ZipInputStream(is)) {

                byte[] buffer = new byte[8192];
                ZipEntry entry;
                int fileCount = 0;

                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    String entryName = entry.getName();
                    int slashIdx = entryName.indexOf('/');
                    if (slashIdx >= 0) {
                        entryName = entryName.substring(slashIdx + 1);
                    }

                    if (entryName.isBlank()) {
                        continue;
                    }

                    Path target = tempDir.resolve(entryName).normalize();
                    if (!target.startsWith(tempDir)) {
                        continue;
                    }

                    Files.createDirectories(target.getParent());

                    try (OutputStream os = Files.newOutputStream(target)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }

                    fileCount++;
                }

                System.out.println(
                        "[GITHUB_FILE_FETCHER] Archive extracted. "
                                + "repository=" + repository
                                + ", commitSha=" + shortSha(commitSha)
                                + ", files=" + fileCount
                                + ", path=" + tempDir
                );

                return tempDir;

            } finally {
                conn.disconnect();
            }

        } catch (IllegalStateException rethrown) {
            throw rethrown;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to fetch GitHub commit archive. "
                            + "repository=" + repository
                            + ", commitSha=" + shortSha(commitSha)
                            + ", reason=" + ex.getMessage(),
                    ex
            );
        }
    }

    /**
     * Vérifie si un fichier de training script existe dans le répertoire
     * extrait (train.py, train.ipynb, etc.).
     */
    public Path resolveTrainingScript(Path commitDir) {
        String[] candidates = {
                "train.py", "train.ipynb", "training.py", "main.py", "run.py"
        };
        for (String name : candidates) {
            Path p = commitDir.resolve(name);
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Vérifie si un Dockerfile existe dans le répertoire extrait.
     */
    public Path resolveDockerfile(Path commitDir) {
        Path p = commitDir.resolve("Dockerfile");
        return Files.exists(p) ? p : null;
    }

    /**
     * Vérifie si un requirements.txt existe dans le répertoire extrait.
     */
    public Path resolveRequirements(Path commitDir) {
        String[] candidates = {"requirements.txt", "requirements-dev.txt", "deps.txt"};
        for (String name : candidates) {
            Path p = commitDir.resolve(name);
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Recherche un dataset dans le répertoire extrait.
     */
    public Path resolveDataset(Path commitDir) {
        String[] candidates = {
                "dataset.csv", "data.csv", "train.csv", "input.csv",
                "dataset.zip", "data.zip"
        };
        for (String name : candidates) {
            Path p = commitDir.resolve(name);
            if (Files.exists(p)) {
                return p;
            }
        }

        try (var stream = Files.list(commitDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(f -> {
                        String fn = f.getFileName().toString().toLowerCase();
                        return fn.endsWith(".csv") || fn.endsWith(".tsv")
                                || fn.endsWith(".zip") || fn.endsWith(".parquet");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String shortSha(String commitSha) {
        return commitSha == null ? "null"
                : commitSha.length() <= 8 ? commitSha
                : commitSha.substring(0, 8);
    }
}
