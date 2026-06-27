package com.mlops.platform.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity
@Table(name = "uploaded_project_files")
public class UploadedProjectFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String originalFileName;

    @Column(nullable = false)
    private String storedFileName;

    @Column(nullable = false, length = 1000)
    private String filePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UploadedFileType fileType;

    /**
     * Type de données métier :
     * TABULAR / IMAGE / TEXT
     */
    @Column(name = "data_type", length = 50)
    private String dataType;

    /**
     * Extension normalisée :
     * csv, tsv, jpg, png, txt, jsonl...
     */
    @Column(name = "file_extension", length = 50)
    private String fileExtension;

    private String contentType;

    private Long sizeBytes;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    /**
     * Le fichier appartient au projet.
     * Pas de cascade ici : c'est le projet qui supprime ses fichiers,
     * pas le fichier qui supprime le projet.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MlProject project;

    @PrePersist
    public void onCreate() {
        if (this.uploadedAt == null) {
            this.uploadedAt = LocalDateTime.now();
        }

        normalizeFields();
    }

    @PreUpdate
    public void onUpdate() {
        normalizeFields();
    }

    private void normalizeFields() {
        if (this.originalFileName != null) {
            this.originalFileName = this.originalFileName.trim();
        }

        if (this.storedFileName != null) {
            this.storedFileName = this.storedFileName.trim();
        }

        if (this.filePath != null) {
            this.filePath = this.filePath.trim();
        }

        if (this.contentType != null) {
            this.contentType = this.contentType.trim();
        }

        if (this.dataType != null) {
            this.dataType = this.dataType.trim().toUpperCase();

            if (!this.dataType.equals("TABULAR")
                    && !this.dataType.equals("IMAGE")
                    && !this.dataType.equals("TEXT")) {
                this.dataType = null;
            }
        }

        if (this.fileExtension != null) {
            this.fileExtension = this.fileExtension.trim().toLowerCase();

            if (this.fileExtension.startsWith(".")) {
                this.fileExtension = this.fileExtension.substring(1);
            }

            if (this.fileExtension.isBlank()) {
                this.fileExtension = null;
            }
        }
    }

    public Long getId() {
        return id;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public String getStoredFileName() {
        return storedFileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public UploadedFileType getFileType() {
        return fileType;
    }

    public String getDataType() {
        return dataType;
    }

    public String getFileExtension() {
        return fileExtension;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public MlProject getProject() {
        return project;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = cleanText(originalFileName);
    }

    public void setStoredFileName(String storedFileName) {
        this.storedFileName = cleanText(storedFileName);
    }

    public void setFilePath(String filePath) {
        this.filePath = cleanText(filePath);
    }

    public void setFileType(UploadedFileType fileType) {
        this.fileType = fileType;
    }

    public void setDataType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            this.dataType = null;
        } else {
            this.dataType = dataType.trim().toUpperCase();
        }
    }

    public void setFileExtension(String fileExtension) {
        if (fileExtension == null || fileExtension.isBlank()) {
            this.fileExtension = null;
            return;
        }

        String extension = fileExtension.trim().toLowerCase();

        if (extension.startsWith(".")) {
            extension = extension.substring(1);
        }

        this.fileExtension = extension.isBlank() ? null : extension;
    }

    public void setContentType(String contentType) {
        this.contentType = cleanText(contentType);
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public void setProject(MlProject project) {
        this.project = project;
    }

    private String cleanText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}