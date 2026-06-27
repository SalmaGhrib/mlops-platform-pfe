package com.mlops.platform.controller;

import com.mlops.platform.dto.PredictionRequestDto;
import com.mlops.platform.dto.PredictionResponseDto;
import com.mlops.platform.dto.ProductionModelDto;
import com.mlops.platform.service.PredictionService;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/predictions")
@CrossOrigin(
        origins = "http://localhost:4200",
        allowedHeaders = "*",
        methods = {
                RequestMethod.GET,
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.DELETE,
                RequestMethod.OPTIONS
        }
)
@SecurityRequirement(name = "bearerAuth")
public class PredictionController {

    private final PredictionService predictionService;

    public PredictionController(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @GetMapping("/my-production-models")
    public ResponseEntity<?> getMyProductionModels(
            Principal principal,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader
    ) {
        try {
            List<ProductionModelDto> models =
                    predictionService.getMyProductionModels(principal, authorizationHeader);

            return ResponseEntity.ok(models);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    new ErrorResponse("BAD_REQUEST", e.getMessage())
            );

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new ErrorResponse("LOAD_PRODUCTION_MODELS_FAILED", e.getMessage())
            );
        }
    }

    @PostMapping("/predict")
    public ResponseEntity<?> predict(
            @RequestBody PredictionRequestDto request,
            Principal principal,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader
    ) {
        try {
            PredictionResponseDto response =
                    predictionService.predict(request, principal, authorizationHeader);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    new ErrorResponse("BAD_REQUEST", e.getMessage())
            );

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    new ErrorResponse("FORBIDDEN", e.getMessage())
            );

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new ErrorResponse("PREDICTION_FAILED", e.getMessage())
            );
        }
    }

    @PostMapping(
            value = "/predict-image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<?> predictImage(
            @RequestParam("modelName") String modelName,
            @RequestParam(value = "modelAlias", defaultValue = "production") String modelAlias,
            @RequestParam("imageFile") MultipartFile imageFile,
            Principal principal,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader
    ) {
        try {
            PredictionResponseDto response =
                    predictionService.predictImage(
                            modelName,
                            modelAlias,
                            imageFile,
                            principal,
                            authorizationHeader
                    );

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    new ErrorResponse("BAD_REQUEST", e.getMessage())
            );

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    new ErrorResponse("FORBIDDEN", e.getMessage())
            );

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new ErrorResponse("IMAGE_PREDICTION_FAILED", e.getMessage())
            );
        }
    }

    @PostMapping("/predict-text")
    public ResponseEntity<?> predictText(
            @RequestBody PredictionTextRequest request,
            Principal principal,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader
    ) {
        try {
            PredictionResponseDto response =
                    predictionService.predictText(
                            request.getModelName(),
                            request.getModelAlias(),
                            request.getText(),
                            principal,
                            authorizationHeader
                    );

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    new ErrorResponse("BAD_REQUEST", e.getMessage())
            );

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    new ErrorResponse("FORBIDDEN", e.getMessage())
            );

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new ErrorResponse("TEXT_PREDICTION_FAILED", e.getMessage())
            );
        }
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(
                new HealthResponse("ok", "Prediction controller is running.")
        );
    }

    public static class PredictionTextRequest {
        private String modelName;
        private String modelAlias = "production";
        private String text;

        public PredictionTextRequest() {
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

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    public static class ErrorResponse {
        private String code;
        private String message;

        public ErrorResponse() {
        }

        public ErrorResponse(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class HealthResponse {
        private String status;
        private String message;

        public HealthResponse() {
        }

        public HealthResponse(String status, String message) {
            this.status = status;
            this.message = message;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}