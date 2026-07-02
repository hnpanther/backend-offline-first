package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "unit_supervisors")
@IdClass(UnitUserId.class)
@Data
public class UnitSupervisor {
    @Id
    private Long unitId;

    @Id
    private Long userId;
}
