package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.KnowledgeDocument;
import com.schemaplexai.model.vo.knowledge.KnowledgeDocumentVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 知识文档转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface KnowledgeDocumentConverter {

    @Mapping(target = "createdByName", ignore = true)
    KnowledgeDocumentVO toVO(KnowledgeDocument entity);

    List<KnowledgeDocumentVO> toVOList(List<KnowledgeDocument> entities);
}
