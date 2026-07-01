package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.entity.User;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class UserPickerHelper {

    private UserPickerHelper() {}

    public static List<Map<String, String>> toPickerItems(List<User> users) {
        List<Map<String, String>> items = new ArrayList<>();
        for (User u : users) {
            String username = u.getUsername();
            String fullName = u.getFullName();
            String label = (fullName != null && !fullName.isBlank()) ? fullName : username;
            String search = username + " " + (fullName != null ? fullName : "");

            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", u.getId());
            item.put("label", label);
            item.put("username", username);
            item.put("search", search.trim());
            items.add(item);
        }
        return items;
    }

    public static String toCsv(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        return ids.stream().collect(Collectors.joining(","));
    }
}
