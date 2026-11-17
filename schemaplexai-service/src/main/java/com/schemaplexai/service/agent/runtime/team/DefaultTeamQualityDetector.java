package com.schemaplexai.service.agent.runtime.team;

import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Team 质量判据与规则化兜底的默认实现（审计 P2-1 拆分）。
 *
 * <p>聚合判据纯函数与「公司/联系人/获客」业务规则全部收敛于此，支持插拔替换。</p>
 */
@Component
public class DefaultTeamQualityDetector implements TeamQualityDetector {

    private static final Pattern MIN_COMPANY_COUNT_PATTERN = Pattern.compile(
            "(?:至少|不少于|不低于|at\\s+least)(?:[^\\d\\n]{0,12})?(\\d+)\\s*(?:家|companies?)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public String resolveThrowableMessage(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        Throwable cursor = throwable;
        while (cursor != null && parts.size() < 5) {
            String message = cursor.getMessage();
            if (StringUtils.hasText(message)) {
                parts.add(cursor.getClass().getSimpleName() + ": " + message);
            } else {
                parts.add(cursor.getClass().getSimpleName());
            }
            cursor = cursor.getCause();
        }
        return StringUtils.collectionToDelimitedString(parts, " | ");
    }

    @Override
    public boolean isSuccessfulOutputStatus(String status) {
        return AgentExecutionStatusEnum.COMPLETED.getCode().equals(status)
                || AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode().equals(status);
    }

    @Override
    public boolean shouldUseRuleBasedFallback(List<String> errors) {
        if (CollectionUtils.isEmpty(errors)) {
            return false;
        }
        return errors.stream().allMatch(error -> {
            String normalized = (StringUtils.hasText(error) ? error : "").toLowerCase();
            return normalized.contains("eofexception")
                    || normalized.contains("socketexception")
                    || normalized.contains("connection reset")
                    || normalized.contains("socket closed")
                    || normalized.contains("authentication_error")
                    || normalized.contains("身份验证失败")
                    || normalized.contains("invalid api key")
                    || normalized.contains("invalidparameter")
                    || normalized.contains("not support")
                    || normalized.contains("所有候选模型当前处于冷却期")
                    || normalized.contains("insufficient_quota")
                    || normalized.contains("quota exceeded")
                    || normalized.contains("too many requests");
        });
    }

    @Override
    public boolean isTransientModelChannelFailure(String errorMessage) {
        String normalized = (StringUtils.hasText(errorMessage) ? errorMessage : "").toLowerCase();
        return normalized.contains("eofexception")
                || normalized.contains("socketexception")
                || normalized.contains("connection reset")
                || normalized.contains("socket closed")
                || normalized.contains("authentication_error")
                || normalized.contains("身份验证失败")
                || normalized.contains("invalid api key")
                || normalized.contains("invalidparameter")
                || normalized.contains("not support")
                || normalized.contains("所有候选模型当前处于冷却期")
                || normalized.contains("insufficient_quota")
                || normalized.contains("quota exceeded")
                || normalized.contains("too many requests");
    }

    @Override
    public String buildExecutionLevelFallbackOutput(String executionId, String agentId, String errorMessage) {
        return "# Team Agent 规则化兜底交付\n\n"
                + "## 已确认事实\n"
                + "- Team Agent 已完成工作流触发、上下文装配、成员编排与链路追踪。\n"
                + "- 模型通道返回不可用类异常，未能获得成员深度分析结果。\n"
                + "- 系统为保证交付链路不中断，生成规则化可复核草案。\n\n"
                + "## 交付草案\n"
                + "1. 明确客户业务目标、当前约束、目标用户和交付成功指标。\n"
                + "2. 将方案拆解为诊断、设计、执行、交付、复盘五个阶段。\n"
                + "3. 每个阶段绑定责任角色、输入资料、输出物和验收标准。\n"
                + "4. 对最终文档补充实际案例、业务配图、执行里程碑和风险清单。\n\n"
                + "## 平台能力验证\n"
                + "- 上下文管理：工作流变量、Team 成员和节点输入已被装配。\n"
                + "- 工具调用：模型通道异常已进入执行日志和链路追踪。\n"
                + "- 交付产物：当前为兜底草案，可继续进入工作流后续通知和归档。\n"
                + "- 质量检测：该产物标记为需二次复核，不作为最终客户正式版本。\n\n"
                + "## 风险与后续\n"
                + "- 风险：成员模型未产出，内容深度低于正常交付。\n"
                + "- 后续：模型通道恢复后建议重新执行该节点生成增强版。\n\n"
                + "---\n"
                + "- Team执行ID: " + executionId + "\n"
                + "- AgentID: " + agentId + "\n"
                + "- 兜底原因: " + (StringUtils.hasText(errorMessage) && errorMessage.length() > 800 ? errorMessage.substring(0, 800) : StringUtils.hasText(errorMessage) ? errorMessage : "");
    }

