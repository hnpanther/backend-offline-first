package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferenceLabelServiceTest {

    @Mock LocationRepository locationRepository;
    @Mock PlantSystemRepository plantSystemRepository;
    @Mock MainFunctionRepository mainFunctionRepository;
    @Mock SubFunctionRepository subFunctionRepository;
    @Mock OperationalUnitRepository operationalUnitRepository;
    @Mock AssetClassRepository assetClassRepository;
    @Mock UserRepository userRepository;

    @InjectMocks ReferenceLabelService labels;

    @Test
    void userDisplayNameReturnsDashForNullId() {
        assertThat(labels.userDisplayName((Long) null)).isEqualTo("—");
    }

    @Test
    void userDisplayNameShowsFullNameAndUsername() {
        User u = new User();
        u.setId(1L);
        u.setUsername("admin");
        u.setFullName("مدیر سیستم");
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));

        assertThat(labels.userDisplayName(1L)).isEqualTo("مدیر سیستم (admin)");
    }

    @Test
    void userDisplayNameShowsUsernameOnlyWhenNoFullName() {
        User u = new User();
        u.setId(2L);
        u.setUsername("operator1");
        when(userRepository.findById(2L)).thenReturn(Optional.of(u));

        assertThat(labels.userDisplayName(2L)).isEqualTo("operator1");
    }

    @Test
    void userDisplayNameForMissingUserDoesNotThrow() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(labels.userDisplayName(99L)).isEqualTo("کاربر #99");
    }

    @Test
    void scopeDisplayLabelShowsTypeAndCode() {
        Location loc = new Location();
        loc.setId(5L);
        loc.setCode("LOC-A");
        when(locationRepository.findById(5L)).thenReturn(Optional.of(loc));

        assertThat(labels.scopeDisplayLabel("location", 5L)).isEqualTo("مکان: LOC-A");
    }

    @Test
    void scopeDisplayLabelForSystem() {
        PlantSystem ps = new PlantSystem();
        ps.setId(3L);
        ps.setCode("SYS-01");
        when(plantSystemRepository.findById(3L)).thenReturn(Optional.of(ps));

        assertThat(labels.scopeDisplayLabel("system", 3L)).isEqualTo("سیستم: SYS-01");
    }

    @Test
    void formatScopeSummaryParsesStoredValue() {
        Location loc = new Location();
        loc.setId(7L);
        loc.setCode("B-12");
        when(locationRepository.findById(7L)).thenReturn(Optional.of(loc));

        assertThat(labels.formatScopeSummary("location:7")).isEqualTo("مکان: B-12");
    }

    @Test
    void formatScopeSummaryReturnsDashForBlank() {
        assertThat(labels.formatScopeSummary(null)).isEqualTo("—");
        assertThat(labels.formatScopeSummary("")).isEqualTo("—");
    }

    @Test
    void formatScopeSummaryReturnsRawWhenNoColon() {
        assertThat(labels.formatScopeSummary("invalid")).isEqualTo("invalid");
    }
}
