package com.mlops.platform.service;

import com.mlops.platform.dto.UpdateUserRoleRequest;
import com.mlops.platform.dto.UpdateUserStatusRequest;
import com.mlops.platform.dto.UserResponse;
import com.mlops.platform.entity.AccountStatus;
import com.mlops.platform.entity.Role;
import com.mlops.platform.entity.RoleType;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.RoleRepository;
import com.mlops.platform.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    public AdminUserService(UserRepository userRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::mapToUserResponse)
                .toList();
    }

    public UserResponse updateUserRole(Long userId, UpdateUserRoleRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        RoleType newRoleType;

        try {
            newRoleType = RoleType.valueOf(request.getRole().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new RuntimeException("Invalid role. Allowed roles are USER and ADMIN");
        }

        Role role = roleRepository.findByName(newRoleType)
                .orElseGet(() -> {
                    Role newRole = new Role();
                    newRole.setName(newRoleType);
                    return roleRepository.save(newRole);
                });

        user.setRole(role);

        User savedUser = userRepository.save(user);

        return mapToUserResponse(savedUser);
    }

    public UserResponse updateUserStatus(Long userId, UpdateUserStatusRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        AccountStatus newStatus;

        try {
            newStatus = AccountStatus.valueOf(request.getStatus().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new RuntimeException("Invalid status. Allowed statuses are ACTIVE and BLOCKED");
        }

        user.setStatus(newStatus);

        User savedUser = userRepository.save(user);

        return mapToUserResponse(savedUser);
    }

    public String deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        userRepository.delete(user);

        return "User deleted successfully";
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