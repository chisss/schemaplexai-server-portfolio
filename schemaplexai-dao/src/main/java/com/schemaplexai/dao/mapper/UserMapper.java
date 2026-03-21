package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * User Mapper接口
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
