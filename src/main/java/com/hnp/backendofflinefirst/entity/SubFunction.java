package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "sub_functions")
@Data
public class SubFunction {
    @Id
    private String id;
    private String code;
    private String name;
    private String tag;
    private String mainFunctionId;
    private String systemId;
    private String locationId;
    private Long createdAt;
    private Long updatedAt;
}
