package com.hnp.backendofflinefirst.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class LogSheetTemplateAssetId implements Serializable {
    private Long templateId;
    private Long assetId;
}
