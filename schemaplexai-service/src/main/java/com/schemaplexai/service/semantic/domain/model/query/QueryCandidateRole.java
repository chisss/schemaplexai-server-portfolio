package com.schemaplexai.service.semantic.domain.model.query;

/** 本体节点在查询解释中的受控角色。 */
public enum QueryCandidateRole {
    METRIC,
    DIMENSION,
    FILTER,
    TIME,
    UNKNOWN
}
