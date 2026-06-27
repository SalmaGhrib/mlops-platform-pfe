package com.mlops.platform.controller;

import com.mlops.platform.dto.MonitoringSummaryResponse;
import com.mlops.platform.service.MonitoringService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/monitoring")
@CrossOrigin(origins = "http://localhost:4200")
public class MonitoringController {

    private final MonitoringService monitoringService;

    public MonitoringController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @GetMapping("/summary")
    public ResponseEntity<MonitoringSummaryResponse> getMonitoringSummary() {
        return ResponseEntity.ok(monitoringService.getSummary());
    }
}