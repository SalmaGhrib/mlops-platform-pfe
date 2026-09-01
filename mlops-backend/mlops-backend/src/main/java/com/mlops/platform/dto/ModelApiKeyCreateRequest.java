package com.mlops.platform.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public class ModelApiKeyCreateRequest {

    private String name;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss[.SSS]['Z']")
    private LocalDateTime expiresAt;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
