package com.mlops.platform.service;

import com.mlops.platform.entity.MlExperiment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service responsable de détecter automatiquement le modèle sauvegardé
 * par un script utilisateur.
 *
 * Important :
 * Ce service ne modifie pas le modèle.
 * Il détecte seulement :
 * - le chemin du modèle dans les logs
 * - son format probable
 * - s'il ressemble déjà à un modèle MLflow valide
 *
 * Exemple détecté :
 * Model saved to outputs/model.pkl
 * AUTO_SAVE_MODEL_PATH=outputs/model.pkl
 * model_path=/workspace/outputs/model.joblib
 * saved_model/model.cbm
 */
@Service
public class ModelArtifactResolver {

    private static final List<String> ACCEPTED_EXTENSIONS = List.of(
            ".pkl",
            ".joblib",
            ".sav",
            ".cbm",
            ".pt",
            ".pth",
            ".h5",
            ".keras",
            ".onnx"
    );

    private static final List<String> ACCEPTED_DIRS = List.of(
            "outputs/",
            "output/",
            "artifacts/",
            "artifact/",
            "model/",
            "models/",
            "saved_model/",
            "checkpoints/",
            "/workspace/outputs/",
            "/workspace/output/",
            "/workspace/artifacts/",
            "/workspace/artifact/",
            "/workspace/model/",
            "/workspace/models/",
            "/workspace/saved_model/",
            "/workspace/checkpoints/",
            "/workspace/"
    );

    private static final List<String> PREFERRED_NAMES = List.of(
            "mlmodel",
            "best_model.pkl",
            "final_model.pkl",
            "model.pkl",
            "best_model.joblib",
            "final_model.joblib",
            "model.joblib",
            "best_model.cbm",
            "final_model.cbm",
            "model.cbm",
            "model.sav",
            "model.onnx",
            "model.h5",
            "model.keras",
            "model.pt",
            "model.pth"
    );

    public DetectedModelArtifact resolveFromExperiment(MlExperiment experiment) {
        if (experiment == null) {
            return DetectedModelArtifact.notFound("Experiment is null.");
        }

        String logs = firstNonBlank(
                experiment.getTrainingLogs(),
                experiment.getBuildLogs(),
                experiment.getMessage()
        );

        return resolveFromLogs(logs);
    }

    public DetectedModelArtifact resolveFromLogs(String logs) {
        if (!hasText(logs)) {
            return DetectedModelArtifact.notFound("No logs available to detect model artifact.");
        }

        List<DetectedModelArtifact> candidates = new ArrayList<>();

        candidates.addAll(findMlflowModelCandidates(logs));
        candidates.addAll(findModelFileCandidates(logs));

        if (candidates.isEmpty()) {
            return DetectedModelArtifact.notFound(
                    "No model artifact found in logs. Accepted extensions: " + ACCEPTED_EXTENSIONS
            );
        }

        candidates.sort(
                Comparator.comparingInt(this::scoreCandidate).reversed()
        );

        DetectedModelArtifact best = candidates.get(0);
        best.setFound(true);
        best.setReason("Model artifact detected automatically from training logs.");

        return best;
    }

    private List<DetectedModelArtifact> findMlflowModelCandidates(String logs) {
        List<DetectedModelArtifact> result = new ArrayList<>();

        /*
         * Détecte les cas où un vrai dossier MLflow existe déjà.
         * Exemple :
         * /workspace/outputs/model/MLmodel
         * outputs/model/MLmodel
         */
        Pattern pattern = Pattern.compile(
                "(?i)([A-Za-z0-9_./\\\\\\-]+/MLmodel)"
        );

        Matcher matcher = pattern.matcher(logs);

        while (matcher.find()) {
            String path = cleanPath(matcher.group(1));

            DetectedModelArtifact artifact = new DetectedModelArtifact();
            artifact.setFound(true);
            artifact.setArtifactPath(path);
            artifact.setFileName(extractFileName(path));
            artifact.setExtension("MLmodel");
            artifact.setFormat("MLFLOW_MODEL");
            artifact.setFramework("MLFLOW");
            artifact.setMlflowModel(true);
            artifact.setReason("Existing MLflow MLmodel file detected.");

            result.add(artifact);
        }

        return result;
    }

    private List<DetectedModelArtifact> findModelFileCandidates(String logs) {
        List<DetectedModelArtifact> result = new ArrayList<>();

        /*
         * Détecte :
         * outputs/model.pkl
         * /workspace/outputs/model.pkl
         * Model saved to outputs/model.pkl
         * AUTO_SAVE_MODEL_PATH=outputs/model.pkl
         * model_path: /workspace/model/model.joblib
         */
        Pattern pattern = Pattern.compile(
                "(?i)([A-Za-z0-9_./\\\\\\-]+\\.(pkl|joblib|sav|cbm|pt|pth|h5|keras|onnx))"
        );

        Matcher matcher = pattern.matcher(logs);

        while (matcher.find()) {
            String path = cleanPath(matcher.group(1));

            if (!hasAcceptedExtension(path)) {
                continue;
            }

            DetectedModelArtifact artifact = new DetectedModelArtifact();
            artifact.setFound(true);
            artifact.setArtifactPath(path);
            artifact.setFileName(extractFileName(path));
            artifact.setExtension(extractExtension(path));
            artifact.setFormat(resolveFormat(path));
            artifact.setFramework(resolveFramework(path));
            artifact.setMlflowModel(false);
            artifact.setReason("Model file detected from training logs.");

            result.add(artifact);
        }

        return result;
    }

