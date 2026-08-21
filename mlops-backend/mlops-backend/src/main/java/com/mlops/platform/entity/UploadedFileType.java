package com.mlops.platform.entity;

/**
 * Rôle explicite d'un fichier dans la plateforme MLOps.
 *
 * Le rôle ne doit JAMAIS être déduit uniquement de l'extension du fichier :
 * il est fourni/validé à l'upload et persisté. Cette séparation stricte évite
 * par exemple qu'un script de préparation de dataset (.py) soit confondu avec
 * un dataset tabulaire et transmis comme DATA_PATH au script d'entraînement.
 */
public enum UploadedFileType {

    /**
     * Script d'entraînement principal (train.py ...) — exécuté par le runner.
     */
    TRAINING_SCRIPT,

    /**
     * Fichier de données tabulaire directement exploitable (CSV, TSV, Parquet, XLSX).
     */
    DATASET_FILE,

    /**
     * Archive de dataset (ZIP, TAR.GZ, 7Z ...) nécessitant une extraction.
     * Peut contenir des images (train/val/test) ou des fichiers texte.
     */
    DATASET_ARCHIVE,

    /**
     * Script Python dont le rôle est de GÉNÉRER le dataset.
     * Contrat officiel : python prepare_dataset.py --output-dir <dir>
     * Le runner exécute ce script puis résout le vrai chemin du dataset
     * (marqueur PLATFORM_DATASET_PATH ou structure connue dans output-dir).
     */
    DATASET_PREPARATION_SCRIPT,

    /**
     * Script Python de pré-traitement du dataset (normalisation, nettoyage,
     * feature engineering). Ne produit JAMAIS de chemin DATASET_PATH :
     * il est exécuté avant l'entraînement pour préparer les données.
     * Nom d'exécution canonique : preprocess.py
     */
    PREPROCESSING_SCRIPT,

    /**
     * Dockerfile pour le mode CUSTOM_DOCKERFILE.
     */
    DOCKERFILE,

    /**
     * requirements.txt pour le mode CUSTOM_DOCKERFILE.
     */
    REQUIREMENTS,

    /**
     * Fichier auxiliaire : scaler, preprocessor, métadonnées, config...
     * Jamais utilisé comme dataset ou script principal.
     */
    AUXILIARY,

    // ---- Valeurs héritées (rétrocompatibilité des données existantes) ----
    MODEL_FILE,
    DATASET,
    IMAGE_FILE,
    TEXT_FILE,
    OTHER
}