package com.mlops.platform.service;

import com.mlops.platform.entity.UploadedFileType;

import java.util.Locale;
import java.util.Set;

/**
 * Normalisation CENTRALISÉE des noms de fichiers uploadés.
 *
 * Le navigateur / Windows peut générer des noms portant un suffixe de
 * duplication ("train (6).py", "data (2).csv", "Dockerfile (7)",
 * "requirements (6).txt"). Ces suffixes ne doivent JAMAIS casser
 * l'association rôle → fichier (dataset, script, Dockerfile, requirements).
 *
 * La plateforme identifie chaque fichier par son RÔLE EXPLICITE du formulaire
 * et dérive un nom d'exécution canonique (runtimeName) utilisé pour la
 * construction du contexte Docker/Kaniko et pour les logs. Le nom original
 * reste conservé pour l'affichage.
 *
 * Règles de runtimeName :
 *  - TRAINING_SCRIPT              -> train.py
 *  - DOCKERFILE                   -> Dockerfile
 *  - REQUIREMENTS                 -> requirements.txt
 *  - DATASET_PREPARATION_SCRIPT / PREPROCESSING_SCRIPT -> preprocess.py
 *  - DATASET_FILE / DATASET_ARCHIVE / DATASET / IMAGE_FILE / TEXT_FILE
 *      -> nom de base débarrassé du suffixe navigateur, extension réelle
 *         conservée ("data (2).csv" -> "data.csv", "images (1).zip" -> "images.zip")
 *  - autres -> nom nettoyé du suffixe navigateur.
 *
 * Validations (appelées à l'upload, avant sauvegarde) :
 *  - DATASET + .py/.py3           -> refus (aucun format dataset Python supporté) ;
 *  - TRAINING_SCRIPT sans .py     -> refus ;
 *  - REQUIREMENTS                 -> fichier requirements texte (.txt/.requirements/.req) ;
 *  - DOCKERFILE                   -> nom canonique Dockerfile (suffixe toléré) ;
 *  - PREPROCESSING_SCRIPT / DATASET_PREPARATION_SCRIPT sans .py -> refus.
 */
public final class UploadFileNameNormalizer {

    public static final String TRAINING_SCRIPT_RUNTIME_NAME = "train.py";
    public static final String PREPROCESSING_SCRIPT_RUNTIME_NAME = "preprocess.py";
    public static final String DOCKERFILE_RUNTIME_NAME = "Dockerfile";
    public static final String REQUIREMENTS_RUNTIME_NAME = "requirements.txt";

    private static final Set<String> DATASET_EXTENSIONS = Set.of(
            "csv", "tsv", "txt", "json", "jsonl", "parquet", "xlsx", "xls",
            "zip", "tar", "tgz", "gz", "rar", "7z", "bz2", "npz", "npy",
            "jpg", "jpeg", "png", "tif", "tiff"
    );

    private static final Set<String> REQUIREMENTS_EXTENSIONS = Set.of("txt", "requirements", "req", "pip");

    private static final Set<String> SCRIPT_EXTENSIONS = Set.of("py", "py3");

    private UploadFileNameNormalizer() {
    }

    /**
     * Retire un suffixe de duplication navigateur/Windows :
     * "train (6).py" -> "train.py", "train(1).py" -> "train.py",
     * "Dockerfile (7)" -> "Dockerfile", "data (2).csv" -> "data.csv".
     * Ne touche pas aux suffixes non numériques (ex: "model v2 (final).py").
     */
    public static String stripBrowserSuffix(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        return trimmed.replaceFirst("\\s*\\(\\d+\\)(?=(\\.[^.]*)?$)", "");
    }

