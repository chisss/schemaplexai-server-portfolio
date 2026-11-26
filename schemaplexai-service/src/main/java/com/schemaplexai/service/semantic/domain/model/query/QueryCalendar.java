package com.schemaplexai.service.semantic.domain.model.query;

import java.time.ZoneId;
import java.util.Set;

/** 已发布时间字段的业务时区与物理类型。 */
public record QueryCalendar(String timeZone, String physicalType) {

    public QueryCalendar {
        timeZone = ZoneId.of(timeZone).getId();
        if (!Set.of("date", "datetime", "timestamp without time zone", "timestamp with time zone").contains(physicalType)) {
            throw new IllegalArgumentException("不支持的时间映射类型");
        }
    }

    public boolean instant() {
        return "timestamp with time zone".equals(physicalType);
    }
}
