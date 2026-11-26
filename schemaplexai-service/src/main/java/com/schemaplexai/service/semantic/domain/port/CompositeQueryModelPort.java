package com.schemaplexai.service.semantic.domain.port;

import java.util.List;

/** 只向模型提供问题文本，候选不包含 SQL 或权限信息。 */
public interface CompositeQueryModelPort {
    Intent interpret(String tenantId, String question, String context);

    record Intent(String presentation, List<Part> parts) {}

    record Part(String question, String title) {}
}
