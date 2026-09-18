package com.schemaplexai.model.vo.semantic;

/** 节点编辑后的新版本 revision 和节点快照。 */
public record SemanticNodeUpdateVO(
        String versionId,
        long revision,
        SemanticGraphVO.Node node) {
}
