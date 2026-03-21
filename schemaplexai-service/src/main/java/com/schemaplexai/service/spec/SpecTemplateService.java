package com.schemaplexai.service.spec;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.spec.SpecFromTemplateRequest;
import com.schemaplexai.model.dto.spec.SpecTemplateCreateRequest;
import com.schemaplexai.model.dto.spec.SpecTemplateQueryRequest;
import com.schemaplexai.model.vo.spec.SpecTemplateVO;
import com.schemaplexai.model.vo.spec.SpecVO;

/**
 * Spec模板管理服务接口
 */
public interface SpecTemplateService {

    /** 分页查询模板列表 */
    PageResult<SpecTemplateVO> listTemplates(SpecTemplateQueryRequest request);

    /** 获取模板详情 */
    SpecTemplateVO getTemplateById(String id);

    /** 创建自定义模板 */
    SpecTemplateVO createTemplate(SpecTemplateCreateRequest request);

    /** 删除模板（内置模板不可删除） */
    void deleteTemplate(String id);

    /** 从模板创建Spec */
    SpecVO createSpecFromTemplate(String templateId, SpecFromTemplateRequest request);
}
