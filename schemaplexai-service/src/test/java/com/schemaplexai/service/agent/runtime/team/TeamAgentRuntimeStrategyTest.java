package com.schemaplexai.service.agent.runtime.team;

import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberContextBindingMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberToolBindingMapper;
import com.schemaplexai.dao.mapper.BuiltinToolMapper;
import com.schemaplexai.dao.mapper.ContextItemMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AgentTeamMember;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.agent.execution.AgentLoopQualityChecker;
import com.schemaplexai.service.agent.execution.AgentLogService;
import com.schemaplexai.service.agent.execution.ExecutionEventStreamService;
import com.schemaplexai.service.mq.AgentContextPublisher;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamAgentRuntimeStrategyTest {

    @Test
    void shouldIncludeUpstreamEvidenceAndStructuredFetchHintsForContributor() {
        TeamAgentRuntimeStrategy strategy = createStrategy();
        AgentTeamMember member = new AgentTeamMember();
        member.setRoleName("Contact Researcher");
        member.setRoleType("member");
        member.setDescription("负责补充联系人");

        String prompt = strategy.buildMemberPrompt(
                "请为南美氨基酸线索补充公开联系方式。",
                member,
                0,
                null,
                Map.of(),
                null,
                "## 上游成员已确认事实与证据\n### Company Researcher\n- 公司名称: Vitafor"
        );

        assertThat(prompt).contains("web.fetch 返回 links/emails/phones");
        assertThat(prompt).contains("上游成员证据");
        assertThat(prompt).contains("优先围绕上游已确认的公司");
    }

    @Test
    void shouldResolveMinimumCompanyCountAndCountMarkdownRows() {
        TeamAgentRuntimeStrategy strategy = createStrategy();

        Integer minimumCompanyCount = strategy.resolveMinimumCompanyCount("最终主表至少10家可信度为 high 或 medium 的公司");
        Integer workflowGoalMinimumCompanyCount = strategy.resolveMinimumCompanyCount("请执行南美氨基酸原料获客 Team 工作流：以公开官网证据为准，至少输出10家 high/medium 可信度公司，结果以飞书文档表格交付。");
        int detectedRows = strategy.countDetectedCompanyRows("""
                | 公司名称 | 国家/地区 | 联系方式 |
                | --- | --- | --- |
                | Vitafor | 巴西 | contato@vitafor.com.br |
                | Bodyaction | 巴西 | form:https://www.bodyaction.com.br/contato |
                | Pure Bulk | 哥伦比亚 | +57 300 123 4567 |
                """);

        assertThat(minimumCompanyCount).isEqualTo(10);
        assertThat(workflowGoalMinimumCompanyCount).isEqualTo(10);
        assertThat(detectedRows).isEqualTo(3);
    }

    @Test
    void shouldCountOnlyQualifiedRowsAndDetectUndeliveredConclusion() {
        TeamAgentRuntimeStrategy strategy = createStrategy();

        String markdown = """
                ## 结论

                **当前状态**：暂不满足“10家High/Medium”的收敛要求，主表缺口2家。

                ### 已完成联系信息收集的公司
                | 公司名称 | 国家 | 联系方式 | 可信度 | 来源 |
                | --- | --- | --- | --- | --- |
                | Suples.cl | 智利 | contacto@suples.cl | High | 官网首页 |
                | GUT Suplementos | 巴西 | contato@gutsuplementos.com.br | High | 官网联系页 |
                | New Millen | 巴西 | contato@newmillen.com.br | High | 官网联系页 |
                | DUX Nutrition | 巴西 | contato@duxnutrition.com | High | 官网联系页 |
                | Bodyaction | 巴西 | vendas@bodyaction.com.br | High | 官网页脚 |
                | Vitafor | 巴西 | contato@vitafor.com.br | High | 官网联系页 |
                | Essential Nutrition | 巴西 | sac@essentialnutrition.com.br | High | 官网联系页 |
                | Star Nutrition | 阿根廷 | form:https://starnutrition.com.ar/contacto | Medium | 官网联系页 |

                ### 待补充联系信息的公司
                | 公司名称 | 国家 | 产品证据 | 联系证据 | 待补充项 |
                | --- | --- | --- | --- | --- |
                | Black Skull USA | 巴西 | FALE CONOSCO页面存在 | 有入口页面 | 邮箱/电话 |
                | Nutremax | 阿根廷 | Aminos分类页确认 | 需访问联系页 | 邮箱/电话 |
                | Vita Plus Colombia | 哥伦比亚 | Suplementos分类页 | 需访问联系页 | 邮箱/电话 |

                ### 已剔除公司
                | 公司名称 | 剔除原因 |
                | --- | --- |
                | Soldiers Nutrition | 联系页404 |
                """;

        assertThat(strategy.countQualifiedCompanyRows(markdown)).isEqualTo(8);
        assertThat(strategy.containsConclusionBlocker(markdown)).isTrue();
    }

    @Test
    void shouldRecognizeCompanyAliasHeaderInLeadFinalTable() {
        TeamAgentRuntimeStrategy strategy = createStrategy();

        String markdown = """
                ## 已确认事实

                ### 最终主表 - High/Medium 可信度公司（10家）
                | 公司 | 国家/地区 | 公司简要信息 | 氨基酸需求信号 | 证据链接 | 联系方式 | 可信度 | 备注 |
                | --- | --- | --- | --- | --- | --- | --- | --- |
                | Bodyaction | 巴西 | 补剂品牌 | /aminoacidos分类页可访问 | https://www.bodyaction.com.br/aminoacidos | sac@rainha.ind.br + (19) 3828-9999 | **high** | 官方联系页公开邮箱和电话 |
                | GUT Suplementos | 巴西 | 补剂品牌 | 氨基酸产品线 | https://www.gutsuplementos.com.br | contato@gutsuplementos.com.br + (11) 3135-1765 | **high** | /contact-us页公开邮箱和电话 |
                | BNutrition | 巴西 | 补剂品牌 | 氨基酸产品线 | https://www.bnutrition.com.br | sac@bnutrition.com.br + contato@bnutrition.com.br | **high** | 官网页脚公开双邮箱 |
                | Suples Chile | 智利 | 补剂品牌 | 氨基酸产品线 | https://www2.suples.cl | contacto@suples.cl + +569 44200363 | **high** | 官网首页公开邮箱和WhatsApp |
                | Nutrazul | 智利 | 补剂品牌 | 补剂产品线 | https://www.nutrazulchile.cl/contact | contacto@nutrazulchile.cl + +569 42782889 | **high** | /contact页公开邮箱和电话 |
                | New Millen | 巴西 | 补剂品牌 | /materia-prima原料页可访问 | https://newmillen.com.br/materia-prima/ | contato@newmillen.com.br | **medium** | 官方联系页公开邮箱 |
                | Nutremax | 阿根廷 | 补剂品牌 | /categorias/aminos有BCAA、GLUTAMINA等产品 | https://www.nutremax.com.ar/categorias/aminos | nutremax@nutremax.com.ar + +54 9 341 5791467 | **high** | 联系页公开邮箱与WhatsApp |
                | Suplextreme | 智利 | 补剂品牌 | /categoria-producto/bcaa-y-aminoacidos可访问 | https://suplextreme.cl/categoria-producto/bcaa-y-aminoacidos/ | +569 79683991 | **high** | 联系页显示电话/WhatsApp |
                | Gentech | 阿根廷 | 补剂品牌 | Aminoácidos分类页可访问 | /contacto页面 | info@gentech.com.ar + +54 011 4304-6008 | **high** | 官方邮箱和电话 |
                | Neo Pro-Line | 西班牙（拉美市场） | 补剂品牌 | Aminoácidos分类页可访问 | /pagina-ejemplo联系页 | info@neoproline.com + +34 965 384 502 | **high** | 总部在西班牙，向拉美市场供货 |
                """;

        assertThat(strategy.countQualifiedCompanyRows(markdown)).isEqualTo(10);
        assertThat(strategy.countDetectedCompanyRows(markdown)).isEqualTo(10);
    }

    @Test
    void shouldIncludeRetryFeedbackInMemberPrompt() {
        TeamAgentRuntimeStrategy strategy = createStrategy();
        AgentTeamMember member = new AgentTeamMember();
        member.setRoleName("Lead Orchestrator");
        member.setRoleType("lead_agent");

        String prompt = strategy.buildMemberPrompt(
                "请整理最终交付文档。",
                member,
                1,
                null,
                Map.of(),
                "最终主表仅检测到 8 家 high/medium 公司，低于要求的 10 家，请继续补充公开线索后再交付",
                "## 上游成员已确认事实与证据\n### Contact Researcher\n- 已补齐 8 家联系方式"
        );

        assertThat(prompt).contains("上一轮质量闸门反馈");
        assertThat(prompt).contains("8 家 high/medium 公司");
        assertThat(prompt).contains("本轮必须优先解决上述缺口");
    }

    @Test
    void shouldUseCustomerDeliveryPromptForLeadWithoutWebResearchBias() {
        TeamAgentRuntimeStrategy strategy = createStrategy();
        AgentTeamMember member = new AgentTeamMember();
        member.setRoleName("内容方案主编");
        member.setRoleType("lead_agent");

        String prompt = strategy.buildMemberPrompt(
                """
                请输出最终交付文档。

                ## 当前流程上下文
                目标产物: deliveries/blogger/customer-demo.md
                目标产物标题: SchemaPlexAI 知识内容生产客户演示方案
                """,
                member,
                0,
                null,
                Map.of(),
                null,
                "## 上游成员已确认事实与证据\n- 已确认博主痛点、栏目结构和交付方向"
        );

        assertThat(prompt).contains("输出面向客户或业务负责人的最终交付 Markdown");
        assertThat(prompt).contains("优先复用上游已确认事实、流程上下文和已生成产物");
        assertThat(prompt).doesNotContain("web.fetch 直接抓取官网首页");
        assertThat(prompt).doesNotContain("仓库中未发现");
    }

    @Test
    void shouldSummarizeChildExecutionTokenUsageForTeamParent() throws Exception {
        AgentExecutionMapper executionMapper = mock(AgentExecutionMapper.class);
        AgentExecution first = new AgentExecution();
        first.setTokenInput(120L);
        first.setTokenOutput(80L);
        AgentExecution second = new AgentExecution();
        second.setTokenInput(30L);
        second.setTokenOutput(null);
        when(executionMapper.selectList(any())).thenReturn(List.of(first, second));
        TeamAgentRuntimeStrategy strategy = createStrategy(executionMapper);

        Method method = TeamAgentRuntimeStrategy.class.getDeclaredMethod("summarizeTeamTokenUsage", String.class);
        method.setAccessible(true);
        Object summary = method.invoke(strategy, "parent-exec-1");

        Method inputTokens = summary.getClass().getDeclaredMethod("inputTokens");
        Method outputTokens = summary.getClass().getDeclaredMethod("outputTokens");
        inputTokens.setAccessible(true);
        outputTokens.setAccessible(true);
        assertThat(inputTokens.invoke(summary)).isEqualTo(150L);
        assertThat(outputTokens.invoke(summary)).isEqualTo(80L);
    }

    private TeamAgentRuntimeStrategy createStrategy() {
        return createStrategy(mock(AgentExecutionMapper.class));
    }

    private TeamAgentRuntimeStrategy createStrategy(AgentExecutionMapper agentExecutionMapper) {
        return new TeamAgentRuntimeStrategy(
                agentExecutionMapper,
                mock(AgentTeamMemberMapper.class),
                mock(AgentTeamMemberToolBindingMapper.class),
                mock(BuiltinToolMapper.class),
                mock(AgentTeamMemberContextBindingMapper.class),
                mock(ContextItemMapper.class),
                mock(AgentExecutionEngine.class),
                mock(AgentLogService.class),
                mock(AgentLoopQualityChecker.class),
                mock(ExecutionEventStreamService.class),
                mock(AgentContextPublisher.class),
                mock(DataSource.class)
        );
    }
}
