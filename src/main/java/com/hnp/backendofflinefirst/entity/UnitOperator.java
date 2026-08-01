package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "unit_operators")
@IdClass(UnitUserId.class)
@Data
public class UnitOperator {
    @Id
    @Column(name = "unit_id")
    private Long unitId;

    @Id
    @Column(name = "user_id")
    private Long userId;
}
