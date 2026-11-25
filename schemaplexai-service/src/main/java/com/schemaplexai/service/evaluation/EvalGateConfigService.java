package com.schemaplexai.service.evaluation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.EvalGateConfigMapper;
import com.schemaplexai.dao.mapper.EvalTaskMapper;
import com.schemaplexai.model.dto.evaluation.EvalGateConfigSaveRequest;
import com.schemaplexai.model.entity.EvalGateConfig;
import com.schemaplexai.model.entity.EvalTask;
import com.schemaplexai.model.vo.evaluation.EvalGateConfigVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 评估门禁配置服务（后台管理页：管理人员配置并开启 CI 门禁）
 * <p>
 * 单租户单行：enabled 开关 + taskId 绑定评估任务 + passRateThreshold 达标率门槛。
 * 判定语义由 verdict 端点消费（CI 脚本透传 passRateThreshold）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalGateConfigService {

    private final EvalGateConfigMapper gateConfigMapper;
    private final EvalTaskMapper evalTaskMapper;

    /**
     * 获取当前租户门禁配置（无配置时返回默认视图：关闭态 + 达标率 100）
     */
    public EvalGateConfigVO get() {
        EvalGateConfig config = selectCurrentTenantConfig();
        EvalGateConfigVO vo = new EvalGateConfigVO();
        if (config == null) {
            vo.setEnabled(false);
            vo.setPassRateThreshold(100);
            return vo;
        }
        vo.setEnabled(Boolean.TRUE.equals(config.getEnabled()));
        vo.setTaskId(config.getTaskId());
        vo.setPassRateThreshold(config.getPassRateThreshold());
        vo.setUpdatedAt(config.getUpdatedAt());
        if (StringUtils.hasText(config.getTaskId())) {
            EvalTask task = evalTaskMapper.selectById(config.getTaskId());
            if (task != null) {
                vo.setTaskName(task.getName());
                vo.setTaskStatus(task.getStatus());
            }
        }
        return vo;
    }

    /**
     * 保存（upsert）当前租户门禁配置
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalGateConfigVO save(EvalGateConfigSaveRequest request) {
        // 绑定任务存在性校验（防止绑定到不存在的任务导致 CI 恒 FAIL）
        EvalTask task = evalTaskMapper.selectById(request.getTaskId());
        if (task == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "绑定的评估任务不存在");
        }
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(tenantId) && StringUtils.hasText(task.getTenantId())
                && !tenantId.equals(task.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "不能绑定其他租户的评估任务");
        }

        EvalGateConfig config = selectCurrentTenantConfig();
        if (config == null) {
            config = new EvalGateConfig();
            config.setTenantId(SecurityUtil.getCurrentTenantId());
        }
        config.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        config.setTaskId(request.getTaskId());
        config.setPassRateThreshold(request.getPassRateThreshold());
        if (config.getId() == null) {
            gateConfigMapper.insert(config);
        } else {
            gateConfigMapper.updateById(config);
        }
        log.info("保存评估门禁配置: tenantId={}, enabled={}, taskId={}, threshold={}",
                SecurityUtil.getCurrentTenantId(), request.getEnabled(),
                request.getTaskId(), request.getPassRateThreshold());
        return get();
    }

    private EvalGateConfig selectCurrentTenantConfig() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        return gateConfigMapper.selectOne(new LambdaQueryWrapper<EvalGateConfig>()
                .eq(EvalGateConfig::getTenantId, tenantId)
                .last("LIMIT 1"));
    }
}
