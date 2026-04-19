package com.schemaplexai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.vo.system.ConnectivityTestResultVO;
import com.schemaplexai.service.config.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型健康检查定时任务
 * 定期检查所有活跃模型的连通性，更新测试记录
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentHealthCheckTask {

    private final AiModelMapper aiModelMapper;
    private final SystemConfigService systemConfigService;

    /**
     * 每5分钟检查模型健康状态
     * 遍历所有 active 状态的模型，逐一执行连通性测试并更新测试记录
     */
    @Scheduled(fixedRate = 300000)
    public void checkModelHealth() {
        log.debug("开始模型健康检查...");

        // 查询所有活跃状态的模型
        List<AiModel> activeModels = aiModelMapper.selectList(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getStatus, "active")
        );

        if (activeModels.isEmpty()) {
            log.debug("无活跃模型，跳过健康检查");
            return;
        }

        log.info("开始模型健康检查，共 {} 个活跃模型", activeModels.size());
        int successCount = 0;
        int failCount = 0;

        for (AiModel model : activeModels) {
            try {
                // 复用连通性测试逻辑，内部会自动保存测试结果到 sf_ai_model 表
                ConnectivityTestResultVO result = systemConfigService.testConnectivity(model.getId());
                if ("success".equals(result.getStatus())) {
                    successCount++;
                } else {
                    failCount++;
                    log.warn("模型健康检查失败: modelId={}, name={}, status={}, error={}",
                            model.getId(), model.getName(), result.getStatus(), result.getErrorMessage());
                }
            } catch (Exception e) {
                failCount++;
                log.error("模型健康检查异常: modelId={}, name={}", model.getId(), model.getName(), e);
            }
        }

        log.info("模型健康检查完成: total={}, success={}, fail={}", activeModels.size(), successCount, failCount);
    }
}
