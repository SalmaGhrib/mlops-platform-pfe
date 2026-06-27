package com.mlops.platform.entity;

public enum DeploymentVisibility {
    /*
     * Accès privé :
     * seul le propriétaire du projet peut utiliser le modèle depuis la plateforme.
     */
    PRIVATE,

    /*
     * Accès externe sécurisé :
     * une application externe peut consommer le modèle avec une clé API.
     */
    PUBLIC_API,

    /*
     * Réservé pour plus tard :
     * partage avec une équipe, un autre projet ou des utilisateurs précis.
     */
    SHARED
}