package com.schemaplexai.service.semantic.application.command;

/** 创建语义模型命令。 */
public record CreateSemanticModelCommand(String name, String domain, String description, String scopeId) {
    public CreateSemanticModelCommand(String name, String domain, String description) {
        this(name, domain, description, null);
    }
}
