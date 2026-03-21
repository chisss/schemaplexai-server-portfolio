package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.ContextSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 上下文快照 Mapper
 */
@Mapper
public interface ContextSnapshotMapper extends BaseMapper<ContextSnapshot> {
}
