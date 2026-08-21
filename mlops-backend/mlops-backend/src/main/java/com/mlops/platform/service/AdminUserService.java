package com.mlops.platform.service;

import com.mlops.platform.dto.UpdateUserRoleRequest;
import com.mlops.platform.dto.UpdateUserStatusRequest;
import com.mlops.platform.dto.SearchPageResponse;
import com.mlops.platform.dto.UserResponse;
import com.mlops.platform.entity.AccountStatus;
import com.mlops.platform.entity.Role;
import com.mlops.platform.entity.RoleType;
import com.mlops.platform.entity.User;
import com.mlops.platform.repository.RoleRepository;
import com.mlops.platform.repository.UserRepository;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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

    /**
     * Recherche dynamique et intelligente des utilisateurs.
     *
     * La pertinence est calculée côté base :
     * - exact sur email (100) puis préfixe email (80) puis contenu email (60)
     * - prénom/contient (40) puis nom/contient (30)
     *
     * @param q      texte libre (email / prénom / nom), tokens AND
     * @param status filtre ACTIVE | BLOCKED
     * @param role   filtre ADMIN | USER
     * @param page   page 0-based
     * @param size   taille de page (1..50)
     */
    @Transactional(readOnly = true, timeout = 15)
    public SearchPageResponse<UserResponse> searchUsers(
            String q,
            String status,
            String role,
            int page,
            int size
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 50);

        Specification<User> spec = userSearchSpecification(q, status, role);

        Page<User> result = userRepository.findAll(
                spec,
                PageRequest.of(safePage, safeSize)
        );

        List<UserResponse> items = result
                .getContent()
                .stream()
                .map(this::mapToUserResponse)
                .toList();

        return new SearchPageResponse<>(
                items,
                result.getTotalElements(),
                safePage,
                safeSize
        );
    }

    private Specification<User> userSearchSpecification(
            String q,
            String status,
            String role
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(
                        root.get("status"),
                        AccountStatus.valueOf(status.trim().toUpperCase())
                ));
            }

            if (role != null && !role.isBlank()) {
                predicates.add(cb.equal(
                        root.get("role").get("name"),
                        RoleType.valueOf(role.trim().toUpperCase())
                ));
            }

            String cleanQuery = q != null ? q.trim() : null;

            if (cleanQuery != null && !cleanQuery.isBlank()) {
                String[] tokens = cleanQuery.toLowerCase().split("\\s+");

                Expression<String> email = lowerExpr(cb, root.get("email"));
                Expression<String> firstName =
                        lowerExpr(cb, root.get("firstName"));
                Expression<String> lastName =
                        lowerExpr(cb, root.get("lastName"));

                List<Expression<Integer>> relevanceTerms = new ArrayList<>();

                for (String token : tokens) {
                    String any = "%" + token + "%";
                    String prefix = token + "%";

                    predicates.add(cb.or(
                            cb.like(email, any),
                            cb.like(firstName, any),
                            cb.like(lastName, any)
                    ));

                    relevanceTerms.add(cb.<Integer>selectCase()
                            .when(cb.equal(email, token), 100)
                            .when(cb.like(email, prefix), 80)
                            .when(cb.like(email, any), 60)
                            .when(cb.like(firstName, any), 40)
                            .when(cb.like(lastName, any), 30)
                            .otherwise(0));
                }

                Expression<Integer> relevance = relevanceTerms.get(0);
                for (int i = 1; i < relevanceTerms.size(); i++) {
                    relevance = cb.sum(relevance, relevanceTerms.get(i));
                }

                query.orderBy(
                        cb.desc(relevance),
                        cb.asc(root.get("email"))
                );
            } else {
                query.orderBy(cb.asc(root.get("email")));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Expression<String> lowerExpr(
            jakarta.persistence.criteria.CriteriaBuilder cb,
            Path<?> field
    ) {
        return cb.lower(cb.coalesce(field.as(String.class), ""));
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