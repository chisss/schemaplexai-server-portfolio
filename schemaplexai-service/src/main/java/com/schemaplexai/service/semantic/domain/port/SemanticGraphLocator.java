package com.schemaplexai.service.semantic.domain.port;

/** 将领域版本标识解析为服务端拥有的图地址。 */
public interface SemanticGraphLocator {

    String assertedGraph(String tenantId, String modelId, long version);
}
