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

    @Column(name = "value")
    private String value;
    @Column(name = "updated_at")
    private Long updatedAt;
}
