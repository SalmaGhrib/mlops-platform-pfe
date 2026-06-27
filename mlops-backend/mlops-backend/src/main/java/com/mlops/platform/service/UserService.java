package com.mlops.platform.service;

import com.mlops.platform.dto.ChangePasswordRequest;
import com.mlops.platform.dto.UpdateProfileRequest;
import com.mlops.platform.dto.UserResponse;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.UserRepository;
import com.mlops.platform.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse updateCurrentUserProfile(String authHeader, UpdateProfileRequest request) {

        User user = getUserFromAuthorizationHeader(authHeader);

        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhone(request.getPhone());
        user.setNationality(request.getNationality());
        user.setBio(request.getBio());

        User savedUser = userRepository.save(user);

        return mapToUserResponse(savedUser);
    }

    public String changeCurrentUserPassword(String authHeader, ChangePasswordRequest request) {

        User user = getUserFromAuthorizationHeader(authHeader);

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new RuntimeException("Current password is incorrect");
        }

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new RuntimeException("New password and confirmation do not match");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new RuntimeException("New password must be different from current password");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        return "Password updated successfully";
    }

    private User getUserFromAuthorizationHeader(String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);

        if (!jwtService.isTokenValid(token)) {
            throw new RuntimeException("Invalid or expired token");
        }

        String email = jwtService.extractEmail(token);

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private UserResponse mapToUserResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getNationality(),
                user.getBio(),
                user.getRole().getName().name(),
                user.getStatus().name(),
                user.isEmailVerified(),
                user.getCreatedAt() != null ? user.getCreatedAt().toString() : "-"
        );
    }
}