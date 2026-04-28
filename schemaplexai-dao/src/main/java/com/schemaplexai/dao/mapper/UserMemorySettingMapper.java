package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.UserMemorySetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户记忆设置 Mapper
 */
@Mapper
public interface UserMemorySettingMapper extends BaseMapper<UserMemorySetting> {
}
