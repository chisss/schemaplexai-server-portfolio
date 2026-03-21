package com.schemaplexai.service.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * 通用实体校验器 — 消除重复的存在性检查和唯一性校验模式
 */
@Component
public class EntityValidator {

    /**
     * 校验实体存在性，不存在则抛出 BusinessException
     *
     * @param mapper    MyBatis-Plus Mapper
     * @param id        主键ID
     * @param errorCode 不存在时的错误码
     * @return 存在的实体
     */
    public <T> T requireExists(BaseMapper<T> mapper, Serializable id, ResultCode errorCode) {
        var entity = mapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(errorCode);
        }
        return entity;
    }

    /**
     * 校验字段唯一性，已存在则抛出 BusinessException
     *
     * @param mapper    MyBatis-Plus Mapper
     * @param column    字段 lambda 引用
     * @param value     字段值
     * @param errorCode 重复时的错误码
     */
    public <T> void checkUnique(BaseMapper<T> mapper, SFunction<T, ?> column,
                                Object value, ResultCode errorCode) {
        var count = mapper.selectCount(new LambdaQueryWrapper<T>().eq(column, value));
        if (count > 0) {
            throw new BusinessException(errorCode);
        }
    }

}
