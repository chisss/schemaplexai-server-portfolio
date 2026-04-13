package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.NotificationRecord;
import com.schemaplexai.model.vo.notification.NotificationRecordVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 通知记录转换器
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface NotificationRecordConverter {

    NotificationRecordVO toVO(NotificationRecord entity);

    List<NotificationRecordVO> toVOList(List<NotificationRecord> entities);
}
