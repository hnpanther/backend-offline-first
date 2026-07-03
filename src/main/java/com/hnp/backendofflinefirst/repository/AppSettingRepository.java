package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
