package com.mlops.platform.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public class ModelApiKeyCreateRequest {

    private String name;

    private Long dailyQuota;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss[.SSS]['Z']")
    private LocalDateTime expiresAt;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getDailyQuota() {
        return dailyQuota;
    }

    public void setDailyQuota(Long dailyQuota) {
        this.dailyQuota = dailyQuota;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
