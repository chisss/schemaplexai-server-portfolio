package com.schemaplexai.service.desktop;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public record DesktopScheduleRule(String kind, String expression, String timezone, OffsetDateTime runAt) {
    private static final Pattern DAILY = Pattern.compile("^(每天|每个工作日|工作日)\\s*(\\d{1,2})(?:[:：](\\d{2})|点(?:(\\d{1,2})分)?)");

    public static DesktopScheduleRule draft(String text, String zone) {
        var match = DAILY.matcher(text.strip());
        if (!match.find()) return null;
        int hour = Integer.parseInt(match.group(2));
        String minute = match.group(3) == null ? match.group(4) : match.group(3);
        return new DesktopScheduleRule("cron", "0 " + (minute == null ? "0" : minute) + " " + hour
                + " * * " + ("每天".equals(match.group(1)) ? "*" : "MON-FRI"), zone, null);
    }

    public List<OffsetDateTime> preview(Instant after) {
        List<OffsetDateTime> upcoming = new ArrayList<>(5);
        Instant cursor = after;
        for (int index = 0; index < 5; index++) {
            OffsetDateTime next = next(cursor);
            if (next == null) break;
            upcoming.add(next);
            cursor = next.toInstant();
        }
        return upcoming;
    }

    public OffsetDateTime next(Instant after) {
        if (timezone == null || !ZoneId.getAvailableZoneIds().contains(timezone)) {
            throw invalid("请输入有效的 IANA 时区");
        }
        ZoneId zone = ZoneId.of(timezone);
        if ("once".equals(kind) && runAt != null && expression == null) {
            return runAt.toInstant().isAfter(after) ? runAt.atZoneSameInstant(zone).toOffsetDateTime() : null;
        }
        if (!"cron".equals(kind) || expression == null || runAt != null) {
            throw invalid("请选择单次执行时间或六段 Cron 规则");
        }
        try {
            var cron = CronExpression.parse(expression);
            ZonedDateTime next = cron.next(after.atZone(zone));
            if (next == null) return null;
            var following = cron.next(next);
            if (following != null && following.toInstant().isBefore(next.toInstant().plusSeconds(60))) {
                throw invalid("触发间隔至少为一分钟");
            }
            return next.toOffsetDateTime();
        } catch (DateTimeException | IllegalArgumentException ex) {
            throw invalid("Cron 规则无效");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ResultCode.BAD_REQUEST, message);
    }
}
