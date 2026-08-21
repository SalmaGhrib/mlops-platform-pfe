package com.mlops.platform.dto;

/**
 * Nouvelle clé de retraining générée.
 *
 * La clé complète n'est renvoyée qu'UNE SEULE fois, à sa génération.
 * Elle ne sera plus jamais visible ensuite (seul son hash est stocké).
 */
public class GitHubKeyResponse {

    private String secretKey;
    private String secretSuffix;

    public GitHubKeyResponse() {
    }

    public GitHubKeyResponse(String secretKey, String secretSuffix) {
        this.secretKey = secretKey;
        this.secretSuffix = secretSuffix;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getSecretSuffix() {
        return secretSuffix;
    }

    public void setSecretSuffix(String secretSuffix) {
        this.secretSuffix = secretSuffix;
    }
}