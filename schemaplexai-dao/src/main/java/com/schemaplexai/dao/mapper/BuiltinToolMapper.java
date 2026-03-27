package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.BuiltinTool;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统内置工具 Mapper
 */
@Mapper
public interface BuiltinToolMapper extends BaseMapper<BuiltinTool> {
}
