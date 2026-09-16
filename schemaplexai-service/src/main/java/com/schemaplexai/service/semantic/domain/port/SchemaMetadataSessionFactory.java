package com.schemaplexai.service.semantic.domain.port;

/** 为租户已授权数据源创建短生命周期元数据会话。 */
public interface SchemaMetadataSessionFactory {

    SchemaMetadataSession open(String tenantId, String sourceId);
}
