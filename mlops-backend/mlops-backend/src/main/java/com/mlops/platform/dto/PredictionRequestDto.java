package com.mlops.platform.dto;

import java.util.List;
import java.util.Map;

public class PredictionRequestDto {

    private List<Map<String, Object>> rows;
    private String modelName;
    private String modelAlias;

    public PredictionRequestDto() {
    }

    public PredictionRequestDto(List<Map<String, Object>> rows, String modelName, String modelAlias) {
        this.rows = rows;
        this.modelName = modelName;
        this.modelAlias = modelAlias;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getModelAlias() {
        return modelAlias;
    }

    public void setModelAlias(String modelAlias) {
        this.modelAlias = modelAlias;
    }
}