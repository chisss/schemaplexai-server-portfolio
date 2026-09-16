package com.schemaplexai.service.semantic.application.command;

/** 创建语义模型命令。 */
public record CreateSemanticModelCommand(String name, String domain, String description) {
}
