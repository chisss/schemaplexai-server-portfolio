package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.CrossReview;
import com.schemaplexai.model.vo.quality.CrossReviewVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import java.util.List;

/**
 * 交叉审查转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface CrossReviewConverter {

    @Mapping(target = "specName", ignore = true)
    @Mapping(target = "modelAName", ignore = true)
    @Mapping(target = "modelBName", ignore = true)
    CrossReviewVO toVO(CrossReview entity);

    List<CrossReviewVO> toVOList(List<CrossReview> entities);
}
