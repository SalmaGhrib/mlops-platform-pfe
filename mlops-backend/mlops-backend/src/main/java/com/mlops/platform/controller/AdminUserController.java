package com.mlops.platform.controller;

import com.mlops.platform.dto.SearchPageResponse;
import com.mlops.platform.dto.UpdateUserRoleRequest;
import com.mlops.platform.dto.UpdateUserStatusRequest;
import com.mlops.platform.dto.UserResponse;
import com.mlops.platform.service.AdminUserService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
@CrossOrigin(origins = "http://localhost:4200")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public List<UserResponse> getAllUsers() {
        return adminUserService.getAllUsers();
    }

    /**
     * Recherche dynamique des utilisateurs avec filtres et pagination.
     *
     * @param q      texte libre (email / prénom / nom)
     * @param status filtre ACTIVE | BLOCKED
     * @param role   filtre ADMIN | USER
     */
    @GetMapping("/search")
    public SearchPageResponse<UserResponse> searchUsers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size
    ) {
        return adminUserService.searchUsers(q, status, role, page, size);
    }

    @PutMapping("/{id}/role")
    public UserResponse updateUserRole(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRoleRequest request
    ) {
        return adminUserService.updateUserRole(id, request);
    }

    @PutMapping("/{id}/status")
    public UserResponse updateUserStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserStatusRequest request
    ) {
        return adminUserService.updateUserStatus(id, request);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> deleteUser(@PathVariable Long id) {
        String message = adminUserService.deleteUser(id);

        return Map.of("message", message);
    }
}