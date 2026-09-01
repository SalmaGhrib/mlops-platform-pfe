package com.mlops.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mlops.platform.repository.MlExperimentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringServiceTest {

    @Mock private MlExperimentRepository mlExperimentRepository;

    private MonitoringService service;

    @BeforeEach
    void setUp() {
        service = new MonitoringService(new ObjectMapper(), mlExperimentRepository);
        ReflectionTestUtils.setField(service, "prometheusUrl", "http://localhost:9090");
    }

    @Test
    void getSummary_returnsResponseWithDefaults() {
        when(mlExperimentRepository.count()).thenReturn(0L);

        var response = service.getSummary();

        assertNotNull(response);
    }

    @Test
    void getSummary_withExperiments_returnsResponse() {
        when(mlExperimentRepository.count()).thenReturn(5L);

        var response = service.getSummary();

        assertNotNull(response);
    }
}
