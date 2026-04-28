package com.schemaplexai.model.converter;

import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.model.dto.message.MessageTemplateCreateRequest;
import com.schemaplexai.model.entity.MessageTemplate;
import com.schemaplexai.model.vo.message.MessageTemplateVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 消息模板转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE,
        imports = {CommonConstant.class})
public interface MessageTemplateConverter {

    @Mapping(target = "createdByName", ignore = true)
    MessageTemplateVO toVO(MessageTemplate entity);

    List<MessageTemplateVO> toVOList(List<MessageTemplate> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "status", expression = "java(CommonConstant.STATUS_ACTIVE)")
    @Mapping(target = "supportedChannels", source = "supportedChannels")
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    MessageTemplate fromCreateRequest(MessageTemplateCreateRequest request);
}
