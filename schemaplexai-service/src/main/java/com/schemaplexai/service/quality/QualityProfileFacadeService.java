package com.schemaplexai.service.quality;

import com.schemaplexai.model.dto.quality.QualityProfileConfigRequest;
import com.schemaplexai.model.vo.quality.QualityProfileConfigVO;

import java.util.List;

/**
 * 质量配置组门面服务
 */
public interface QualityProfileFacadeService {

    List<QualityProfileConfigVO> list();

    QualityProfileConfigVO getById(String id);

    QualityProfileConfigVO create(QualityProfileConfigRequest request);

    QualityProfileConfigVO update(String id, QualityProfileConfigRequest request);

    void delete(String id);
}
