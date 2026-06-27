package com.mlops.platform.controller;

import com.mlops.platform.dto.NotificationResponse;
import com.mlops.platform.entity.User;
import com.mlops.platform.security.JwtService;
import com.mlops.platform.repository.UserRepository;
import com.mlops.platform.service.NotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(origins = "http://localhost:4200")
public class NotificationController {

    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public NotificationController(
            NotificationService notificationService,
            UserRepository userRepository,
            JwtService jwtService
    ) {
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @GetMapping
    public List<NotificationResponse> getMyNotifications(
            @RequestHeader("Authorization") String authHeader
    ) {
        User user = getUserFromAuthorizationHeader(authHeader);
        return notificationService.getMyNotifications(user);
    }

    @GetMapping("/unread-count")
    public long getUnreadCount(
            @RequestHeader("Authorization") String authHeader
    ) {
        User user = getUserFromAuthorizationHeader(authHeader);
        return notificationService.countUnread(user);
    }

    @PutMapping("/{notificationId}/read")
    public void markAsRead(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long notificationId
    ) {
        User user = getUserFromAuthorizationHeader(authHeader);
        notificationService.markAsRead(user, notificationId);
    }

    @PutMapping("/read-all")
    public void markAllAsRead(
            @RequestHeader("Authorization") String authHeader
    ) {
        User user = getUserFromAuthorizationHeader(authHeader);
        notificationService.markAllAsRead(user);
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
}