    /** Extension sans point, minuscule, après suppression du suffixe navigateur. */
    public static String extensionOf(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String base = stripBrowserSuffix(name);
        int lastDot = base.lastIndexOf('.');
        if (lastDot == -1 || lastDot == base.length() - 1) {
            return "";
        }
        return base.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Nom d'exécution canonique du fichier pour le rôle donné.
     * Le nom original (éventuellement "train (6).py") n'est JAMAIS utilisé tel
     * quel pour l'exécution.
     */
    public static String runtimeName(String originalName, UploadedFileType role) {
        String stripped = stripBrowserSuffix(originalName);
        if (role == null) {
            return stripped;
        }
        return switch (role) {
            case TRAINING_SCRIPT -> TRAINING_SCRIPT_RUNTIME_NAME;
            case DOCKERFILE -> DOCKERFILE_RUNTIME_NAME;
            case REQUIREMENTS -> REQUIREMENTS_RUNTIME_NAME;
            case DATASET_PREPARATION_SCRIPT, PREPROCESSING_SCRIPT -> PREPROCESSING_SCRIPT_RUNTIME_NAME;
            case DATASET_FILE, DATASET_ARCHIVE, DATASET, IMAGE_FILE, TEXT_FILE -> {
                // Conserve le nom de base réel + extension réelle.
                yield stripped;
            }
            default -> stripped;
        };
    }

    /** Vrai si le nom (minuscule) correspond à un fichier dataset (extension de données). */
    public static boolean isDatasetFileName(String lowerCaseName) {
        if (lowerCaseName == null || lowerCaseName.isBlank()) {
            return false;
        }
        String lower = lowerCaseName.toLowerCase(Locale.ROOT);
        for (String ext : DATASET_EXTENSIONS) {
            if (lower.endsWith("." + ext) || lower.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Valide un fichier uploadé contre son rôle explicite.
     * Lance {@link IllegalArgumentException} si le fichier ne correspond pas au rôle.
     */
    public static void validate(String originalName, UploadedFileType role) {
        if (role == null) {
            return;
        }
        String name = originalName == null ? "" : originalName;
        String base = stripBrowserSuffix(name);
        String ext = extensionOf(name);

        switch (role) {
            case TRAINING_SCRIPT, DATASET_PREPARATION_SCRIPT, PREPROCESSING_SCRIPT -> {
                if (!SCRIPT_EXTENSIONS.contains(ext)) {
                    throw new IllegalArgumentException(
                            "Le fichier '" + name + "' doit être un script Python (.py/.py3) "
                                    + "pour le rôle " + role.name() + ".");
                }
            }
            case DOCKERFILE -> {
                String lowerBase = base.toLowerCase(Locale.ROOT);
                boolean nameOk = lowerBase.equals("dockerfile")
                        || lowerBase.startsWith("dockerfile.");
                if (!nameOk) {
                    throw new IllegalArgumentException(
                            "Le fichier '" + name + "' doit s'appeler 'Dockerfile' "
                                    + "pour le rôle DOCKERFILE.");
                }
            }
            case REQUIREMENTS -> {
                if (!REQUIREMENTS_EXTENSIONS.contains(ext)
                        && !base.toLowerCase(Locale.ROOT).contains("requirement")) {
                    throw new IllegalArgumentException(
                            "Le fichier '" + name + "' doit être un fichier requirements texte "
                                    + "(.txt, .requirements) pour le rôle REQUIREMENTS.");
                }
            }
            case DATASET_FILE, DATASET_ARCHIVE, DATASET, IMAGE_FILE, TEXT_FILE -> {
                if (SCRIPT_EXTENSIONS.contains(ext)) {
                    throw new IllegalArgumentException(
                            "Le fichier '" + name + "' est un script Python (.py/.py3) : "
                                    + "la plateforme ne supporte pas de dataset Python.");
                }
                if (!isDatasetFileName(base)) {
                    throw new IllegalArgumentException(
                            "Le fichier '" + name + "' n'a pas une extension de dataset reconnue "
                                    + "(csv, zip, parquet, json, txt, images...).");
                }
            }
            default -> {
                // AUXILIARY, MODEL_FILE, OTHER : aucune contrainte de nom.
            }
        }
    }
}