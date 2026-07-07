package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSheetTemplateServiceTest {

    @Mock LogSheetTemplateRepository templateRepository;
    @Mock OperationalUnitScopeService unitScopeService;
    @Mock BusinessEventLogger businessEventLogger;

    @InjectMocks LogSheetTemplateService service;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void supervisorCannotEditTemplate() {
        authenticate(20L, "SUPERVISOR");
        LogSheetTemplate template = template(5L, 10L);
        when(templateRepository.findById(5L)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.update(5L, template))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void highUserCanEditTemplateInSupervisedUnit() {
        authenticate(30L, "HIGH_USER");
        LogSheetTemplate existing = template(5L, 10L);
        LogSheetTemplate form = template(5L, 10L);
        form.setName("Updated");
        when(templateRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(unitScopeService.isSupervisorOf(30L, 10L)).thenReturn(true);

        service.update(5L, form);

        verify(templateRepository).save(existing);
        assertThat(existing.getName()).isEqualTo("Updated");
    }

    @Test
    void supervisorSeesOnlySupervisedUnits() {
        authenticate(20L, "SUPERVISOR");
        when(unitScopeService.getSupervisorScopeUnitIds(20L)).thenReturn(Set.of(10L));

        assertThat(service.visibleUnitIds()).containsExactly(10L);
    }

    @Test
    void adminSeesAllUnits() {
        authenticate(1L, "ADMIN");

        assertThat(service.visibleUnitIds()).isNull();
    }

    private static LogSheetTemplate template(Long id, Long unitId) {
        LogSheetTemplate t = new LogSheetTemplate();
        t.setId(id);
        t.setName("Round check");
        t.setOperationalUnitId(unitId);
        t.setScopeType("location");
        t.setScopeId(1L);
        t.setClassId(2L);
        return t;
    }

    private static void authenticate(Long userId, String role) {
        User user = new User();
        user.setId(userId);
        user.setUsername("tester");
        user.setActive(true);
        AppUserDetails principal = new AppUserDetails(user, Set.of(role), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
