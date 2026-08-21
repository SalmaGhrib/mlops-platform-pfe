package com.mlops.platform.controller;

import com.mlops.platform.dto.AdminMonitoringOverviewResponse;
import com.mlops.platform.service.AdminMonitoringOverviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/monitoring")
@CrossOrigin(origins = "http://localhost:4200")
public class AdminMonitoringOverviewController {

    private final AdminMonitoringOverviewService adminMonitoringOverviewService;

    public AdminMonitoringOverviewController(
            AdminMonitoringOverviewService adminMonitoringOverviewService
    ) {
        this.adminMonitoringOverviewService = adminMonitoringOverviewService;
    }

    @GetMapping("/overview")
    public ResponseEntity<AdminMonitoringOverviewResponse> getOverview() {
        return ResponseEntity.ok(adminMonitoringOverviewService.getOverview());
    }
}