package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.ReviewSession;
import com.schemaplexai.model.vo.workflow.ReviewSessionVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;
import java.util.Map;

/**
 * 评审会话转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface ReviewSessionConverter {

    @Mapping(target = "comments", ignore = true)
    @Mapping(target = "summary", ignore = true)
    ReviewSessionVO toVO(ReviewSession session);

    List<ReviewSessionVO> toVOList(List<ReviewSession> sessions);

    /**
     * 自定义映射: List<Object> → List<Map<String, Object>>
     */
    @SuppressWarnings("unchecked")
    default List<Map<String, Object>> mapReviewers(List<Object> reviewers) {
        if (reviewers == null) {
            return null;
        }
        return reviewers.stream()
                .filter(r -> r instanceof Map)
                .map(r -> (Map<String, Object>) r)
                .toList();
    }
}
