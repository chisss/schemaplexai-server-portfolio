package com.schemaplexai.service.desktop;

import com.schemaplexai.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopScheduleRuleTest {

    @Test
    void draftProducesFiveWeekdayRunsInRequestedZone() {
        DesktopScheduleRule rule = DesktopScheduleRule.draft(
                "每个工作日 09:00 汇总日报", "Asia/Shanghai");

        List<OffsetDateTime> nextRuns = rule.preview(Instant.parse("2026-11-20T00:00:00Z"));

        assertEquals(5, nextRuns.size());
        assertEquals(9, nextRuns.getFirst().getHour());
        assertEquals(ZoneOffset.ofHours(8), nextRuns.getFirst().getOffset());
        assertTrue(nextRuns.stream().allMatch(run -> switch (run.getDayOfWeek()) {
            case MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY -> true;
            default -> false;
        }));
    }

    @Test
    void daylightSavingGapSkipsMissingLocalCronTime() {
        DesktopScheduleRule rule = new DesktopScheduleRule(
                "cron", "0 30 2 * * *", "America/New_York", null);

        List<OffsetDateTime> nextRuns = rule.preview(Instant.parse("2026-03-08T05:00:00Z"));

        assertEquals(5, nextRuns.size());
        assertEquals(9, nextRuns.getFirst().getDayOfMonth());
        assertEquals(2, nextRuns.getFirst().getHour());
    }

    @Test
    void unsupportedNaturalLanguageDoesNotInventRule() {
        assertNull(DesktopScheduleRule.draft("下周一上午提醒我", "Asia/Shanghai"));
    }

    @Test
    void invalidTimezoneIsRejected() {
        DesktopScheduleRule rule = new DesktopScheduleRule("cron", "0 0 9 * * *", "Mars/Olympus", null);

        assertThrows(BusinessException.class, () -> rule.preview(Instant.now()));
    }
}
