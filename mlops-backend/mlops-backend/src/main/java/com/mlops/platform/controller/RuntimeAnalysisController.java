package com.mlops.platform.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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

import java.util.LinkedHashMap;
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
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("service", "runtime-analysis-proxy");

        return ResponseEntity.ok(response);
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
            Map<String, Object> errorResponse = new LinkedHashMap<>();
            errorResponse.put("runtimeCompatible", false);
            errorResponse.put("runtime_compatible", false);
            errorResponse.put("compatible", false);
            errorResponse.put("status", "error");
            errorResponse.put("message", "No script file provided.");
            errorResponse.put("recommendation", "Upload a Python script file.");

            return ResponseEntity.badRequest().body(errorResponse);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ByteArrayResource fileResource = new ByteArrayResource(selectedFile.getBytes()) {
                @Override
                public String getFilename() {
                    String originalName = selectedFile.getOriginalFilename();

                    if (originalName == null || originalName.isBlank()) {
                        return "script.py";
                    }

                    return originalName;
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

            Map<String, Object> fallbackResponse = new LinkedHashMap<>();

            fallbackResponse.put("runtimeCompatible", true);
            fallbackResponse.put("runtime_compatible", true);
            fallbackResponse.put("compatible", true);
            fallbackResponse.put("supported", true);
            fallbackResponse.put("status", "success");

            fallbackResponse.put("detectedImports", List.of());
            fallbackResponse.put("detected_imports", List.of());

            fallbackResponse.put("supportedImports", List.of());
            fallbackResponse.put("supported_imports", List.of());

            fallbackResponse.put("unsupportedImports", List.of());
            fallbackResponse.put("unsupported_imports", List.of());

            fallbackResponse.put("detectedModules", List.of());
            fallbackResponse.put("detected_modules", List.of());

            fallbackResponse.put("supportedModules", List.of());
            fallbackResponse.put("supported_modules", List.of());

            fallbackResponse.put("unsupportedModules", List.of());
            fallbackResponse.put("unsupported_modules", List.of());

            fallbackResponse.put("detectedLibraries", List.of());
            fallbackResponse.put("detected_libraries", List.of());

            fallbackResponse.put("supportedLibraries", List.of());
            fallbackResponse.put("supported_libraries", List.of());

            fallbackResponse.put("unsupportedLibraries", List.of());
            fallbackResponse.put("unsupported_libraries", List.of());

            fallbackResponse.put("missingDependencies", List.of());
            fallbackResponse.put("missing_dependencies", List.of());

            fallbackResponse.put("detectedMetrics", List.of());
            fallbackResponse.put("detected_metrics", List.of());

            fallbackResponse.put("detectedAlgorithms", List.of());
            fallbackResponse.put("detected_algorithms", List.of());

            fallbackResponse.put("replaceableAlgorithms", List.of());
            fallbackResponse.put("replaceable_algorithms", List.of());

            fallbackResponse.put("warnings", List.of(
                    "Runtime analyzer unavailable. Continuing with STANDARD_RUNTIME fallback."
            ));

            fallbackResponse.put(
                    "message",
                    "Runtime analysis unavailable. The platform will continue with STANDARD_RUNTIME."
            );

            fallbackResponse.put(
                    "summary",
                    "Runtime analysis unavailable. STANDARD_RUNTIME fallback enabled."
            );

            fallbackResponse.put(
                    "recommendation",
                    "Check runtime analyzer service configuration in AKS."
            );

            return ResponseEntity.ok(fallbackResponse);
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