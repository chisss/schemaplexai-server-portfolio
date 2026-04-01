package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.SecurityBinding;
import org.apache.ibatis.annotations.Mapper;

/**
 * 安全绑定关系 Mapper
 */
@Mapper
public interface SecurityBindingMapper extends BaseMapper<SecurityBinding> {
}
