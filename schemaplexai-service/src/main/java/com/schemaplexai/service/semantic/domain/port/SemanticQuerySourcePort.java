package com.schemaplexai.service.semantic.domain.port;

/** 查询编译所需的数据源元信息端口；实现必须执行当前租户归属校验。 */
public interface SemanticQuerySourcePort {

    String databaseType(String sourceId);
}
