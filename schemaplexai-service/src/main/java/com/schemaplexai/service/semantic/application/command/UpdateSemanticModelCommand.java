package com.schemaplexai.service.semantic.application.command;

/** 更新语义模型命令。 */
public record UpdateSemanticModelCommand(
        String name,
        String domain,
        String description,
        long expectedRevision,
        String scopeId) {
    public UpdateSemanticModelCommand(String name, String domain, String description, long expectedRevision) {
        this(name, domain, description, expectedRevision, null);
    }
}
