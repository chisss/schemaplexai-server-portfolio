package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.model.entity.SpecVersion;
import com.schemaplexai.model.vo.spec.SpecDocumentVO;
import com.schemaplexai.model.vo.spec.SpecVersionVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * Spec文档转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface SpecDocumentConverter {

    SpecDocumentVO toVO(SpecDocument document);

    List<SpecDocumentVO> toVOList(List<SpecDocument> documents);

    SpecVersionVO toVersionVO(SpecVersion version);

    List<SpecVersionVO> toVersionVOList(List<SpecVersion> versions);
}
