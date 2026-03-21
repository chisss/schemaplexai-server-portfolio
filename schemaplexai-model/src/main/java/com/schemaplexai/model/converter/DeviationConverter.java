package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.QualityDeviation;
import com.schemaplexai.model.vo.quality.DeviationVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import java.util.List;

/**
 * 偏离记录转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface DeviationConverter {

    @Mapping(target = "specName", ignore = true)
    @Mapping(target = "deviationTypeLabel", ignore = true)
    @Mapping(target = "severityLabel", ignore = true)
    @Mapping(target = "statusLabel", ignore = true)
    @Mapping(target = "resolvedByName", ignore = true)
    DeviationVO toVO(QualityDeviation entity);

    List<DeviationVO> toVOList(List<QualityDeviation> entities);
}
