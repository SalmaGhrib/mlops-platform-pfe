package com.mlops.platform.service;

import com.mlops.platform.entity.UploadedProjectFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineClientServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private PipelineClientService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new PipelineClientService(restTemplate);
        ReflectionTestUtils.setField(service, "pipelineGatewayUrl", "http://localhost:8001/upload-and-run-custom-model");
        ReflectionTestUtils.setField(service, "kubeflowApiUrl", "http://localhost:8889");
    }

    private UploadedProjectFile file(String name) throws IOException {
        Path filePath = tempDir.resolve(name);
        Files.writeString(filePath, "content");
        UploadedProjectFile f = new UploadedProjectFile();
        f.setOriginalFileName(name);
        f.setStoredFileName("abc-" + name);
        f.setFilePath(filePath.toAbsolutePath().toString());
        f.setSizeBytes(100L);
        return f;
    }

    @Test
    void launchCustomModelPipeline_nullModelFile_throwsRuntimeException() {
        assertThrows(RuntimeException.class, () ->
                service.launchCustomModelPipeline(
                        1L, 100L, "proj", "model", "reg", "run", "target",
                        "accuracy", "maximize", "CLASSIFICATION",
                        "CUSTOM_DOCKERFILE", null, null
                ));
    }

    @Test
    void launchCustomModelPipeline_customDockerfile_returnsFailedImmediately() throws IOException {
        var result = service.launchCustomModelPipeline(
                1L, 100L, "proj", "model", "reg", "run", "target",
                "accuracy", "maximize", "CLASSIFICATION",
                "CUSTOM_DOCKERFILE", file("model.py"), null
        );

        assertNotNull(result);
        assertEquals("FAILED", result.getStatus());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void launchCustomModelPipeline_standard_callsGateway() throws IOException {
        String jsonResponse = "{\"status\":\"SUCCEEDED\",\"kubeflow_run_id\":\"run-abc\"}";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(jsonResponse));

        var result = service.launchCustomModelPipeline(
                1L, 100L, "proj", "model", "reg", "run", "target",
                "accuracy", "maximize", "CLASSIFICATION",
                "STANDARD_RUNTIME", file("model.py"), null
        );

        assertNotNull(result);
        verify(restTemplate).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void launchCustomModelPipeline_restTemplateThrows_throwsRuntimeException() throws IOException {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        assertThrows(RuntimeException.class, () ->
                service.launchCustomModelPipeline(
                        1L, 100L, "proj", "model", "reg", "run", "target",
                        "accuracy", "maximize", "CLASSIFICATION",
                        "STANDARD_RUNTIME", file("model.py"), null
                ));
    }

    @Test
    void launchCustomModelPipeline_nullMode_treatedAsStandard() throws IOException {
        String jsonResponse = "{\"status\":\"SUCCEEDED\"}";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(jsonResponse));

        var result = service.launchCustomModelPipeline(
                1L, 100L, "proj", "model", "reg", "run", "target",
                "accuracy", "maximize", "CLASSIFICATION",
                null, file("model.py"), null
        );

        assertNotNull(result);
    }

    @Test
    void deleteKubeflowRun_nullId_returnsFalse() {
        boolean result = service.deleteKubeflowRun(null);
        assertFalse(result);
        verifyNoInteractions(restTemplate);
    }

    @Test
    void deleteKubeflowRun_emptyId_returnsFalse() {
        boolean result = service.deleteKubeflowRun("  ");
        assertFalse(result);
        verifyNoInteractions(restTemplate);
    }

    @Test
    void deleteKubeflowRun_allEndpointsFail_returnsFalse() {
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenThrow(new RestClientException("timeout"));

        boolean result = service.deleteKubeflowRun("run-123");

        assertFalse(result);
    }
}
