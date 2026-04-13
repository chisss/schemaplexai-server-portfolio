package com.schemaplexai.service.message;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.message.MessageTemplateCreateRequest;
import com.schemaplexai.model.dto.message.MessageTemplateQueryRequest;
import com.schemaplexai.model.dto.message.MessageTemplateUpdateRequest;
import com.schemaplexai.model.vo.message.MessageTemplateMetadataVO;
import com.schemaplexai.model.vo.message.MessageTemplateVO;

import java.util.List;

/**
 * 消息模板服务
 */
public interface MessageTemplateService {

    MessageTemplateVO create(MessageTemplateCreateRequest request);

    PageResult<MessageTemplateVO> page(MessageTemplateQueryRequest request);

    MessageTemplateVO getById(String id);

    MessageTemplateVO update(String id, MessageTemplateUpdateRequest request);

    void delete(String id);

    List<MessageTemplateMetadataVO> listDefinitions();
}
