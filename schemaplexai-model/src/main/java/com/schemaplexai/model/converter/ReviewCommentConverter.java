package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.ReviewComment;
import com.schemaplexai.model.vo.workflow.ReviewCommentVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 评审意见转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface ReviewCommentConverter {

    ReviewCommentVO toVO(ReviewComment comment);

    List<ReviewCommentVO> toVOList(List<ReviewComment> comments);
}