    private int scoreCandidate(DetectedModelArtifact artifact) {
        if (artifact == null || !hasText(artifact.getArtifactPath())) {
            return 0;
        }

        int score = 0;

        String path = normalize(artifact.getArtifactPath());
        String fileName = normalize(artifact.getFileName());

        if (Boolean.TRUE.equals(artifact.getMlflowModel())) {
            score += 1000;
        }

        for (int i = 0; i < PREFERRED_NAMES.size(); i++) {
            String preferred = PREFERRED_NAMES.get(i);

            if (fileName.equalsIgnoreCase(preferred)) {
                score += 500 - i;
            }
        }

        if (fileName.contains("best")) {
            score += 300;
        }

        if (fileName.contains("final")) {
            score += 250;
        }

        if (fileName.equals("model.pkl")
                || fileName.equals("model.joblib")
                || fileName.equals("model.cbm")) {
            score += 200;
        }

        if (isInsideAcceptedDir(path)) {
            score += 100;
        }

        if (path.contains("/workspace/outputs/") || path.contains("outputs/")) {
            score += 80;
        }

        if (path.endsWith(".pkl") || path.endsWith(".joblib")) {
            score += 50;
        }

        if (path.endsWith(".cbm")) {
            score += 45;
        }

        return score;
    }

    private boolean isInsideAcceptedDir(String path) {
        if (!hasText(path)) {
            return false;
        }

        String cleanPath = normalize(path);

        for (String dir : ACCEPTED_DIRS) {
            if (cleanPath.contains(normalize(dir))) {
                return true;
            }
        }

        return false;
    }

    private boolean hasAcceptedExtension(String path) {
        if (!hasText(path)) {
            return false;
        }

        String cleanPath = normalize(path);

        for (String extension : ACCEPTED_EXTENSIONS) {
            if (cleanPath.endsWith(extension)) {
                return true;
            }
        }

        return false;
    }

    private String resolveFormat(String path) {
        String extension = extractExtension(path);

        if (extension == null) {
            return "UNKNOWN";
        }

        return switch (extension.toLowerCase(Locale.ROOT)) {
            case ".pkl" -> "PICKLE";
            case ".joblib" -> "JOBLIB";
            case ".sav" -> "SAV";
            case ".cbm" -> "CATBOOST";
            case ".pt", ".pth" -> "PYTORCH";
            case ".h5", ".keras" -> "KERAS_TENSORFLOW";
            case ".onnx" -> "ONNX";
            default -> "UNKNOWN";
        };
    }

    private String resolveFramework(String path) {
        String extension = extractExtension(path);

        if (extension == null) {
            return "UNKNOWN";
        }

        return switch (extension.toLowerCase(Locale.ROOT)) {
            case ".cbm" -> "CATBOOST";
            case ".pt", ".pth" -> "PYTORCH";
            case ".h5", ".keras" -> "TENSORFLOW_KERAS";
            case ".onnx" -> "ONNX";
            case ".pkl", ".joblib", ".sav" -> "PYTHON_PICKLE_COMPATIBLE";
            default -> "UNKNOWN";
        };
    }

    private String cleanPath(String value) {
        if (value == null) {
            return null;
        }

        return value
                .trim()
                .replace("\\", "/")
                .replaceAll("[,;\\]\\)]+$", "");
    }

    private String extractFileName(String path) {
        if (!hasText(path)) {
            return null;
        }

        String cleanPath = cleanPath(path);

        int index = cleanPath.lastIndexOf('/');

        if (index >= 0 && index < cleanPath.length() - 1) {
            return cleanPath.substring(index + 1);
        }

        return cleanPath;
    }

    private String extractExtension(String path) {
        if (!hasText(path)) {
            return null;
        }

        String fileName = extractFileName(path);

        if (!hasText(fileName)) {
            return null;
        }

        int index = fileName.lastIndexOf('.');

        if (index < 0 || index >= fileName.length() - 1) {
            return null;
        }

        return fileName.substring(index).toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value
                .trim()
                .replace("\\", "/")
                .toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }

    public static class DetectedModelArtifact {

        private boolean found;
        private String artifactPath;
        private String fileName;
        private String extension;
        private String format;
        private String framework;
        private Boolean mlflowModel;
        private String reason;

        public static DetectedModelArtifact notFound(String reason) {
            DetectedModelArtifact artifact = new DetectedModelArtifact();
            artifact.setFound(false);
            artifact.setMlflowModel(false);
            artifact.setReason(reason);
            return artifact;
        }

        public boolean isFound() {
            return found;
        }

        public void setFound(boolean found) {
            this.found = found;
        }

        public String getArtifactPath() {
            return artifactPath;
        }

        public void setArtifactPath(String artifactPath) {
            this.artifactPath = artifactPath;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getExtension() {
            return extension;
        }

        public void setExtension(String extension) {
            this.extension = extension;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public String getFramework() {
            return framework;
        }

        public void setFramework(String framework) {
            this.framework = framework;
        }

        public Boolean getMlflowModel() {
            return mlflowModel;
        }

        public void setMlflowModel(Boolean mlflowModel) {
            this.mlflowModel = mlflowModel;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        @Override
        public String toString() {
            return "DetectedModelArtifact{" +
                    "found=" + found +
                    ", artifactPath='" + artifactPath + '\'' +
                    ", fileName='" + fileName + '\'' +
                    ", extension='" + extension + '\'' +
                    ", format='" + format + '\'' +
                    ", framework='" + framework + '\'' +
                    ", mlflowModel=" + mlflowModel +
                    ", reason='" + reason + '\'' +
                    '}';
        }
    }
}