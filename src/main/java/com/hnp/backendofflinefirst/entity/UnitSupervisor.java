package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "unit_supervisors")
@IdClass(UnitUserId.class)
@Data
public class UnitSupervisor {
    @Id
    @Column(name = "unit_id")
    private Long unitId;

    @Id
    @Column(name = "user_id")
    private Long userId;
}
