package com.hnp.backendofflinefirst.ui;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Persian labels for permission categories (keys remain English in the service layer). */
@Component("permissionCategories")
public class PermissionCategoryLabels {

    private static final Map<String, String> LABELS = Map.of(
            "general", "عمومی",
            "admin", "مدیریت سیستم",
            "organization", "سازمان",
            "master-data", "داده پایه",
            "operational", "عملیاتی",
            "reports", "گزارش‌ها",
            "api", "API موبایل",
            // Not an endpoint: what the holder may DO (see plant-wide, complete any sheet…).
            // These carry no method or path, which is why the row renders differently.
            "capability", "اختیارات ویژه",
            "other", "سایر"
    );

    public String label(String categoryKey) {
        if (categoryKey == null || categoryKey.isBlank()) {
            return LABELS.get("other");
        }
        return LABELS.getOrDefault(categoryKey, categoryKey);
    }
}
