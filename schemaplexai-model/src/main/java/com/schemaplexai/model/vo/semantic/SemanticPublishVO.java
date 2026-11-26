package com.schemaplexai.model.vo.semantic;

/** 发布结果和活动版本切换状态。 */
public record SemanticPublishVO(
        String modelId,
        SemanticVersionVO version,
        boolean activated,
        Readiness readiness) {

    public record Readiness(boolean contextReady, boolean validationReady, boolean queryReady) {
    }
}
