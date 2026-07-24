package com.mlops.platform.controller;

import com.mlops.platform.dto.AuthResponse;
import com.mlops.platform.dto.LoginRequest;
import com.mlops.platform.dto.LoginResponse;
import com.mlops.platform.dto.RegisterRequest;
import com.mlops.platform.dto.UserResponse;
import com.mlops.platform.service.AuthService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import com.mlops.platform.dto.ForgotPasswordRequest;
import com.mlops.platform.dto.ResetPasswordRequest;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public UserResponse me(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        return authService.getCurrentUser(authHeader);
    }

    @GetMapping("/verify-email")
    public Map<String, String> verifyEmail(@RequestParam String token) {
        String message = authService.verifyEmail(token);

        return Map.of("message", message);
    }

    @PostMapping("/forgot-password")
    public Map<String, String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        String message = authService.forgotPassword(request);

        return Map.of("message", message);
    }
    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        String message = authService.resetPassword(request);

        return Map.of("message", message);
    }
}