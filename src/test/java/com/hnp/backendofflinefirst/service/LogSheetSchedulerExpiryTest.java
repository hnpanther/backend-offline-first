package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue B-13: one sheet that cannot be expired must not undo the expiry of every other sheet of
 * the same tick.
 */
@ExtendWith(MockitoExtension.class)
class LogSheetSchedulerExpiryTest {

    @Mock LogSheetTemplateRepository templateRepository;
    @Mock LogSheetRepository logSheetRepository;
    @Mock LogSheetGenerationService generationService;
    @Mock LogSheetService logSheetService;
    @Mock BusinessEventLogger businessEventLogger;
    @InjectMocks LogSheetScheduler scheduler;

    private static LogSheet sheet(long id) {
        LogSheet s = new LogSheet();
        s.setId(id);
        s.setDueAt(1L);
        return s;
    }

    @Test
    void aSheetThatThrowsDoesNotStopTheOthers() {
        when(logSheetRepository.findByStatusInAndDueAtLessThanEqual(any(), anyLong()))
                .thenReturn(List.of(sheet(1), sheet(2), sheet(3)));
        when(logSheetService.tryExpireOverdue(eq(1L), anyLong())).thenReturn(true);
        when(logSheetService.tryExpireOverdue(eq(2L), anyLong()))
                .thenThrow(new IllegalStateException("broken row"));
        when(logSheetService.tryExpireOverdue(eq(3L), anyLong())).thenReturn(true);

        assertThatCode(scheduler::expireOverdueSheets).doesNotThrowAnyException();

        verify(logSheetService).tryExpireOverdue(eq(3L), anyLong());
        verify(businessEventLogger).schedulerRun("log-sheet-expire", 2);
    }

    /**
     * The catch alone is not the fix: with an outer transaction the inner {@code @Transactional}
     * joins it, and its exception marks the whole tick rollback-only even when caught here.
     */
    @Test
    void theTickHasNoTransactionOfItsOwn() throws Exception {
        assertThat(LogSheetScheduler.class.getMethod("expireOverdueSheets")
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(LogSheetScheduler.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(LogSheetService.class.getMethod("tryExpireOverdue", Long.class, long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
