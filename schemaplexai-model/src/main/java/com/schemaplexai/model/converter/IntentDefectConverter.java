package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.IntentDefect;
import com.schemaplexai.model.vo.quality.IntentDefectVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import java.util.List;

/**
 * 意图缺陷转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface IntentDefectConverter {

    @Mapping(target = "specName", ignore = true)
    @Mapping(target = "docTypeLabel", ignore = true)
    @Mapping(target = "defectTypeLabel", ignore = true)
    IntentDefectVO toVO(IntentDefect entity);

    List<IntentDefectVO> toVOList(List<IntentDefect> entities);
}
