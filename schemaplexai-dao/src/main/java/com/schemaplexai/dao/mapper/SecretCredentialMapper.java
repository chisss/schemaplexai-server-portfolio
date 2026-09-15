package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.SecretCredential;
import org.apache.ibatis.annotations.Mapper;

/**
 * 密钥凭据 Mapper
 */
@Mapper
public interface SecretCredentialMapper extends BaseMapper<SecretCredential> {
}
