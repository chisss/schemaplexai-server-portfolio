package com.schemaplexai.service.desktop;

import java.util.List;

public record DesktopWorkbenchTemplate(String id, int version, String name, String category,
                                       String icon, String welcome, List<String> starterPrompts,
                                       boolean available) {
    private static final List<DesktopWorkbenchTemplate> TEMPLATES = List.of(
            template("engineering-delivery", "研发交付", "研发", "code",
                    "从问题定位到可验证交付", "阅读代码", "排查缺陷", "编写测试", "审查变更"),
            template("product-project", "产品项目", "产品", "project",
                    "把目标整理成团队可执行的计划", "整理需求", "拆解任务", "编写周报", "列出风险"),
            template("data-analysis", "数据分析", "数据", "chart",
                    "用企业数据回答业务问题", "分析趋势", "对比指标", "解释异常"),
            template("marketing", "内容营销", "运营", "campaign",
                    "围绕企业资料产出可用内容", "策划活动", "起草内容", "整理渠道文案"),
            template("finance-admin", "财务行政", "职能", "receipt",
                    "整理材料并跟进日常事务", "整理费用材料", "归纳制度", "整理会议纪要"),
            template("people", "人力协作", "职能", "people",
                    "辅助整理招聘与入职协作材料", "起草岗位说明", "整理面试提纲", "编制入职清单"));

    private static DesktopWorkbenchTemplate template(String id, String name, String category,
                                                      String icon, String welcome, String... prompts) {
        return new DesktopWorkbenchTemplate(id, 1, name, category, icon, welcome, List.of(prompts), false);
    }

    public static List<DesktopWorkbenchTemplate> all() {
        return TEMPLATES;
    }

    public DesktopWorkbenchTemplate availability(boolean enabled) {
        return withVersion(version, enabled);
    }

    public DesktopWorkbenchTemplate withVersion(int revision, boolean enabled) {
        return new DesktopWorkbenchTemplate(id, revision, name, category, icon, welcome, starterPrompts, enabled);
    }
}
