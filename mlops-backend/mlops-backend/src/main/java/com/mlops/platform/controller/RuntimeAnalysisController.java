package com.mlops.platform.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeAnalysisController {

    private final RestTemplate restTemplate;

    @Value("${app.runtime.analysis-api-url:http://127.0.0.1:8001/api/runtime/analyze-script}")
    private String runtimeAnalysisApiUrl;

    public RuntimeAnalysisController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "runtime-analysis-proxy"
        ));
    }

    @PostMapping(
            value = "/analyze-script",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> analyzeScript(
            @RequestParam(value = "script_file", required = false) MultipartFile scriptFile,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "scriptFile", required = false) MultipartFile scriptFileCamel,
            @RequestParam(value = "model_file", required = false) MultipartFile modelFile
    ) {
        MultipartFile selectedFile = firstNonEmpty(scriptFile, file, scriptFileCamel, modelFile);

        if (selectedFile == null || selectedFile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "runtimeCompatible", false,
                    "runtime_compatible", false,
                    "message", "No script file provided.",
                    "recommendation", "Upload a Python script file."
            ));
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ByteArrayResource fileResource = new ByteArrayResource(selectedFile.getBytes()) {
                @Override
                public String getFilename() {
                    String originalName = selectedFile.getOriginalFilename();
                    return originalName == null || originalName.isBlank()
                            ? "script.py"
                            : originalName;
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("script_file", fileResource);
            body.add("file", fileResource);
            body.add("scriptFile", fileResource);
            body.add("model_file", fileResource);

            HttpEntity<MultiValueMap<String, Object>> requestEntity =
                    new HttpEntity<>(body, headers);

            System.out.println("[RUNTIME_ANALYSIS_PROXY] Forwarding analysis request to: "
                    + runtimeAnalysisApiUrl);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    runtimeAnalysisApiUrl,
                    requestEntity,
                    String.class
            );

            return ResponseEntity
                    .status(response.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.getBody());

        } catch (Exception e) {
            System.out.println("[RUNTIME_ANALYSIS_PROXY] Runtime analyzer unavailable.");
            System.out.println("[RUNTIME_ANALYSIS_PROXY] Reason: " + e.getMessage());

            return ResponseEntity.ok(Map.of(
                    "runtimeCompatible", true,
                    "runtime_compatible", true,
                    "compatible", true,
                    "status", "success",
                    "detectedImports", List.of(),
                    "supportedImports", List.of(),
                    "unsupportedImports", List.of(),
                    "detectedModules", List.of(),
                    "supportedModules", List.of(),
                    "unsupportedModules", List.of(),
                    "detectedLibraries", List.of(),
                    "supportedLibraries", List.of(),
                    "unsupportedLibraries", List.of(),
                    "missingDependencies", List.of(),
                    "detectedMetrics", List.of(),
                    "detectedAlgorithms", List.of(),
                    "warnings", List.of(
                            "Runtime analyzer unavailable. Continuing with STANDARD_RUNTIME fallback."
                    ),
                    "message", "Runtime analysis unavailable. The platform will continue with STANDARD_RUNTIME.",
                    "recommendation", "Check runtime analyzer service configuration in AKS."
            ));
        }
    }

    private MultipartFile firstNonEmpty(MultipartFile... files) {
        if (files == null) {
            return null;
        }

        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                return file;
            }
        }

        return null;
    }
}