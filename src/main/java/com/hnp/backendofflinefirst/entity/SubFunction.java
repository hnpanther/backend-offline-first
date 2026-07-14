package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "sub_functions")
@Data
public class SubFunction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private String tag;
    private Long parentId;
    private Long mainFunctionId;
    private Long systemId;
    private Long locationId;
    private Long createdAt;
    private Long updatedAt;
}
