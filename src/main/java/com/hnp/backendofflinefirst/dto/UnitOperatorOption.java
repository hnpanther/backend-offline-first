package com.hnp.backendofflinefirst.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Operator pick-list for supervisor assign / reassign on mobile. */
@Data
@AllArgsConstructor
public class UnitOperatorOption {
    private Long id;
    private String fullName;
}
