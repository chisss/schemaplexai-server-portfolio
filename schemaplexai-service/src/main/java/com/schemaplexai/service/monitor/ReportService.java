package com.schemaplexai.service.monitor;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.monitor.ReportQueryRequest;
import com.schemaplexai.model.dto.monitor.ReportTemplateCreateRequest;
import com.schemaplexai.model.vo.monitor.ReportDataVO;
import com.schemaplexai.model.vo.monitor.ReportTemplateVO;

import java.util.Map;

/**
 * 报表服务
 */
public interface ReportService {

    ReportDataVO getEfficiencyReport(ReportQueryRequest request);

    ReportDataVO getQualityReport(ReportQueryRequest request);

    ReportDataVO getAgentPerformanceReport(ReportQueryRequest request, String agentIds);

    ReportDataVO getProjectProgressReport(ReportQueryRequest request);

    ReportDataVO getTeamCollaborationReport(ReportQueryRequest request);

    ReportDataVO customQuery(Map<String, Object> queryConfig);

    PageResult<ReportTemplateVO> pageTemplates(String reportType, Integer page, Integer size);

    ReportTemplateVO createTemplate(ReportTemplateCreateRequest request);
}
