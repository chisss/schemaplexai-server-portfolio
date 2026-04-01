package com.schemaplexai.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.RagOperationLogMapper;
import com.schemaplexai.model.entity.RagOperationLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 向量模型累计额度校验器
 */
@Component
@RequiredArgsConstructor
public class EmbeddingQuotaGuard {

    private final RagOperationLogMapper ragOperationLogMapper;

    public void ensureWithinQuota(String tenantId, RagRuntimeSettings settings, int requestTokens) {
        if (settings == null
                || !StringUtils.hasText(settings.getVectorModelConfigId())
                || settings.getMaxQuotaTokens() <= 0
                || requestTokens <= 0) {
            return;
        }
        int usedTokens = ragOperationLogMapper.selectList(new LambdaQueryWrapper<RagOperationLog>()
                        .select(RagOperationLog::getRequestTokens)
                        .eq(RagOperationLog::getTenantId, tenantId)
                        .eq(RagOperationLog::getModelConfigId, settings.getVectorModelConfigId())
                        .eq(RagOperationLog::getStatus, "success")
                        .isNotNull(RagOperationLog::getRequestTokens))
                .stream()
                .map(RagOperationLog::getRequestTokens)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        if (usedTokens + requestTokens > settings.getMaxQuotaTokens()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    String.format("向量模型累计 Tokens 已达到上限，当前已用 %d，本次预计 %d，配置上限 %d",
                            usedTokens, requestTokens, settings.getMaxQuotaTokens()));
        }
    }
}
