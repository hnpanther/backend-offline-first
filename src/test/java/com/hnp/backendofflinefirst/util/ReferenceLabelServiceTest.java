package com.hnp.backendofflinefirst.util;

import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
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
        loc.setName("سالن A");
        when(locationRepository.findById(5L)).thenReturn(Optional.of(loc));

        assertThat(labels.scopeDisplayLabel("location", 5L)).isEqualTo("مکان: LOC-A - سالن A");
    }

    @Test
    void scopeDisplayLabelForSystem() {
        PlantSystem ps = new PlantSystem();
        ps.setId(3L);
        ps.setCode("SYS-01");
        ps.setName("سیستم پمپاژ");
        when(plantSystemRepository.findById(3L)).thenReturn(Optional.of(ps));

        assertThat(labels.scopeDisplayLabel("system", 3L)).isEqualTo("سیستم: SYS-01 - سیستم پمپاژ");
    }

    @Test
    void formatScopeSummaryParsesStoredValue() {
        Location loc = new Location();
        loc.setId(7L);
        loc.setCode("B-12");
        loc.setName("انبار");
        when(locationRepository.findById(7L)).thenReturn(Optional.of(loc));

        assertThat(labels.formatScopeSummary("location:7")).isEqualTo("مکان: B-12 - انبار");
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

    @Test
    void parentLabelForMainFunctionShowsParentType() {
        PlantSystem sys = new PlantSystem();
        sys.setId(3L);
        sys.setCode("SYS-01");
        sys.setName("برق");
        when(plantSystemRepository.findById(3L)).thenReturn(Optional.of(sys));

        MainFunction mf = new MainFunction();
        mf.setSystemId(3L);

        assertThat(labels.parentLabelForMainFunction(mf)).isEqualTo("سیستم: SYS-01 - برق");
    }

    @Test
    void parentLabelForSubFunctionShowsNestedParentType() {
        SubFunction parent = new SubFunction();
        parent.setId(8L);
        parent.setCode("SF-P");
        parent.setName("پمپ‌ها");
        when(subFunctionRepository.findById(8L)).thenReturn(Optional.of(parent));

        SubFunction sf = new SubFunction();
        sf.setParentId(8L);

        assertThat(labels.parentLabelForSubFunction(sf)).isEqualTo("تابع فرعی: SF-P - پمپ‌ها");
    }

    @Test
    void parentLabelForPlantSystemParentShowsSystemType() {
        PlantSystem parent = new PlantSystem();
        parent.setId(2L);
        parent.setCode("SYS-P");
        parent.setName("برق اصلی");
        when(plantSystemRepository.findById(2L)).thenReturn(Optional.of(parent));

        assertThat(labels.parentLabelForPlantSystemParent(2L)).isEqualTo("سیستم: SYS-P - برق اصلی");
    }

    @Test
    void parentLabelForPlantSystemParentReturnsDashForNull() {
        assertThat(labels.parentLabelForPlantSystemParent(null)).isEqualTo("—");
    }

    @Test
    void parentLabelForMainFunctionShowsLocationWhenDirectUnderLocation() {
        Location loc = new Location();
        loc.setId(4L);
        loc.setCode("LOC-01");
        loc.setName("سالن");
        when(locationRepository.findById(4L)).thenReturn(Optional.of(loc));

        MainFunction mf = new MainFunction();
        mf.setLocationId(4L);

        assertThat(labels.parentLabelForMainFunction(mf)).isEqualTo("مکان: LOC-01 - سالن");
    }

    @Test
    void parentLabelForMainFunctionShowsParentMainFunction() {
        MainFunction parent = new MainFunction();
        parent.setId(6L);
        parent.setCode("MF-P");
        parent.setName("والد");
        when(mainFunctionRepository.findById(6L)).thenReturn(Optional.of(parent));

        MainFunction mf = new MainFunction();
        mf.setParentId(6L);

        assertThat(labels.parentLabelForMainFunction(mf)).isEqualTo("تابع اصلی: MF-P - والد");
    }

    @Test
    void parentLabelForSubFunctionShowsMainFunctionParent() {
        MainFunction mf = new MainFunction();
        mf.setId(3L);
        mf.setCode("MF-01");
        mf.setName("برق");
        when(mainFunctionRepository.findById(3L)).thenReturn(Optional.of(mf));

        SubFunction sf = new SubFunction();
        sf.setMainFunctionId(3L);

        assertThat(labels.parentLabelForSubFunction(sf)).isEqualTo("تابع اصلی: MF-01 - برق");
    }

    @Test
    void parentLabelForSubFunctionReturnsDashWhenOrphan() {
        assertThat(labels.parentLabelForSubFunction(new SubFunction())).isEqualTo("—");
    }
}
