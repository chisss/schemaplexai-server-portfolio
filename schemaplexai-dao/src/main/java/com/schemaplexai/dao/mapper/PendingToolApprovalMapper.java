package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.PendingToolApproval;
import org.apache.ibatis.annotations.Mapper;

/**
 * 待审批工具调用 Mapper
 */
@Mapper
public interface PendingToolApprovalMapper extends BaseMapper<PendingToolApproval> {
}
