package com.schemaplexai.model.converter;

import com.schemaplexai.common.enums.NotificationChannelStatusEnum;
import com.schemaplexai.model.dto.channel.NotificationChannelCreateRequest;
import com.schemaplexai.model.entity.NotificationChannel;
import com.schemaplexai.model.vo.channel.NotificationChannelVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 通知渠道转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE,
        imports = {NotificationChannelStatusEnum.class})
public interface NotificationChannelConverter {

    @Mapping(target = "createdByName", ignore = true)
    NotificationChannelVO toVO(NotificationChannel entity);

    List<NotificationChannelVO> toVOList(List<NotificationChannel> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "status", expression = "java(NotificationChannelStatusEnum.INACTIVE.getCode())")
    @Mapping(target = "lastTestAt", ignore = true)
    @Mapping(target = "errorMessage", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    NotificationChannel fromCreateRequest(NotificationChannelCreateRequest request);
}
