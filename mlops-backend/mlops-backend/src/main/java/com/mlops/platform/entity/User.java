package com.mlops.platform.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false, unique = true)
    private String email;

    @JsonIgnore
    @Column(nullable = false)
    private String password;

    private String phone;

    private String nationality;

    @Column(length = 1000)
    private String bio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(nullable = false)
    private boolean emailVerified;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * Rôle de l'utilisateur.
     *
     * Important :
     * On ne met PAS CascadeType.REMOVE ici.
     * Si on supprime un user, on ne doit jamais supprimer le rôle ADMIN/USER.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    private Role role;

    /**
     * Projets créés par cet utilisateur.
     *
     * Objectif :
     * Supprimer un user
     * -> supprimer automatiquement ses projets
     * -> puis les expériences, métriques et fichiers seront supprimés via les cascades des autres entities.
     */
    @JsonIgnore
    @OneToMany(
            mappedBy = "owner",
            cascade = CascadeType.REMOVE,
            orphanRemoval = true
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private List<MlProject> projects = new ArrayList<>();

    /**
     * Tokens de vérification email liés au user.
     *
     * Objectif :
     * Supprimer un user
     * -> supprimer automatiquement ses tokens de vérification email.
     */
    @JsonIgnore
    @OneToMany(
            mappedBy = "user",
            cascade = CascadeType.REMOVE,
            orphanRemoval = true
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private List<EmailVerificationToken> emailVerificationTokens = new ArrayList<>();

    /**
     * Tokens de reset password liés au user.
     *
     * Objectif :
     * Supprimer un user
     * -> supprimer automatiquement ses tokens de reset password.
     */
    @JsonIgnore
    @OneToMany(
            mappedBy = "user",
            cascade = CascadeType.REMOVE,
            orphanRemoval = true
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private List<PasswordResetToken> passwordResetTokens = new ArrayList<>();

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();

        if (this.status == null) {
            this.status = AccountStatus.ACTIVE;
        }

        normalizeFields();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        normalizeFields();
    }

    private void normalizeFields() {
        if (this.email != null) {
            this.email = this.email.trim().toLowerCase();
        }

        if (this.firstName != null) {
            this.firstName = this.firstName.trim();
        }

        if (this.lastName != null) {
            this.lastName = this.lastName.trim();
        }

        if (this.phone != null) {
            this.phone = this.phone.trim();
        }

        if (this.nationality != null) {
            this.nationality = this.nationality.trim();
        }

        if (this.bio != null) {
            this.bio = this.bio.trim();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
    
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
    
    public String getNationality() {
        return nationality;
    }

    public void setNationality(String nationality) {
        this.nationality = nationality;
    }
    
    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }
    
    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }
    
    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }
    
    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public List<MlProject> getProjects() {
        return projects;
    }

    public void setProjects(List<MlProject> projects) {
        this.projects = projects;
    }

    public List<EmailVerificationToken> getEmailVerificationTokens() {
        return emailVerificationTokens;
    }

    public void setEmailVerificationTokens(List<EmailVerificationToken> emailVerificationTokens) {
        this.emailVerificationTokens = emailVerificationTokens;
    }

    public List<PasswordResetToken> getPasswordResetTokens() {
        return passwordResetTokens;
    }

    public void setPasswordResetTokens(List<PasswordResetToken> passwordResetTokens) {
        this.passwordResetTokens = passwordResetTokens;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}