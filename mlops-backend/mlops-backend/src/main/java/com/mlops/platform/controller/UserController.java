package com.mlops.platform.controller;

import com.mlops.platform.dto.ChangePasswordRequest;
import com.mlops.platform.dto.UpdateProfileRequest;
import com.mlops.platform.dto.UserResponse;
import com.mlops.platform.service.UserService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "http://localhost:4200")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PutMapping("/me")
    public UserResponse updateCurrentUserProfile(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return userService.updateCurrentUserProfile(authHeader, request);
    }

    @PutMapping("/me/password")
    public Map<String, String> changeCurrentUserPassword(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        String message = userService.changeCurrentUserPassword(authHeader, request);
        return Map.of("message", message);
    }
}