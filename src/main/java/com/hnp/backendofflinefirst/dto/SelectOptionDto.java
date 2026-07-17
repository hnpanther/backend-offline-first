package com.hnp.backendofflinefirst.dto;

/** Lightweight option for searchable remote selects in the admin UI. */
public record SelectOptionDto(String value, String label, String group) {

    public static SelectOptionDto of(String value, String label) {
        return new SelectOptionDto(value, label, null);
    }

    public static SelectOptionDto of(String value, String label, String group) {
        return new SelectOptionDto(value, label, group);
    }
}
