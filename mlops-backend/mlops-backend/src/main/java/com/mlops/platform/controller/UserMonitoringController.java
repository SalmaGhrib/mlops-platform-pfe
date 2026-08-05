package com.mlops.platform.controller;

import com.mlops.platform.dto.UserMonitoringDashboardResponse;
import com.mlops.platform.service.UserMonitoringService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user-monitoring")
@CrossOrigin(origins = "http://localhost:4200")
public class UserMonitoringController {

    private final UserMonitoringService userMonitoringService;

    public UserMonitoringController(UserMonitoringService userMonitoringService) {
        this.userMonitoringService = userMonitoringService;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<UserMonitoringDashboardResponse> getDashboard(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) Long projectId
    ) {
        return ResponseEntity.ok(
                userMonitoringService.getDashboard(authHeader, projectId)
        );
    }
}