package com.schemaplexai.service.skill;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.skill.SkillCreateRequest;
import com.schemaplexai.model.dto.skill.SkillQueryRequest;
import com.schemaplexai.model.dto.skill.SkillUpdateRequest;
import com.schemaplexai.model.vo.skill.SkillVO;

/**
 * 技能服务接口
 */
public interface SkillService {

    SkillVO create(SkillCreateRequest request);

    PageResult<SkillVO> page(SkillQueryRequest request);

    SkillVO getById(String id);

    SkillVO update(String id, SkillUpdateRequest request);

    void delete(String id);
}