    @Override
    public Integer resolveMinimumCompanyCount(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            return null;
        }
        Matcher matcher = MIN_COMPANY_COUNT_PATTERN.matcher(prompt);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @Override
    public int countQualifiedCompanyRows(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return 0;
        }
        List<String> lines = List.of(markdown.split("\\R"));
        int qualifiedRows = 0;
        int fallbackRows = 0;
        String currentSection = "";
        for (int index = 0; index < lines.size(); ) {
            String line = lines.get(index).trim();
            if (line.startsWith("#")) {
                currentSection = line.replaceFirst("^#+\\s*", "").trim();
                index++;
                continue;
            }
            if (!line.startsWith("|")) {
                index++;
                continue;
            }
            List<String> tableLines = new ArrayList<>();
            while (index < lines.size() && lines.get(index).trim().startsWith("|")) {
                tableLines.add(lines.get(index).trim());
                index++;
            }
            TableCompanyCount tableCount = analyzeCompanyTable(tableLines, currentSection);
            qualifiedRows += tableCount.qualifiedRows();
            fallbackRows += tableCount.fallbackRows();
        }
        return qualifiedRows > 0 ? qualifiedRows : fallbackRows;
    }

    @Override
    public boolean containsConclusionBlocker(String markdown) {
        return StringUtils.hasText(markdown) && CONCLUSION_BLOCKER_PATTERN.matcher(markdown).find();
    }

    private static final Pattern CONCLUSION_BLOCKER_PATTERN = Pattern.compile(
            "(?:当前状态|结论)[\\s\\S]{0,160}?(?:暂不满足|未满足|不满足)",
            Pattern.CASE_INSENSITIVE
    );

    private static final List<String> NON_FINAL_SECTION_KEYWORDS = List.of(
            "待补充", "已剔除", "剔除", "风险", "未知", "待执行", "后续动作", "高潜力"
    );

    @Override
    public int countDetectedCompanyRows(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return 0;
        }
        int tableRowCount = 0;
        for (String rawLine : markdown.split("\\R")) {
            String line = rawLine.trim();
            if (!line.startsWith("|")) {
                continue;
            }
            if (line.matches("^\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?$")) {
                continue;
            }
            List<String> headerCells = parseMarkdownCells(line);
            if (findCompanyHeaderIndex(headerCells) >= 0) {
                continue;
            }
            tableRowCount++;
        }
        return tableRowCount;
    }

    private TableCompanyCount analyzeCompanyTable(List<String> tableLines, String sectionTitle) {
        if (CollectionUtils.isEmpty(tableLines)) {
            return TableCompanyCount.empty();
        }
        List<String> headerCells = parseMarkdownCells(tableLines.get(0));
        int companyIndex = findCompanyHeaderIndex(headerCells);
        if (companyIndex < 0) {
            return TableCompanyCount.empty();
        }
        int confidenceIndex = findHeaderIndex(headerCells, "可信度", "confidence");
        int qualifiedRows = 0;
        int fallbackRows = 0;
        for (int index = 1; index < tableLines.size(); index++) {
            String tableLine = tableLines.get(index);
            if (isMarkdownSeparatorLine(tableLine)) {
                continue;
            }
            List<String> cells = parseMarkdownCells(tableLine);
            if (companyIndex >= cells.size()) {
                continue;
            }
            String companyName = cells.get(companyIndex);
            if (!StringUtils.hasText(companyName)) {
                continue;
            }
            if (confidenceIndex >= 0) {
                if (confidenceIndex < cells.size() && isQualifiedConfidence(cells.get(confidenceIndex))) {
                    qualifiedRows++;
                }
                continue;
            }
            if (!isNonFinalSection(sectionTitle)) {
                fallbackRows++;
            }
        }
        return new TableCompanyCount(qualifiedRows, fallbackRows);
    }

    private boolean isMarkdownSeparatorLine(String line) {
        return StringUtils.hasText(line)
                && line.matches("^\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?$");
    }

    private List<String> parseMarkdownCells(String line) {
        if (!StringUtils.hasText(line)) {
            return List.of();
        }
        String normalized = line.trim();
        if (normalized.startsWith("|")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("|")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return Arrays.stream(normalized.split("\\|", -1))
                .map(String::trim)
                .toList();
    }

    private int findCompanyHeaderIndex(List<String> headerCells) {
        if (CollectionUtils.isEmpty(headerCells)) {
            return -1;
        }
        for (int index = 0; index < headerCells.size(); index++) {
            if (isCompanyHeaderCell(headerCells.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private int findHeaderIndex(List<String> headerCells, String... keywords) {
        if (CollectionUtils.isEmpty(headerCells) || keywords == null || keywords.length == 0) {
            return -1;
        }
        for (int index = 0; index < headerCells.size(); index++) {
            String header = headerCells.get(index);
            if (!StringUtils.hasText(header)) {
                continue;
            }
            String normalizedHeader = header.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (normalizedHeader.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return index;
                }
            }
        }
        return -1;
    }

    private boolean isCompanyHeaderCell(String header) {
        if (!StringUtils.hasText(header)) {
            return false;
        }
        String normalized = header.replace("**", "")
                .replace("`", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        return "公司名称".equals(normalized)
                || "公司".equals(normalized)
                || "企业名称".equals(normalized)
                || "企业".equals(normalized)
                || "客户名称".equals(normalized)
                || "客户".equals(normalized)
                || "目标公司".equals(normalized)
                || "company".equals(normalized)
                || "company name".equals(normalized)
                || "companyname".equals(normalized);
    }

    private boolean isQualifiedConfidence(String confidence) {
        if (!StringUtils.hasText(confidence)) {
            return false;
        }
        String normalized = confidence.toLowerCase(Locale.ROOT);
        return normalized.contains("high") || normalized.contains("medium");
    }

    private boolean isNonFinalSection(String sectionTitle) {
        if (!StringUtils.hasText(sectionTitle)) {
            return false;
        }
        String normalized = sectionTitle.toLowerCase(Locale.ROOT);
        return NON_FINAL_SECTION_KEYWORDS.stream()
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
    }

    private record TableCompanyCount(int qualifiedRows, int fallbackRows) {
        private static TableCompanyCount empty() {
            return new TableCompanyCount(0, 0);
        }
    }
}
