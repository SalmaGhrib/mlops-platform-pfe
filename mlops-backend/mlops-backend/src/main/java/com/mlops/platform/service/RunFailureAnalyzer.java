package com.mlops.platform.service;

import java.util.Locale;

public class RunFailureAnalyzer {

    public static class FailureAnalysis {
        private final String phase;
        private final String reason;
        private final String details;
        private final String suggestion;
        private final String sourceFile;
        private final String logExcerpt;

        public FailureAnalysis(String phase, String reason, String details, String suggestion, String sourceFile, String logExcerpt) {
            this.phase = phase;
            this.reason = reason;
            this.details = details;
            this.suggestion = suggestion;
            this.sourceFile = sourceFile;
            this.logExcerpt = logExcerpt;
        }

        public String getPhase() { return phase; }
        public String getReason() { return reason; }
        public String getDetails() { return details; }
        public String getSuggestion() { return suggestion; }
        public String getSourceFile() { return sourceFile; }
        public String getLogExcerpt() { return logExcerpt; }
    }

    public static FailureAnalysis analyze(String logs) {
        if (logs == null) logs = "";
        String lower = logs.toLowerCase(Locale.ROOT);

        String excerpt = extractExcerpt(logs);

        if (lower.contains("failed to get fileinfo") || lower.contains("no such file or directory") || lower.contains("failed to stat")) {
            return new FailureAnalysis("BUILD_IMAGE", "Fichier référencé dans le Dockerfile introuvable", extractFirstLine(logs), "Vérifiez les COPY dans le Dockerfile. Le fichier référencé doit exister dans le build context.", "Dockerfile", excerpt);
        }

        if (lower.contains("copy failed")) {
            return new FailureAnalysis("BUILD_IMAGE", "Erreur COPY dans Dockerfile", extractFirstLine(logs), "Corrigez la commande COPY dans le Dockerfile.", "Dockerfile", excerpt);
        }

        if (lower.contains("no matching distribution found") || lower.contains("could not find a version that satisfies the requirement")) {
            return new FailureAnalysis("BUILD_IMAGE", "Dépendance introuvable", extractFirstLine(logs), "Vérifiez le nom ou la version de la dépendance dans requirements.txt.", "requirements.txt", excerpt);
        }

        if (lower.contains("no module named") || lower.contains("modulenotfounderror")) {
            return new FailureAnalysis("USER_SCRIPT", "Dépendance Python manquante", extractFirstLine(logs), "Ajoutez le module manquant dans requirements.txt.", "requirements.txt", excerpt);
        }

        if (lower.contains("syntaxerror")) {
            return new FailureAnalysis("USER_SCRIPT", "Erreur de syntaxe Python", extractFirstLine(logs), "Corrigez la syntaxe du script Python.", "train.py", excerpt);
        }

        if (lower.contains("numpy is not available") || lower.contains("a module that was compiled using numpy 1.x cannot be run in numpy 2")) {
            return new FailureAnalysis("TRAINING", "Incompatibilité NumPy/PyTorch", extractFirstLine(logs), "Pinnez une version compatible de NumPy, par exemple numpy==1.26.4 pour torch==2.2.0.", "requirements.txt", excerpt);
        }

        if (lower.contains("connection timed out") || lower.contains("temporary failure in name resolution") || lower.contains("name or service not known") || lower.contains("http error 404")) {
            return new FailureAnalysis("DATASET", "Erreur réseau / téléchargement de dataset", extractFirstLine(logs), "Vérifiez l'accès réseau/DNS du cluster ou l'URL fournie.", "train.py", excerpt);
        }

        if (lower.contains("cuda") || lower.contains("libcudart") || lower.contains("out of memory") || lower.contains("killed")) {
            return new FailureAnalysis("TRAINING", "Problème CUDA/GPU ou mémoire", extractFirstLine(logs), "Vérifiez la compatibilité GPU ou augmentez les ressources.", "environment", excerpt);
        }

        if (lower.contains("mlflow") && lower.contains("exception")) {
            return new FailureAnalysis("MLFLOW", "Erreur MLflow", extractFirstLine(logs), "Vérifiez la configuration MLflow et son accessibilité.", "MLflow", excerpt);
        }

        // Fallbacks
        if (lower.contains("pip install") && lower.contains("error")) {
            return new FailureAnalysis("BUILD_IMAGE", "Erreur pip durant l'installation des dépendances", extractFirstLine(logs), "Vérifiez requirements.txt.", "requirements.txt", excerpt);
        }

        if (lower.contains("permission denied")) {
            return new FailureAnalysis("TRAINING", "Permission refusée", extractFirstLine(logs), "Vérifiez les permissions sur les fichiers/dossiers.", "train.py", excerpt);
        }

        return new FailureAnalysis("UNKNOWN", "Erreur non classifiée", extractFirstLine(logs), "Consultez les logs détaillés.", "unknown", excerpt);
    }

    /**
     * Classe une erreur en code stable (String), utilisé par les étapes de
     * pipeline (colonne error_code de run_pipeline_steps). Complémentaire de
     * analyze() qui, lui, produit le contenu FR du FailureAnalysis.
     */
    public static String classifyErrorCode(String logs) {
        if (logs == null) logs = "";
        String lower = logs.toLowerCase(Locale.ROOT);

        if (lower.contains("no matching distribution found")
                || lower.contains("could not find a version that satisfies the requirement")
                || lower.contains("failed to get fileinfo")
                || lower.contains("no such file or directory")
                || lower.contains("copy failed")
                || lower.contains("failed to stat")) {
            return "BUILD_FAILED";
        }

        if (lower.contains("manifest for ") && lower.contains(" not found")
                || lower.contains("pull access denied")) {
            return "IMAGE_PULL_FAILED";
        }

        if (lower.contains("cannot allocate memory")
                || lower.contains("killed process")
                || lower.contains("oom-kill")) {
            return "OOM_KILLED";
        }

        if (lower.contains("no module named")
                || lower.contains("modulenotfounderror")
                || lower.contains("syntaxerror")
                || lower.contains("traceback (most recent call last)")) {
            return "USER_SCRIPT_FAILED";
        }

        if (lower.contains("dataset") && lower.contains("not found")
                || lower.contains("no such file") && lower.contains("dataset")
                || lower.contains("dataset path")) {
            return "DATASET_PATH_MISMATCH";
        }

        if (lower.contains("mlflow") && lower.contains("exception")) {
            return "MLFLOW_REGISTRATION_FAILED";
        }

        if (lower.contains("cuda")
                || lower.contains("libcudart")
                || lower.contains("out of memory")
                || lower.contains("killed")) {
            return "OOM_KILLED";
        }

        if (lower.contains("permission denied")) {
            return "USER_SCRIPT_FAILED";
        }

        return "KUBERNETES_JOB_FAILED";
    }

    private static String extractFirstLine(String text) {
        if (text == null || text.isBlank()) return "";
        String[] lines = text.split("\\r?\\n");
        return lines.length > 0 ? lines[0] : text;
    }

    private static String extractExcerpt(String text) {
        if (text == null) return "";
        String[] lines = text.split("\\r?\\n");
        int start = Math.max(0, lines.length - 50);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        String out = sb.toString();
        if (out.length() > 2000) return out.substring(out.length() - 2000);
        return out;
    }
}
