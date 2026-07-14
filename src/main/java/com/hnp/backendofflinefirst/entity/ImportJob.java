package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.ImportJobStatus;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "import_jobs")
@Data
public class ImportJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_uuid", nullable = false, unique = true)
    private String jobUuid;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportJobStatus status;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "processed_rows", nullable = false)
    private int processedRows;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    @Column(name = "submitted_by_user_id", nullable = false)
    private Long submittedByUserId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "started_at")
    private Long startedAt;

    @Column(name = "completed_at")
    private Long completedAt;

    public int progressPercent() {
        if (totalRows <= 0) {
            return status == ImportJobStatus.COMPLETED
                    || status == ImportJobStatus.FAILED
                    || status == ImportJobStatus.CANCELLED ? 100 : 0;
        }
        return Math.min(100, (processedRows * 100) / totalRows);
    }
}
