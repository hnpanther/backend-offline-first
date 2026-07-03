package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "app_settings")
@Data
public class AppSetting {
    @Id
    @Column(name = "setting_key")
    private String settingKey;

    private String value;
    private Long updatedAt;
}
