package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.WebhookEvent;
import com.schemaplexai.model.vo.integration.WebhookEventVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * Webhook事件转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface WebhookEventConverter {

    /**
     * WebhookEvent -> WebhookEventVO
     */
    WebhookEventVO toVO(WebhookEvent entity);

    /**
     * WebhookEvent列表 -> WebhookEventVO列表
     */
    List<WebhookEventVO> toVOList(List<WebhookEvent> entities);
}
