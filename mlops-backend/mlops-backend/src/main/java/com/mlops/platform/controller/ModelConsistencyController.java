package com.mlops.platform.controller;

import com.mlops.platform.dto.ModelConsistencyResponse;
import com.mlops.platform.service.ModelConsistencyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/model-consistency")
public class ModelConsistencyController {

    private final ModelConsistencyService consistencyService;

    public ModelConsistencyController(ModelConsistencyService consistencyService) {
        this.consistencyService = consistencyService;
    }

    @GetMapping("/{modelName}")
    public ResponseEntity<ModelConsistencyResponse> audit(@PathVariable String modelName) {
        return ResponseEntity.ok(consistencyService.auditModel(modelName));
    }

    @PostMapping("/{modelName}/reconcile")
    public ResponseEntity<ModelConsistencyResponse> reconcile(@PathVariable String modelName) {
        return ResponseEntity.ok(consistencyService.reconcileModel(modelName));
    }

    @PostMapping("/reconcile-all")
    public ResponseEntity<List<ModelConsistencyResponse>> reconcileAll() {
        return ResponseEntity.ok(consistencyService.reconcileAllModels());
    }
}
