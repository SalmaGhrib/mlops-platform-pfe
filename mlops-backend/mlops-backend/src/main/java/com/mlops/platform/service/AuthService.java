package com.mlops.platform.service;

import com.mlops.platform.dto.AuthResponse;
import com.mlops.platform.dto.ForgotPasswordRequest;
import com.mlops.platform.dto.LoginRequest;
import com.mlops.platform.dto.LoginResponse;
import com.mlops.platform.dto.RegisterRequest;
import com.mlops.platform.dto.ResetPasswordRequest;
import com.mlops.platform.dto.UserResponse;

import com.mlops.platform.entity.AccountStatus;
import com.mlops.platform.entity.EmailVerificationToken;
import com.mlops.platform.entity.PasswordResetToken;
import com.mlops.platform.entity.Role;
import com.mlops.platform.entity.RoleType;
import com.mlops.platform.entity.User;

import com.mlops.platform.repository.EmailVerificationTokenRepository;
import com.mlops.platform.repository.PasswordResetTokenRepository;
import com.mlops.platform.repository.RoleRepository;
import com.mlops.platform.repository.UserRepository;

import com.mlops.platform.security.JwtService;

import jakarta.transaction.Transactional;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * En local :
     * http://localhost:4200
     *
     * Sur AKS :
     * http://128.251.112.197
     *
     * La valeur AKS est injectée par la variable :
     * APP_FRONTEND_BASE_URL
     */
    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;

    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already exists");
        }

        Role defaultRole = roleRepository.findByName(RoleType.USER)
                .orElseGet(() -> {
                    Role role = new Role();
                    role.setName(RoleType.USER);
                    return roleRepository.save(role);
                });

        User user = new User();
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setPhone(request.getPhone());
        user.setNationality(request.getNationality());
        user.setBio(request.getBio());
        user.setStatus(AccountStatus.ACTIVE);
        user.setEmailVerified(false);
        user.setRole(defaultRole);

        User savedUser = userRepository.save(user);

        try {
            sendVerificationEmail(savedUser);
        } catch (Exception e) {
            System.err.println(
                    "Failed to send verification email to "
                            + savedUser.getEmail()
                            + ": "
                            + e.getMessage()
            );
        }

        return new AuthResponse(
                "User registered successfully. Please check your email to verify your account.",
                savedUser.getEmail(),
                savedUser.getRole().getName().name()
        );
    }

    public LoginResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() ->
                        new RuntimeException("Invalid email or password")
                );

        if (!passwordEncoder.matches(
                request.getPassword(),
                user.getPassword()
        )) {
            throw new RuntimeException("Invalid email or password");
        }

        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new RuntimeException("Account is not active");
        }

        if (!user.isEmailVerified()) {
            throw new RuntimeException(
                    "Email is not verified. Please check your email."
            );
        }

        String token = jwtService.generateToken(
                user,
                request.isRememberMe()
        );

        return new LoginResponse(
                token,
                "Bearer",
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getRole().getName().name()
        );
    }

    public UserResponse getCurrentUser(String authHeader) {

        User user = getUserFromAuthorizationHeader(authHeader);

        return mapToUserResponse(user);
    }

    @Transactional
    public String verifyEmail(String token) {

        EmailVerificationToken verificationToken =
                emailVerificationTokenRepository.findByToken(token)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Invalid verification token"
                                )
                        );

        if (verificationToken
                .getExpiryDate()
                .isBefore(LocalDateTime.now())) {

            emailVerificationTokenRepository.delete(verificationToken);

            throw new RuntimeException(
                    "Verification token has expired"
            );
        }

        User user = verificationToken.getUser();
        user.setEmailVerified(true);

        userRepository.save(user);
        emailVerificationTokenRepository.deleteByUser(user);

        return "Email verified successfully. You can now login.";
    }

    @Transactional
    protected void sendVerificationEmail(User user) {

        emailVerificationTokenRepository.deleteByUser(user);

        String token = UUID.randomUUID().toString();

        EmailVerificationToken verificationToken =
                new EmailVerificationToken(
                        token,
                        user,
                        LocalDateTime.now().plusHours(24)
                );

        emailVerificationTokenRepository.save(verificationToken);

        String verificationLink = buildFrontendUrl(
                "/verify-email",
                token
        );

        String subject =
                "Validation de votre compte MLOps Platform";

        String htmlContent =
                "<!DOCTYPE html>" +
                "<html>" +
                "<head>" +
                "  <meta charset='UTF-8'>" +
                "</head>" +
                "<body style='margin:0; padding:0; background-color:#f4f6f8; font-family:Arial, sans-serif;'>" +
                "  <div style='max-width:600px; margin:40px auto; background:white; border-radius:12px; padding:30px; box-shadow:0 4px 12px rgba(0,0,0,0.08);'>" +
                "    <h2 style='color:#1f2937; text-align:center;'>Bienvenue sur MLOps Platform</h2>" +
                "    <p style='font-size:15px; color:#374151;'>Bonjour " +
                escapeHtml(user.getFirstName()) +
                ",</p>" +
                "    <p style='font-size:15px; color:#374151; line-height:1.6;'>" +
                "      Merci de vous être inscrit(e) sur notre plateforme MLOps. " +
                "      Pour activer votre compte, veuillez valider votre adresse email en cliquant sur le bouton ci-dessous." +
                "    </p>" +
                "    <div style='text-align:center; margin:30px 0;'>" +
                "      <a href='" + verificationLink + "' " +
                "         style='background-color:#2563eb; color:white; padding:14px 24px; text-decoration:none; border-radius:8px; font-weight:bold; display:inline-block;'>" +
                "        Valider mon adresse email" +
                "      </a>" +
                "    </div>" +
                "    <p style='font-size:13px; color:#6b7280; line-height:1.5;'>" +
                "      Ce lien est valable pendant 24 heures. " +
                "      Si vous n’avez pas créé de compte, vous pouvez ignorer cet email." +
                "    </p>" +
                "    <p style='font-size:12px; color:#9ca3af; word-break:break-all;'>" +
                "      Si le bouton ne fonctionne pas, copiez ce lien dans votre navigateur :<br>" +
                "      " + verificationLink +
                "    </p>" +
                "    <hr style='border:none; border-top:1px solid #e5e7eb; margin:25px 0;'>" +
                "    <p style='font-size:12px; color:#9ca3af; text-align:center;'>" +
                "      MLOps Platform Team" +
                "    </p>" +
                "  </div>" +
                "</body>" +
                "</html>";

        emailService.sendHtmlEmail(
                user.getEmail(),
                subject,
                htmlContent
        );
    }

    @Transactional
    public String forgotPassword(
            ForgotPasswordRequest request
    ) {

        User user = userRepository
                .findByEmail(request.getEmail())
                .orElse(null);

        /*
         * On retourne le même message même si l'adresse
         * n'existe pas, pour éviter de révéler les comptes
         * présents dans la base.
         */
        if (user == null) {
            return "If this email exists, a reset password link has been sent.";
        }

        passwordResetTokenRepository.deleteByUser(user);

        String token = UUID.randomUUID().toString();

        PasswordResetToken resetToken =
                new PasswordResetToken(
                        token,
                        user,
                        LocalDateTime.now().plusMinutes(30)
                );

        passwordResetTokenRepository.save(resetToken);

        String resetLink = buildFrontendUrl(
                "/reset-password",
                token
        );

        String subject =
                "Réinitialisation de votre mot de passe - MLOps Platform";

        String htmlContent =
                "<!DOCTYPE html>" +
                "<html>" +
                "<head>" +
                "  <meta charset='UTF-8'>" +
                "</head>" +
                "<body style='margin:0; padding:0; background-color:#f4f6f8; font-family:Arial, sans-serif;'>" +
                "  <div style='max-width:600px; margin:40px auto; background:white; border-radius:12px; padding:30px; box-shadow:0 4px 12px rgba(0,0,0,0.08);'>" +
                "    <h2 style='color:#1f2937; text-align:center;'>Réinitialisation du mot de passe</h2>" +
                "    <p style='font-size:15px; color:#374151;'>Bonjour " +
                escapeHtml(user.getFirstName()) +
                ",</p>" +
                "    <p style='font-size:15px; color:#374151; line-height:1.6;'>" +
                "      Vous avez demandé la réinitialisation de votre mot de passe. " +
                "      Cliquez sur le bouton ci-dessous pour choisir un nouveau mot de passe." +
                "    </p>" +
                "    <div style='text-align:center; margin:30px 0;'>" +
                "      <a href='" + resetLink + "' " +
                "         style='background-color:#2563eb; color:white; padding:14px 24px; text-decoration:none; border-radius:8px; font-weight:bold; display:inline-block;'>" +
                "        Réinitialiser mon mot de passe" +
                "      </a>" +
                "    </div>" +
                "    <p style='font-size:13px; color:#6b7280; line-height:1.5;'>" +
                "      Ce lien est valable pendant 30 minutes. " +
                "      Si vous n’avez pas demandé cette action, ignorez cet email." +
                "    </p>" +
                "    <p style='font-size:12px; color:#9ca3af; word-break:break-all;'>" +
                "      Si le bouton ne fonctionne pas, copiez ce lien dans votre navigateur :<br>" +
                "      " + resetLink +
                "    </p>" +
                "    <hr style='border:none; border-top:1px solid #e5e7eb; margin:25px 0;'>" +
                "    <p style='font-size:12px; color:#9ca3af; text-align:center;'>" +
                "      MLOps Platform Team" +
                "    </p>" +
                "  </div>" +
                "</body>" +
                "</html>";

        emailService.sendHtmlEmail(
                user.getEmail(),
                subject,
                htmlContent
        );

        return "If this email exists, a reset password link has been sent.";
    }

    @Transactional
    public String resetPassword(
            ResetPasswordRequest request
    ) {

        PasswordResetToken resetToken =
                passwordResetTokenRepository
                        .findByToken(request.getToken())
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Invalid reset password token"
                                )
                        );

        if (resetToken
                .getExpiryDate()
                .isBefore(LocalDateTime.now())) {

            passwordResetTokenRepository.delete(resetToken);

            throw new RuntimeException(
                    "Reset password token has expired"
            );
        }

        if (!request
                .getNewPassword()
                .equals(request.getConfirmPassword())) {

            throw new RuntimeException(
                    "New password and confirmation do not match"
            );
        }

        User user = resetToken.getUser();

        user.setPassword(
                passwordEncoder.encode(
                        request.getNewPassword()
                )
        );

        userRepository.save(user);
        passwordResetTokenRepository.deleteByUser(user);

        return "Password reset successfully. You can now login.";
    }

    /**
     * Produit par exemple :
     *
     * http://128.251.112.197/verify-email?token=...
     *
     * ou :
     *
     * http://128.251.112.197/reset-password?token=...
     */
    private String buildFrontendUrl(
            String path,
            String token
    ) {

        String normalizedBaseUrl =
                frontendBaseUrl == null
                        ? ""
                        : frontendBaseUrl.trim();

        while (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(
                    0,
                    normalizedBaseUrl.length() - 1
            );
        }

        if (normalizedBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "Frontend base URL is not configured"
            );
        }

        return UriComponentsBuilder
                .fromUriString(normalizedBaseUrl)
                .path(path)
                .queryParam("token", token)
                .build()
                .encode()
                .toUriString();
    }

    private User getUserFromAuthorizationHeader(
            String authHeader
    ) {

        if (authHeader == null
                || !authHeader.startsWith("Bearer ")) {

            throw new RuntimeException(
                    "Missing or invalid Authorization header"
            );
        }

        String token = authHeader.substring(7);

        if (!jwtService.isTokenValid(token)) {
            throw new RuntimeException(
                    "Invalid or expired token"
            );
        }

        String email = jwtService.extractEmail(token);

        return userRepository
                .findByEmail(email)
                .orElseThrow(() ->
                        new RuntimeException("User not found")
                );
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
                user.getCreatedAt() != null
                        ? user.getCreatedAt().toString()
                        : "-"
        );
    }

    /**
     * Protection minimale du prénom injecté dans le HTML.
     */
    private String escapeHtml(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}