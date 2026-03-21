package com.schemaplexai.service.monitor.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.ReportTemplateMapper;
import com.schemaplexai.model.converter.ReportTemplateConverter;
import com.schemaplexai.model.dto.monitor.ReportQueryRequest;
import com.schemaplexai.model.dto.monitor.ReportTemplateCreateRequest;
import com.schemaplexai.model.entity.ReportTemplate;
import com.schemaplexai.model.vo.monitor.ReportDataVO;
import com.schemaplexai.model.vo.monitor.ReportTemplateVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.monitor.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 报表服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ReportTemplateMapper reportTemplateMapper;
    private final ReportTemplateConverter reportTemplateConverter;
    private final EntityValidator entityValidator;

    @Override
    public ReportDataVO getEfficiencyReport(ReportQueryRequest request) {
        log.info("查询效率报表, request={}", request);

        // TODO: 从ClickHouse查询效率指标数据（任务完成率、平均耗时、吞吐量等）
        return new ReportDataVO();
    }

    @Override
    public ReportDataVO getQualityReport(ReportQueryRequest request) {
        log.info("查询质量报表, request={}", request);

        // TODO: 从ClickHouse查询质量指标数据（代码质量分、偏差率、返工率等）
        return new ReportDataVO();
    }

    @Override
    public ReportDataVO getAgentPerformanceReport(ReportQueryRequest request, String agentIds) {
        log.info("查询Agent性能报表, request={}, agentIds={}", request, agentIds);

        // TODO: 从ClickHouse查询Agent性能数据（执行时间、成功率、Token消耗等），按agentIds过滤
        return new ReportDataVO();
    }

    @Override
    public ReportDataVO getProjectProgressReport(ReportQueryRequest request) {
        log.info("查询项目进度报表, request={}", request);

        // TODO: 从ClickHouse查询项目进度数据（里程碑完成情况、任务燃尽图等）
        return new ReportDataVO();
    }

    @Override
    public ReportDataVO getTeamCollaborationReport(ReportQueryRequest request) {
        log.info("查询团队协作报表, request={}", request);

        // TODO: 从ClickHouse查询团队协作数据（审批效率、代码审查统计、协作频次等）
        return new ReportDataVO();
    }

    @Override
    public ReportDataVO customQuery(Map<String, Object> queryConfig) {
        log.info("执行自定义报表查询, queryConfig={}", queryConfig);

        // TODO: 根据queryConfig动态构建ClickHouse查询语句并执行
        return new ReportDataVO();
    }

    @Override
    public PageResult<ReportTemplateVO> pageTemplates(String reportType, Integer page, Integer size) {
        log.info("分页查询报表模板, reportType={}, page={}, size={}", reportType, page, size);

        var pageParam = new Page<ReportTemplate>(page, size);
        var wrapper = new LambdaQueryWrapper<ReportTemplate>();

        // 可选的报表类型过滤
        if (StringUtils.hasText(reportType)) {
            wrapper.eq(ReportTemplate::getReportType, reportType);
        }
        wrapper.orderByDesc(ReportTemplate::getCreatedAt);

        var result = reportTemplateMapper.selectPage(pageParam, wrapper);
        var voList = reportTemplateConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportTemplateVO createTemplate(ReportTemplateCreateRequest request) {
        log.info("创建报表模板, name={}, reportType={}", request.getName(), request.getReportType());

        // 使用Converter转换请求为实体
        var template = reportTemplateConverter.fromCreateRequest(request);

        // 填充租户和创建人信息
        template.setTenantId(SecurityUtil.getCurrentTenantId());
        template.setCreatedBy(SecurityUtil.getCurrentUserId());

        reportTemplateMapper.insert(template);

        log.info("创建报表模板成功: templateId={}", template.getId());
        return reportTemplateConverter.toVO(template);
    }
}
