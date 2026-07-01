package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.io.Serializable;

@Data
public class UnitUserId implements Serializable {
    private String unitId;
    private String userId;
}
