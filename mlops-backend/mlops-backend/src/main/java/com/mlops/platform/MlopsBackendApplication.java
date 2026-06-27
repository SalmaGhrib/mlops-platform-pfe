package com.mlops.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MlopsBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MlopsBackendApplication.class, args);
    }
}