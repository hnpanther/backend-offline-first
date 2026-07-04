package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.UnitOperatorOption;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.service.OperationalUnitScopeService;
import com.hnp.backendofflinefirst.service.OperationalUnitService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/operational-units")
@RequiredArgsConstructor
public class OperationalUnitController {

    private final OperationalUnitService operationalUnitService;
    private final OperationalUnitScopeService scopeService;
    private final UserRepository userRepository;

    @GetMapping("/{unitId}/operators")
    @PreAuthorize("hasAuthority('GET:/api/operational-units/{unitId}/operators')")
    public List<UnitOperatorOption> operators(@PathVariable Long unitId) {
        Long userId = SecurityUtils.currentUserId();
        if (!scopeService.isSupervisorOf(userId, unitId)) {
            throw new AccessDeniedException("You are not the supervisor of this unit.");
        }
        return operationalUnitService.getOperatorIds(unitId).stream()
                .map(id -> userRepository.findById(id)
                        .map(u -> new UnitOperatorOption(u.getId(), displayName(u)))
                        .orElse(new UnitOperatorOption(id, String.valueOf(id))))
                .toList();
    }

    private static String displayName(User user) {
        String name = user.getFullName();
        if (name != null && !name.isBlank()) return name;
        return user.getUsername();
    }
}
