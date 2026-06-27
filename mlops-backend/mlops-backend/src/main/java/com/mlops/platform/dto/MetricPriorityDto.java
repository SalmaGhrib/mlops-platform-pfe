package com.mlops.platform.dto;

public class MetricPriorityDto {

    private String metricName;
    private String mode; // MIN ou MAX
    private Integer priority;

    public MetricPriorityDto() {
    }

    public MetricPriorityDto(String metricName, String mode, Integer priority) {
        this.metricName = metricName;
        this.mode = mode;
        this.priority = priority;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }
}