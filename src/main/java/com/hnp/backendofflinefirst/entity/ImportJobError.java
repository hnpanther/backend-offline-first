package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "import_job_errors")
@Data
public class ImportJobError {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "row_num", nullable = false)
    private int rowNum;

    @Column(name = "message_en", nullable = false, length = 1024)
    private String messageEn;
}
