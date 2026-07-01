package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "unit_operators")
@IdClass(UnitUserId.class)
@Data
public class UnitOperator {
    @Id
    private String unitId;

    @Id
    private String userId;
}
