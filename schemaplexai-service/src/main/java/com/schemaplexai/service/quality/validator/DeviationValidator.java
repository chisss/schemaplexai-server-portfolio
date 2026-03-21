package com.schemaplexai.service.quality.validator;

import com.schemaplexai.common.enums.DeviationStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;

/**
 * 偏离状态校验器 — 校验状态流转合法性及相关业务规则
 */
@Component
@RequiredArgsConstructor
public class DeviationValidator {

    /**
     * 状态流转矩阵：当前状态 → 允许的目标状态集合
     * OPEN → ACKNOWLEDGED / RESOLVED / IGNORED
     * ACKNOWLEDGED → RESOLVED / IGNORED
     */
    private static final Map<String, Set<String>> VALID_TRANSITIONS = Map.of(
            DeviationStatusEnum.OPEN.getCode(), Set.of(
                    DeviationStatusEnum.ACKNOWLEDGED.getCode(),
                    DeviationStatusEnum.RESOLVED.getCode(),
                    DeviationStatusEnum.IGNORED.getCode()
            ),
            DeviationStatusEnum.ACKNOWLEDGED.getCode(), Set.of(
                    DeviationStatusEnum.RESOLVED.getCode(),
                    DeviationStatusEnum.IGNORED.getCode()
            )
    );

    /**
     * 校验状态流转合法性
     *
     * @param currentStatus 当前状态
     * @param newStatus     目标状态
     */
    public void validateStatusTransition(String currentStatus, String newStatus) {
        Set<String> allowedTargets = VALID_TRANSITIONS.get(currentStatus);
        if (allowedTargets == null || !allowedTargets.contains(newStatus)) {
            throw new BusinessException(ResultCode.DEVIATION_STATUS_INVALID);
        }
    }

    /**
     * 校验解决备注：当状态为 resolved 时，备注不能为空
     *
     * @param status 目标状态
     * @param remark 备注内容
     */
    public void validateResolveRemark(String status, String remark) {
        if (DeviationStatusEnum.RESOLVED.getCode().equals(status) && !StringUtils.hasText(remark)) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
    }
}
