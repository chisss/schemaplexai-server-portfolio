package com.schemaplexai.service.agent.runtime.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.util.HashUtils;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import com.schemaplexai.common.enums.AgentExecutionEventTypeEnum;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.TeamMemberRoleTypeEnum;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberContextBindingMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberToolBindingMapper;
import com.schemaplexai.dao.mapper.BuiltinToolMapper;
import com.schemaplexai.dao.mapper.ContextItemMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AgentTeamMember;
import com.schemaplexai.model.entity.AgentTeamMemberToolBinding;
import com.schemaplexai.service.agent.execution.AgentAcceptance;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.agent.execution.AgentExecutionEvent;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.execution.AgentLoopQualityChecker;
import com.schemaplexai.service.agent.execution.AgentLogService;
import com.schemaplexai.service.agent.execution.TeamExecutionBudget;
import com.schemaplexai.service.agent.execution.ExecutionEventStreamService;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.execution.SandboxPolicyResolver;
import com.schemaplexai.service.agent.execution.steering.ExecutionSteeringService;
import com.schemaplexai.service.desktop.DesktopAttachmentService;
import com.schemaplexai.service.desktop.DesktopExecutionTarget;
import com.schemaplexai.service.memory.CompositeChatMemoryStore;
import com.schemaplexai.service.memory.PostgresChatMemoryStore.ArchivedMessage;
import com.schemaplexai.service.mq.AgentContextPublisher;
import com.schemaplexai.service.user.impl.UserMemoryExtractionService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.bsc.langgraph4j.GraphRunnerException;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TeamAgentRuntimeStrategyTest {

    @ParameterizedTest
    @ValueSource(strings = {"web-history", "desktop-history", "desktop-fresh", "desktop-normalized", "desktop-spec"})
    void shouldPropagateCallerIdentityAndVerificationOwnerToTeamMemberGraph(String scenario) throws Exception {
        boolean verifiedDesktop = scenario.startsWith("desktop-");
        boolean previousTurns = scenario.endsWith("-history");
        var executions = mock(AgentExecutionMapper.class);
        var members = mock(AgentTeamMemberMapper.class);
        var memberTools = mock(AgentTeamMemberToolBindingMapper.class);
        var engine = mock(AgentExecutionEngine.class);
        var logs = mock(AgentLogService.class);
        var chatMemory = mock(CompositeChatMemoryStore.class);
        var userMemory = mock(UserMemoryExtractionService.class);
        var events = mock(ExecutionEventStreamService.class);
        var strategy = new TeamAgentRuntimeStrategy(executions, members,
                memberTools, mock(BuiltinToolMapper.class),
                mock(AgentTeamMemberContextBindingMapper.class), mock(ContextItemMapper.class), engine, logs,
                mock(AgentLoopQualityChecker.class), events,
                mock(AgentContextPublisher.class), mock(TeamCheckpointStore.class), mock(DesktopAttachmentService.class),
                mock(SandboxPolicyResolver.class), mock(ExecutionSteeringService.class),
                new DefaultTeamQualityDetector(), new TeamConversationArchive(chatMemory, userMemory, new ObjectMapper()),
                mock(TeamChildApprovalService.class), mock(TeamExecutionBudget.class), Runnable::run);

        var agent = new Agent();
        agent.setId("office-team");
        agent.setName("办公支持团队");
        var lead = new AgentTeamMember();
        lead.setId("team-lead");
        lead.setAgentId(agent.getId());
        lead.setRoleName("办公协调员");
        lead.setRoleType("lead_agent");
        var execution = new AgentExecution();
        execution.setId("office-run");
        execution.setTenantId("office-tenant");
        execution.setCreatedBy("employee-1");
        execution.setAgentId(agent.getId());
        execution.setAiModel("test-model");
        String original = "desktop-normalized".equals(scenario) ? "生成办公搬迁清单\r\n核对地址" : "生成办公搬迁清单";
        execution.setInputPrompt(original);
        if ("desktop-spec".equals(scenario)) execution.setSpecId("spec-1");
        execution.setConversationId("office-conversation");
        var context = AgentExecutionContext.builder()
                .agentId(agent.getId()).executionId(execution.getId()).tenantId(execution.getTenantId())
                .inputPrompt(execution.getInputPrompt()).build();
        if (verifiedDesktop) {
            context.setInputContext(Map.of(DesktopExecutionTarget.DEVICE_CONTEXT_KEY, "device-1",
                    DesktopExecutionTarget.WORKSPACE_CONTEXT_KEY, "workspace-1"));
            context.setDesktopVerificationCommand("mvn clean package");
            when(memberTools.selectList(any())).thenReturn(List.of(verificationTeamTool(lead, "sys.bash")));
            when(logs.hasCurrentDesktopVerification(context)).thenReturn(true);
        }

        when(members.selectList(any())).thenReturn(List.of(lead));
        when(logs.tryTransitionExecutionStatus(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        if (previousTurns) {
            when(chatMemory.loadWindow("office-conversation", "office-run", 6)).thenReturn(
                    List.of(UserMessage.from("搬迁目的地是松柏园"), AiMessage.from("已记录：松柏园。")));
        }
        when(logs.createTeamChildWhileParentRunning(any(AgentExecution.class))).thenAnswer(invocation -> {
            invocation.<AgentExecution>getArgument(0).setId("child-1");
            return true;
        });
        Thread caller = Thread.currentThread();
        var graphIdentity = new AtomicReference<List<String>>();
        when(executions.selectById(execution.getId())).thenAnswer(invocation -> {
            if (Thread.currentThread() != caller) {
                graphIdentity.set(Arrays.asList(SecurityUtil.getCurrentTenantId(),
                        SecurityUtil.getCurrentUserId(), SecurityUtil.getCurrentUsername()));
            }
            return execution;
        });
        when(engine.execute(any(AgentExecutionContext.class))).thenReturn(CompletableFuture.completedFuture(
                AgentExecutionResult.builder().status("completed").outputResult("搬迁清单已完成").build()));

        SecurityUtil.setCurrentTenantId("office-tenant");
        SecurityUtil.setCurrentUserId("employee-1");
        SecurityUtil.setCurrentUsername("office-operator");
        try {
            var result = strategy.execute(agent, execution, context).get(5, TimeUnit.SECONDS);

            assertThat(graphIdentity.get()).containsExactly("office-tenant", "employee-1", "office-operator");
            assertThat(result.getStatus()).isEqualTo("completed");
            assertThat(result.getOutputResult()).contains("搬迁清单已完成");
            var child = ArgumentCaptor.forClass(AgentExecutionContext.class);
            verify(engine).execute(child.capture());
            assertThat(child.getValue().getInputPrompt()).contains("生成办公搬迁清单");
            if (previousTurns) assertThat(child.getValue().getInputPrompt()).contains("松柏园");
            if ("desktop-fresh".equals(scenario)) {
                var projection = child.getValue().getTeamTaskContextProjection();
                assertThat(projection).isNotNull();
                assertThat(projection.executionId()).isEqualTo("child-1");
                assertThat(projection.memberId()).isEqualTo(lead.getId());
                assertThat(projection.memberPrompt()).isEqualTo(child.getValue().getInputPrompt());
            } else {
                assertThat(child.getValue().getTeamTaskContextProjection()).isNull();
            }
            assertThat(child.getValue().getMaxRounds()).isEqualTo(verifiedDesktop ? 12 : 4);
            if (verifiedDesktop) {
                assertThat(child.getValue().getInputPrompt()).contains("你负责最终验收");
                verify(logs).hasCurrentDesktopVerification(context);
            }
            verify(chatMemory).appendMessages("office-conversation", "office-run", List.of(
                    new ArchivedMessage(UserMessage.from(original), original),
                    new ArchivedMessage(AiMessage.from(result.getOutputResult()), null)));
            var completedOrder = inOrder(chatMemory, userMemory, events);
            completedOrder.verify(chatMemory).appendMessages(eq("office-conversation"), eq("office-run"), any());
            completedOrder.verify(userMemory).saveAfterCompletion(eq("office-tenant"), eq("employee-1"), eq("office-team"), isNull(),
                    eq("office-run"), eq("office-conversation"), eq(false), isNull(), any());
            completedOrder.verify(events).publish(argThat(event ->
                    AgentExecutionEventTypeEnum.COMPLETED.getCode().equals(event.getEventType())));
            assertThat(SecurityUtil.getCurrentTenantId()).isEqualTo("office-tenant");
        } finally {
            SecurityUtil.clear();
        }
    }

    @Test
    void shouldOnlyArchiveTheWinningTransitionAndSkipPriorTurnsForTemporaryChat() {
        var store = mock(CompositeChatMemoryStore.class);
        var archive = new TeamConversationArchive(store, mock(UserMemoryExtractionService.class), new ObjectMapper());
        var execution = new AgentExecution();
        execution.setId("run-1");
        execution.setConversationId("conversation-1");
        execution.setInputPrompt("核对预算");

        assertThat(archive.appendOnTransition(execution, "已核对", () -> true)).isTrue();
        assertThat(archive.appendOnTransition(execution, "重复完成", () -> false)).isFalse();
        assertThat(archive.previousTurns(execution, true)).isEmpty();
        verify(store).appendMessages("conversation-1", "run-1", List.of(
                new ArchivedMessage(UserMessage.from("核对预算"), "核对预算"),
                new ArchivedMessage(AiMessage.from("已核对"), null)));
        verifyNoMoreInteractions(store);
    }

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
    void shouldMakeTeamDeliveryFormatRespectSingleValueRequests() {
        TeamAgentRuntimeStrategy strategy = createStrategy();
        AgentTeamMember member = new AgentTeamMember();
        member.setRoleName("领队");
        member.setRoleType("lead_agent");

        String prompt = strategy.buildMemberPrompt("只回答发布代号，缺失时输出 UNKNOWN。", member, 0, null,
                Map.of(), null, "上游查到了另一条不相关的记录");

        assertThat(prompt).contains("只返回该值", "用户未指定格式且需要交付文档时", "仅在交付需要时引用证据",
                "不列出无关的记忆或上游证据", "用户指定了缺失值的写法时直接使用该写法");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldGuideVerifiedDesktopMembersUsingTheirShellBinding(boolean hasDesktopShell) {
        var bindingMapper = mock(AgentTeamMemberToolBindingMapper.class);
        var strategy = createStrategy(mock(AgentExecutionMapper.class), mock(AgentTeamMemberMapper.class),
                mock(AgentLogService.class), mock(ExecutionEventStreamService.class), Runnable::run, bindingMapper);
        var member = new AgentTeamMember();
        member.setId("member-1");
        member.setRoleName("测试员");
        member.setRoleType("member");
        var binding = new AgentTeamMemberToolBinding();
        binding.setSourceType(hasDesktopShell ? "builtin" : "mcp");
        binding.setToolCode("sys.bash");
        binding.setEnabled(true);
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        var agent = new Agent();
        agent.setId("agent-1");
        var parent = new AgentExecution();
        parent.setId("team-1");
        parent.setTenantId("tenant-1");
        var child = new AgentExecution();
        child.setId("child-1");
        var sandbox = SandboxPolicy.builder().agentId(agent.getId()).build();
        var context = AgentExecutionContext.builder()
                .inputPrompt("在导入的目录完成文件交付和验证")
                .inputContext(Map.of(DesktopExecutionTarget.DEVICE_CONTEXT_KEY, "device-1",
                        DesktopExecutionTarget.WORKSPACE_CONTEXT_KEY, "workspace-1"))
                .desktopVerificationCommand("mvn clean package").sandboxPolicy(sandbox).build();

        var desktop = strategy.buildChildExecutionContext(agent, parent, context, member, 0, null,
                Map.of(), null, "已生成 README.md", child, null);

        assertThat(desktop.getInputPrompt())
                .contains("成员按配置顺序执行", "实际文件或工具回执", "已生成 README.md", "尚缺或失败的验收项")
                .doesNotContain("候选公司", "联系人", "mvn clean package");
        assertThat(desktop.getDesktopVerificationCommand()).isEqualTo("mvn clean package");
        assertThat(desktop.isDesktopVerificationDelegated()).isFalse();
        assertThat(desktop.getSandboxPolicy()).isSameAs(sandbox);
        if (hasDesktopShell) {
            assertThat(desktop.getInputPrompt()).contains("执行最终验收命令并报告退出码");
        } else {
            assertThat(desktop.getInputPrompt()).contains("将需要 Shell 的检查交给具备本机 Shell 的成员");
        }

        context.setInputContext(Map.of());
        var web = strategy.buildChildExecutionContext(agent, parent, context, member, 0, null,
                Map.of(), null, "已生成 README.md", child, null);
        assertThat(web.getInputPrompt()).contains("候选公司", "联系人").doesNotContain("成员按配置顺序执行");
    }

    @ParameterizedTest
    @ValueSource(strings = {"lead", "tester", "inherited"})
    void shouldSelectLastExplicitShellMemberAndRespectParentRoundLimit(String scenario) {
        var bindingMapper = mock(AgentTeamMemberToolBindingMapper.class);
        var strategy = createStrategy(mock(AgentExecutionMapper.class), mock(AgentTeamMemberMapper.class),
                mock(AgentLogService.class), mock(ExecutionEventStreamService.class), Runnable::run, bindingMapper);
        var lead = verificationTeamMember("lead", "负责人", "lead_agent");
        var coder = verificationTeamMember("coder", "编码员", "member");
        var tester = verificationTeamMember("tester", "测试员", "member");
        var bindings = List.of(
                verificationTeamTool(lead, "lead".equals(scenario) ? "sys.bash" : "sys.read"),
                verificationTeamTool(coder, "sys.bash"),
                verificationTeamTool(tester, "sys.bash"));
        when(bindingMapper.selectList(any())).thenReturn(
                "inherited".equals(scenario) ? bindings.subList(1, bindings.size()) : bindings);
        var agent = new Agent();
        agent.setId("agent-1");
        var parent = new AgentExecution();
        parent.setId("team-1");
        parent.setTenantId("tenant-1");
        var child = new AgentExecution();
        child.setId("child-1");
        var context = AgentExecutionContext.builder()
                .inputPrompt("完成构建和验证")
                .inputContext(Map.of(DesktopExecutionTarget.DEVICE_CONTEXT_KEY, "device-1",
                        DesktopExecutionTarget.WORKSPACE_CONTEXT_KEY, "workspace-1"))
                .desktopVerificationCommand("mvn clean package").maxRounds(10).build();

        var initialState = strategy.buildInitialState(parent, context, List.of(lead, coder, tester));
        if ("inherited".equals(scenario)) {
            assertThat(initialState).doesNotContainKey(TeamAgentRuntimeStrategy.DESKTOP_VERIFICATION_OWNER_ID);
            return;
        }
        var owner = "lead".equals(scenario) ? lead : tester;
        assertThat(initialState).containsEntry(TeamAgentRuntimeStrategy.DESKTOP_VERIFICATION_OWNER_ID, owner.getId());
        var ownerContext = strategy.buildChildExecutionContext(agent, parent, context, owner, 0, null,
                Map.of(), null, null, child, owner);
        var coderContext = strategy.buildChildExecutionContext(agent, parent, context, coder, 0, null,
                Map.of(), null, null, child, owner);

        assertThat(ownerContext).satisfies(verifier -> {
            assertThat(verifier.getMaxRounds()).isEqualTo(10);
            assertThat(verifier.getMaxToolCallsPerRound()).isEqualTo(8);
            assertThat(verifier.getMaxMessages()).isEqualTo(80);
            assertThat(verifier.isDesktopVerificationDelegated()).isFalse();
            assertThat(verifier.isTeamDesktopVerificationOwner()).isTrue();
            assertThat(verifier.getInputPrompt()).contains("预留一轮独立执行配置的最终验收命令", "把实际验收结果放在输出开头",
                    "优先复用上游有效产物", "在它之前完成 CLI 等产物检查",
                    "只有该证据已失效或要求复测时才重新执行", "使用最终 Maven 回执中已核实的测试汇总");
        });
        assertThat(coderContext).satisfies(contributor -> {
            assertThat(contributor.getMaxRounds()).isEqualTo(8);
            assertThat(contributor.getMaxToolCallsPerRound()).isEqualTo(4);
            assertThat(contributor.isDesktopVerificationDelegated()).isTrue();
            assertThat(contributor.isTeamDesktopVerificationOwner()).isFalse();
            assertThat(contributor.getDesktopVerificationCommand()).isEqualTo("mvn clean package");
            assertThat(contributor.getInputPrompt()).contains("最终验收由 " + owner.getRoleName() + " 完成");
        });
        var leadContext = strategy.buildChildExecutionContext(agent, parent, context, lead, 0, null,
                Map.of(), null, null, child, owner);
        assertThat(leadContext.getInputPrompt()).contains("按原任务逐项引用实际回执",
                "用户要求角色分工时，简述各角色的实际产出", "仅有成员状态或计划时，标明该项未验证");
        assertThat(leadContext.getMaxToolCallsPerRound()).isEqualTo(owner == lead ? 8 : 2);

        context.setMaxToolCallsPerRound(3);
        var capped = strategy.buildChildExecutionContext(agent, parent, context, owner, 0, null,
                Map.of(), null, null, child, owner);
        assertThat(capped.getMaxToolCallsPerRound()).isEqualTo(3);
        assertThat(capped.getMaxMessages()).isEqualTo(52);

        context.setInputContext(Map.of());
        var cloud = strategy.buildChildExecutionContext(agent, parent, context, owner, 0, null,
                Map.of(), null, null, child, owner);
        assertThat(cloud.getMaxToolCallsPerRound()).isEqualTo(owner == lead ? 2 : 3);
    }

    @Test
    void shouldKeepFinalVerificationEvidenceInDesktopLeadPromptWhenEarlierMembersHaveLongOutputs() {
        var strategy = createStrategy();
        var analyst = verificationTeamMember("analyst", "需求分析师", "member");
        var coder = verificationTeamMember("coder", "编码员", "member");
        var tester = verificationTeamMember("tester", "测试员", "member");
        var context = AgentExecutionContext.builder()
                .inputContext(Map.of(DesktopExecutionTarget.DEVICE_CONTEXT_KEY, "device-1",
                        DesktopExecutionTarget.WORKSPACE_CONTEXT_KEY, "workspace-1"))
                .desktopVerificationCommand("mvn clean package").build();
        String verificationResults = """
                最终 Maven exit 0，BUILD SUCCESS，25 项测试通过，0 失败、0 错误、0 跳过。
                JAR 为 target/quote-cli.jar，CLI 正常输出 59.97，缺参 exit 1 且 stderr 有说明。
                Git 根等于授权目录，零远程。Maven Central HTTPS 返回 HTTP/2 200。
                """;
        String verificationHandoff = verificationResults
                + "\n" + "验收结果按实际回执列出。".repeat(104)
                + "\n\n" + "已确认最终网络回执为 HTTPS 200。";
        var memberResults = List.of(
                completedMemberOutput(analyst, "需求背景与方案设想。\n\n".repeat(220)),
                completedMemberOutput(coder, "编码过程和中间方案。\n\n".repeat(220)),
                completedMemberOutput(tester, verificationResults + "\n" + "测试过程细节。\n\n".repeat(220)));

        String desktopEvidence = strategy.buildLeadEvidencePrompt(memberResults, context, tester);
        String cloudEvidence = strategy.buildLeadEvidencePrompt(memberResults, AgentExecutionContext.builder().build(), tester);
        String unassignedEvidence = strategy.buildLeadEvidencePrompt(memberResults, context, null);

        assertThat(desktopEvidence).contains(verificationResults.trim()).hasSizeLessThan(2300);
        assertThat(desktopEvidence.indexOf("### 测试员")).isLessThan(desktopEvidence.indexOf("### 需求分析师"));
        assertThat(cloudEvidence).doesNotContain("HTTP/2 200").isEqualTo(unassignedEvidence);
        var lateReceipt = List.of(completedMemberOutput(tester, verificationHandoff));
        assertThat(strategy.buildLeadEvidencePrompt(lateReceipt, context, tester))
                .contains("已确认最终网络回执为 HTTPS 200。");
    }

    @Test
    void shouldHandOffActualCommandReceiptsAndDistinguishCliFromFinalBuild() {
        var store = mock(CompositeChatMemoryStore.class);
        var mapper = new ObjectMapper();
        var archive = new TeamConversationArchive(store, mock(UserMemoryExtractionService.class), mapper);
        var receipts = new ArrayList<ChatMessage>();
        receipts.addAll(deviceShellCall(mapper, "proxy", "curl -fsSI https://proxy.example/",
                0, "HTTP/1.1 200 Connection established\r\n", "", Map.of()));
        receipts.addAll(deviceShellCall(mapper, "https", "curl -fsSI --max-time 10 https://repo.maven.apache.org/maven2/",
                0, "HTTP/1.1 200 Connection established\r\n\r\nHTTP/2 200 \r\n", "", Map.of()));
        receipts.addAll(deviceShellCall(mapper, "get", "curl -fsS https://other.example/",
                0, "HTTP/2 500\r\n", "", Map.of()));
        receipts.addAll(deviceShellCall(mapper, "positive", "java -jar target/quote-cli.jar 0.005 1",
                0, "0.01\n", "NOTE: Picked up JDK_JAVA_OPTIONS: -Duser.home=/sandbox\n", Map.of()));
        receipts.addAll(deviceShellCall(mapper, "negative", "java -jar target/quote-cli.jar -5 2",
                1, "", "Error: negative price\n", Map.of()));
        receipts.addAll(deviceShellCall(mapper, "build", "mvn clean package", 0, "[INFO] BUILD SUCCESS\n", "",
                Map.of("desktopMavenModelSummary",
                        "{\"command\":\"mvn clean package\",\"tests\":50,\"failures\":0,\"errors\":0,\"skipped\":0}")));
        when(store.loadExecutionForExtraction("test-conversation", "test-execution")).thenReturn(receipts);

        assertThat(archive.desktopVerificationEvidence("test-execution", "test-conversation", "mvn clean package").lines())
                .satisfiesExactly(
                        item -> assertThat(item).contains("curl -fsSI --max-time 10 https://repo.maven.apache.org/maven2/",
                                "HTTP/2 200", "exit 0", "设备回执"),
                        item -> assertThat(item).contains("最终验收命令之前的 CLI", "0.005 1", "stdout `0.01`", "应用 stderr 为空"),
                        item -> assertThat(item).contains("最终验收命令之前的 CLI", "-5 2", "exit 1", "stdout 为空", "应用 stderr 有输出"),
                        item -> assertThat(item).contains("配置的最终验收命令 `mvn clean package`", "50 项测试，0 失败，0 错误，0 跳过"));
    }

    @Test
    void shouldDistinguishEarlierNetworkVerificationFromLaterRecheck() {
        var store = mock(CompositeChatMemoryStore.class);
        var mapper = new ObjectMapper();
        var archive = new TeamConversationArchive(store, mock(UserMemoryExtractionService.class), mapper);
        String curl = "curl --head https://repo.maven.apache.org/maven2/";
        var code = deviceShellCall(mapper, "network-first", curl, 0, "HTTP/2 200\n", "",
                Map.of("desktopCallId", "11bce843-ad43-4538-bb1a-2edcfaa82e12"));
        when(store.loadExecutionForExtraction("code-conversation", "code-execution")).thenReturn(code);
        var tester = new ArrayList<ChatMessage>();
        tester.addAll(deviceShellCall(mapper, "cli", "java -jar target/app.jar 19.99 3", 0, "59.97\n", "", Map.of()));
        tester.addAll(deviceShellCall(mapper, "network-repeat", curl, 0, "HTTP/2 200\n", "",
                Map.of("desktopCallId", "22bce843-ad43-4538-bb1a-2edcfaa82e12")));
        tester.addAll(deviceShellCall(mapper, "build", "mvn clean package", 0, "[INFO] BUILD SUCCESS\n", "", Map.of()));
        when(store.loadExecutionForExtraction("test-conversation", "test-execution")).thenReturn(tester);

        var result = archive.desktopVerificationEvidence("test-execution", "test-conversation", "mvn clean package")
                .withMissingChecksFrom(archive.desktopUpstreamEvidence("code-execution", "code-conversation"));
        assertThat(result.https()).satisfiesExactly(
                item -> assertThat(item).contains("HTTP/2 200", "11bce843"),
                item -> assertThat(item).contains("本成员已开始 CLI 后", "HTTP/2 200", "22bce843"));
    }

    @Test
    void shouldIgnoreUnmatchedOrCompressedDesktopReceiptsAndOmitCliOrderWithoutFinalBuild() {
        var store = mock(CompositeChatMemoryStore.class);
        var mapper = new ObjectMapper();
        var archive = new TeamConversationArchive(store, mock(UserMemoryExtractionService.class), mapper);
        String curl = "curl --head https://repo.maven.apache.org/maven2/";
        var valid = deviceShellCall(mapper, "valid", curl, 0, "HTTP/2 200\n", "", Map.of());
        var unmatched = deviceShellCall(mapper, "unmatched", curl, 0, "HTTP/2 403\n", "", Map.of());
        var compressed = deviceShellCall(mapper, "compressed", curl, 0, "HTTP/2 500\n", "",
                Map.of("compressionApplied", true));
        var cli = deviceShellCall(mapper, "cli", "java -jar target/quote-cli.jar 19.99 3", 0, "59.97\n", "", Map.of());
        var remote = deviceShellCall(mapper, "git", "git remote -v", 0, "", "", Map.of());
        when(store.loadExecutionForExtraction("test-conversation", "test-execution"))
                .thenReturn(List.of(valid.getFirst(), valid.getLast(), unmatched.getLast(),
                        compressed.getFirst(), compressed.getLast(), cli.getFirst(), cli.getLast(),
                        remote.getFirst(), remote.getLast()));

        assertThat(archive.desktopVerificationEvidence("test-execution", "test-conversation", "mvn clean package").lines())
                .satisfiesExactly(item -> assertThat(item).contains(curl, "HTTP/2 200"),
                        item -> assertThat(item).contains("git remote -v", "stdout 为空"));
        assertThat(archive.desktopUpstreamEvidence("test-execution", "test-conversation").lines())
                .satisfiesExactly(item -> assertThat(item).contains(curl, "HTTP/2 200"),
                        item -> assertThat(item).contains("git remote -v", "stdout 为空"),
                        item -> assertThat(item).contains("成员已执行的 CLI", "stdout `59.97`")
                                .doesNotContain("最终验收命令之前"));
    }

    @Test
    void shouldInvalidateArtifactAndGitHandoffAfterWorkspaceWriteButKeepHistoricalHttps() {
        var store = mock(CompositeChatMemoryStore.class);
        var mapper = new ObjectMapper();
        var archive = new TeamConversationArchive(store, mock(UserMemoryExtractionService.class), mapper);
        var earlier = new ArrayList<ChatMessage>();
        earlier.addAll(deviceShellCall(mapper, "network", "curl --head https://repo.maven.apache.org/maven2/",
                0, "HTTP/2 200\n", "", Map.of()));
        earlier.addAll(deviceShellCall(mapper, "cli-old", "java -jar target/quote-cli.jar 19.99 3",
                0, "59.97\n", "", Map.of()));
        earlier.addAll(deviceShellCall(mapper, "git-old", "git remote -v", 0, "", "", Map.of()));
        when(store.loadExecutionForExtraction("coder-conversation", "coder-execution")).thenReturn(earlier);
        var upstream = archive.desktopUpstreamEvidence("coder-execution", "coder-conversation");
        assertThat(upstream.lines()).hasSize(3);

        var verifier = new ArrayList<ChatMessage>(earlier);
        verifier.add(ToolExecutionResultMessage.builder().id("write").toolName("sys_write").isError(false)
                .text("已保存 pom.xml").build());
        verifier.addAll(deviceShellCall(mapper, "cli-new", "java -jar target/quote-cli.jar 0.005 1",
                0, "0.01\n", "", Map.of()));
        verifier.addAll(deviceShellCall(mapper, "build", "mvn clean package", 0, "[INFO] BUILD SUCCESS\n", "", Map.of()));
        when(store.loadExecutionForExtraction("test-conversation", "test-execution")).thenReturn(verifier);

        var actual = archive.desktopVerificationEvidence("test-execution", "test-conversation", "mvn clean package");
        assertThat(actual.withMissingChecksFrom(upstream).lines()).satisfiesExactly(
                item -> assertThat(item).contains("HTTP/2 200"),
                item -> assertThat(item).contains("最终验收命令之前的 CLI", "0.005 1", "0.01"),
                item -> assertThat(item).contains("配置的最终验收命令", "mvn clean package"));
        assertThat(archive.desktopUpstreamEvidence("test-execution", "test-conversation").lines())
                .singleElement().satisfies(item -> assertThat(item).contains("HTTP/2 200"));
    }

    @Test
    void shouldMergeObservedCliAcrossGitOnlyChangesAndQuoteVerifiedReadmeCommands() {
        var store = mock(CompositeChatMemoryStore.class);
        var mapper = new ObjectMapper();
        var archive = new TeamConversationArchive(store, mock(UserMemoryExtractionService.class), mapper);
        var upstream = deviceShellCall(mapper, "original", "java -jar target/app.jar 19.99 3",
                0, "59.97\n", "", Map.of());
        when(store.loadExecutionForExtraction("coder-conversation", "coder-execution")).thenReturn(upstream);

        var verifier = new ArrayList<ChatMessage>();
        verifier.addAll(deviceShellCall(mapper, "ls", "ls -l target/app.jar", 0, "app.jar", "", Map.of()));
        verifier.addAll(deviceShellCall(mapper, "init", "git init", 0, "", "", Map.of()));
        verifier.addAll(deviceShellCall(mapper, "git", "git status --porcelain --untracked-files=all",
                0, "", "", Map.of()));
        verifier.addAll(deviceShellCall(mapper, "bad-number", "java -jar target/app.jar 19.99 abc",
                1, "", "Invalid quantity", Map.of()));
        String document = "构建使用 `mvn clean package`\njava -jar target/app.jar <unit-price> <quantity>";
        verifier.addAll(deviceReadmeCall(mapper, "readme", document, true));
        verifier.addAll(deviceReadmeCall(mapper, "forged", "mvn clean install\njava -jar target/other.jar 4", false));
        verifier.addAll(deviceShellCall(mapper, "count", "grep -c @Test src/AppTest.java",
                0, "10\n", "", Map.of()));
        verifier.addAll(deviceShellCall(mapper, "build", "mvn clean package", 0, "[INFO] BUILD SUCCESS\n", "", Map.of()));
        when(store.loadExecutionForExtraction("test-conversation", "test-execution")).thenReturn(verifier);

        var actual = archive.desktopVerificationEvidence("test-execution", "test-conversation", "mvn clean package")
                .withMissingChecksFrom(archive.desktopUpstreamEvidence("coder-execution", "coder-conversation"));
        assertThat(actual.lines()).satisfiesExactly(
                item -> assertThat(item).contains("最终验收命令之前的 CLI", "19.99 3", "59.97"),
                item -> assertThat(item).contains("最终验收命令之前的 CLI", "19.99 abc", "exit 1", "stdout 为空"),
                item -> assertThat(item).contains("README.md", "构建 `mvn clean package`",
                        "运行 `java -jar target/app.jar <unit-price> <quantity>`").doesNotContain("install", "other.jar"),
                item -> assertThat(item).contains("配置的最终验收命令", "mvn clean package"));
    }

    private List<ChatMessage> deviceReadmeCall(ObjectMapper mapper, String id, String document, boolean validHash) {
        var args = mapper.createObjectNode().put("path", "README.md");
        var envelope = mapper.createObjectNode().put("callId", id).put("toolCode", "sys.read")
                .put("success", true).put("result", document);
        String body = envelope.toString();
        String signature = mapper.createArrayNode().add(args).add(document).toString();
        return List.of(AiMessage.from(List.of(ToolExecutionRequest.builder().id(id).name("sys_read").arguments(args.toString()).build())),
                ToolExecutionResultMessage.builder().id(id).toolName("sys_read").isError(false)
                        .attributes(Map.of("desktopCallId", "1bce843a-d43a-4538-bb1a-2edcfaa82e12",
                                "compressionApplied", false, "rawChars", body.length(), "compressedChars", body.length(),
                                "desktopReadEvidenceHash", validHash
                                        ? HashUtils.sha256Hex(signature.getBytes(StandardCharsets.UTF_8)) : "invalid"))
                        .text("请求参数: " + args + "\n工具结果: " + body).build());
    }

    private List<ChatMessage> deviceShellCall(ObjectMapper mapper, String id, String command, int exitCode,
                                              String stdout, String stderr, Map<String, Object> extraMetadata) {
        String args = mapper.createObjectNode().put("command", command).toString();
        var shell = mapper.createObjectNode().put("exitCode", exitCode).put("stdout", stdout).put("stderr", stderr)
                .put("stdoutTruncated", false).put("stderrTruncated", false);
        var envelope = mapper.createObjectNode().put("callId", id).put("toolCode", "sys.bash")
                .put("success", exitCode == 0).put("result", shell.toString());
        if (exitCode != 0) envelope.put("errorMessage", "命令退出码：" + exitCode);
        String body = envelope.toString();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("desktopCallId", "1bce843a-d43a-4538-bb1a-2edcfaa82e12");
        metadata.put("compressionApplied", false);
        metadata.put("rawChars", body.length());
        metadata.put("compressedChars", body.length());
        metadata.putAll(extraMetadata);
        return List.of(AiMessage.from(List.of(ToolExecutionRequest.builder().id(id).name("sys_bash").arguments(args).build())),
                ToolExecutionResultMessage.builder().id(id).toolName("sys_bash").isError(exitCode != 0)
                        .attributes(metadata).text("请求参数: " + args + "\n工具结果: " + body).build());
    }

    @Test
    void shouldKeepDistinctMemberEvidenceAndUserContractInDesktopVerifierHandoff() {
        var archive = mock(TeamConversationArchive.class);
        var strategy = createStrategy(mock(AgentExecutionMapper.class), mock(AgentTeamMemberMapper.class),
                mock(AgentLogService.class), mock(ExecutionEventStreamService.class), Runnable::run,
                mock(AgentTeamMemberToolBindingMapper.class), archive);
        var tester = verificationTeamMember("tester", "测试工程师", "member");
        var coder = verificationTeamMember("coder", "编码工程师", "member");
        var completed = new LinkedHashMap<>(completedMemberOutput(coder, "已写入项目文件"));
        completed.put(TeamGraphConstants.RESULT_EXECUTION_ID, "coder-run");
        completed.put(TeamGraphConstants.RESULT_CONVERSATION_ID, "coder-conversation");
        String http = "HTTPS 实测返回 HTTP/2 200（设备回执 network-1）";
        String git = "Git 根与工作区一致（设备回执 git-1）";
        String memberEvidence = "- " + http + "\n- " + http + "，建议保留代理回退。\n- 需兼容历史输入格式。";
        var receipts = new TeamConversationArchive.DesktopEvidence(List.of(http), List.of(git), List.of(),
                "", "", false, false);
        when(archive.desktopUpstreamEvidence("coder-run", "coder-conversation"))
                .thenReturn(receipts, receipts, TeamConversationArchive.DesktopEvidence.EMPTY);
        String original = "保留旧输入。\n先检查 HTTPS，再测试 CLI。构建须最后执行。";

        String evidence = strategy.buildDesktopVerificationHandoff(List.of(completed), memberEvidence, true);
        assertThat(evidence.lines().filter(line -> line.equals("- " + http)).count()).isEqualTo(1);
        assertThat(evidence.lines().toList()).containsSubsequence("- " + http, "- " + git,
                "- " + http + "，建议保留代理回退。", "- 需兼容历史输入格式。");
        assertThat(strategy.buildDesktopVerifierPrompt(original, tester, evidence))
                .contains("成员增补的测试变体仅为建议", "Git、HTTP 和 CLI 结论依据实际设备回执",
                        "尚缺或失败", "预留一轮独立执行配置的最终验收命令", "README", "测试汇总", evidence)
                .endsWith(original);
        assertThat(strategy.buildDesktopVerificationHandoff(List.of(completed), memberEvidence, false))
                .contains("若相关输入及产物无变化", memberEvidence);
        assertThat(strategy.buildDesktopVerificationHandoff(List.of(completed), memberEvidence, true))
                .isEqualTo(memberEvidence);
    }

    @Test
    void shouldLetDesktopLeadReuseVerifierHandoffOnlyWithCurrentServerReceipt() {
        var logs = mock(AgentLogService.class);
        var strategy = createStrategy(mock(AgentExecutionMapper.class), mock(AgentTeamMemberMapper.class),
                logs, mock(ExecutionEventStreamService.class), Runnable::run);
        var lead = verificationTeamMember("lead", "负责人", "lead_agent");
        var tester = verificationTeamMember("tester", "测试员", "member");
        var context = AgentExecutionContext.builder().executionId("team-1").tenantId("tenant-1")
                .inputContext(Map.of(DesktopExecutionTarget.DEVICE_CONTEXT_KEY, "device-1",
                        DesktopExecutionTarget.WORKSPACE_CONTEXT_KEY, "workspace-1"))
                .desktopVerificationCommand("mvn clean package").build();
        var completed = List.of(completedMemberOutput(tester, "22 项通过，HTTP/2 200。"));
        String ordinaryEvidence = strategy.buildLeadEvidencePrompt(completed, context, tester);
        when(logs.hasCurrentDesktopVerification(context)).thenReturn(false, true);

        assertThat(strategy.buildLeadDeliveryEvidence(completed, context, lead, tester)).isEqualTo(ordinaryEvidence);
        assertThat(strategy.buildLeadDeliveryEvidence(completed, context, lead, tester))
                .contains("存在有效成功回执", "避免重复读取或检查已有证据的项", "先指出具体缺口",
                        "仍缺必要证据", "22 项通过，HTTP/2 200。", "列表次序不代表执行先后",
                        "不根据单元测试推断外部 CLI 结果", "README 有实际内容回执时");

        var fallback = new LinkedHashMap<>(completed.getFirst());
        fallback.put(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode());
        for (var noVerifiedHandoff : List.<List<Map<String, Object>>>of(List.of(), List.of(fallback),
                List.of(completedMemberOutput(tester, " ")))) {
            assertThat(strategy.buildLeadDeliveryEvidence(noVerifiedHandoff, context, lead, tester))
                    .doesNotContain("存在有效成功回执");
        }
        assertThat(strategy.buildLeadDeliveryEvidence(completed, context, tester, tester))
                .isEqualTo(ordinaryEvidence);
        assertThat(strategy.buildLeadDeliveryEvidence(completed, context, lead, null))
                .doesNotContain("存在有效成功回执");
        verify(logs, times(2)).hasCurrentDesktopVerification(context);
    }

    private AgentTeamMember verificationTeamMember(String id, String name, String type) {
        var member = new AgentTeamMember();
        member.setId(id);
        member.setRoleName(name);
        member.setRoleType(type);
        return member;
    }

    private AgentTeamMemberToolBinding verificationTeamTool(AgentTeamMember member, String toolCode) {
        var binding = new AgentTeamMemberToolBinding();
        binding.setMemberId(member.getId());
        binding.setSourceType("builtin");
        binding.setEnabled(true);
        binding.setToolCode(toolCode);
        return binding;
    }

    private Map<String, Object> completedMemberOutput(AgentTeamMember member, String output) {
        return Map.of(TeamGraphConstants.RESULT_MEMBER_ID, member.getId(),
                TeamGraphConstants.RESULT_ROLE_NAME, member.getRoleName(),
                TeamGraphConstants.RESULT_ROLE_TYPE, member.getRoleType(),
                TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.COMPLETED.getCode(),
                TeamGraphConstants.RESULT_OUTPUT_RESULT, output);
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

    @Test
    void shouldMarkRuleBasedFallbackAsCompletedWithFallback() {
        TeamAgentRuntimeStrategy strategy = createStrategy();

        Map<String, Object> update = strategy.aggregate(List.of(Map.of(
                TeamGraphConstants.RESULT_ROLE_NAME, "市场分析师",
                TeamGraphConstants.RESULT_ROLE_TYPE, "member",
                TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.FAILED.getCode(),
                TeamGraphConstants.RESULT_ERROR_MESSAGE, "SocketException: connection reset"
        )), 0);

        assertThat(update.get(TeamGraphConstants.STATE_FINAL_STATUS))
                .isEqualTo(AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode());
        assertThat(update.get(TeamGraphConstants.STATE_FINAL_OUTPUT))
                .asString()
                .contains("规则化兜底");
    }

    @Test
    void shouldMarkPartialTeamOutputDegradedAndExcludeFailedEvidence() {
        TeamAgentRuntimeStrategy strategy = createStrategy();
        var update = strategy.aggregate(List.of(
                Map.of(TeamGraphConstants.RESULT_ROLE_NAME, "领队", TeamGraphConstants.RESULT_ROLE_TYPE, TeamMemberRoleTypeEnum.LEAD_AGENT.getCode(),
                        TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.COMPLETED.getCode(),
                        TeamGraphConstants.RESULT_OUTPUT_RESULT, "可用交付"),
                Map.of(TeamGraphConstants.RESULT_ROLE_NAME, "编码", TeamGraphConstants.RESULT_ROLE_TYPE, "member",
                        TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.COMPLETED.getCode(),
                        TeamGraphConstants.RESULT_OUTPUT_RESULT, "可信产物"),
                Map.of(TeamGraphConstants.RESULT_ROLE_NAME, "测试", TeamGraphConstants.RESULT_ROLE_TYPE, "member",
                        TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.FAILED.getCode(),
                        TeamGraphConstants.RESULT_OUTPUT_RESULT, "失败前的未确认文本")
        ), 0);

        assertThat(update.get(TeamGraphConstants.STATE_FINAL_STATUS))
                .isEqualTo(AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode());
        assertThat(update.get(TeamGraphConstants.STATE_FINAL_OUTPUT)).isEqualTo("可用交付");
        assertThat(update.get(TeamGraphConstants.STATE_MEMBER_EVIDENCE).toString())
                .contains("可信产物").doesNotContain("未确认文本");
        assertThat(update.get(TeamGraphConstants.STATE_AGGREGATE_WARNINGS).toString()).contains("[测试] 本轮未完成");
    }

    @Test
    void shouldNotUseOutputFromFailedTeamLeader() {
        TeamAgentRuntimeStrategy strategy = createStrategy();
        var update = strategy.aggregate(List.of(
                Map.of(TeamGraphConstants.RESULT_ROLE_NAME, "领队", TeamGraphConstants.RESULT_ROLE_TYPE, TeamMemberRoleTypeEnum.LEAD_AGENT.getCode(),
                        TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.FAILED.getCode(),
                        TeamGraphConstants.RESULT_OUTPUT_RESULT, "未验证的终稿"),
                Map.of(TeamGraphConstants.RESULT_ROLE_NAME, "编码", TeamGraphConstants.RESULT_ROLE_TYPE, "member",
                        TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.COMPLETED.getCode(),
                        TeamGraphConstants.RESULT_OUTPUT_RESULT, "可信产物")
        ), 0);

        assertThat(update.get(TeamGraphConstants.STATE_FINAL_STATUS)).isEqualTo(AgentExecutionStatusEnum.FAILED.getCode());
        assertThat(update).doesNotContainKey(TeamGraphConstants.STATE_FINAL_OUTPUT);
    }

    @Test
    void shouldPublishFallbackUsedEventWhenFinalizingExecutionLevelFallback() throws Exception {
        AgentExecutionMapper executionMapper = mock(AgentExecutionMapper.class);
        AgentLogService agentLogService = mock(AgentLogService.class);
        ExecutionEventStreamService eventStreamService = mock(ExecutionEventStreamService.class);
        when(executionMapper.selectList(any())).thenReturn(List.of());
        when(agentLogService.tryTransitionExecutionStatus(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        TeamAgentRuntimeStrategy strategy = createStrategy(executionMapper, agentLogService, eventStreamService);
        AgentExecution execution = new AgentExecution();
        execution.setId("execution-1");
        execution.setAgentId("agent-1");
        execution.setTenantId("tenant-1");
        execution.setConversationId("conversation-1");
        TeamGraphState state = new TeamGraphState(Map.of(
                TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.FAILED.getCode(),
                TeamGraphConstants.STATE_FINAL_ERROR, "SocketException: connection reset"
        ));

        AgentExecutionResult result = strategy.finalizeExecution(execution, AgentExecutionContext.builder().build(), state);

        assertThat(result.getStatus()).isEqualTo(AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode());
        verify(agentLogService).tryTransitionExecutionStatus(
                eq("execution-1"),
                eq(AgentExecutionStatusEnum.RUNNING.getCode()),
                eq(AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode()),
                eq(null),
                eq(0L),
                eq(0L),
                any()
        );
        ArgumentCaptor<AgentExecutionEvent> eventCaptor = ArgumentCaptor.forClass(AgentExecutionEvent.class);
        verify(eventStreamService).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType())
                .isEqualTo(AgentExecutionEventTypeEnum.FALLBACK_USED.getCode());
    }

    @Test
    void shouldFailTeamDeliveryWhenLeaderOutputViolatesTheRootAcceptanceContract() {
        var executions = mock(AgentExecutionMapper.class);
        var logs = mock(AgentLogService.class);
        var events = mock(ExecutionEventStreamService.class);
        var strategy = createStrategy(executions, logs, events);
        var execution = new AgentExecution();
        execution.setId("parent");
        var contract = new AgentAcceptance(Map.of("/ok", "boolean"));
        var context = AgentExecutionContext.builder().executionId("parent").acceptance(contract).build();
        when(logs.tryFinalizeAcceptance(eq("parent"), eq("failed"), any(), eq(0L), eq(0L),
                isNull(), eq(AgentAcceptance.Status.REJECTED))).thenReturn(true);
        var state = new TeamGraphState(Map.of(
                TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.COMPLETED.getCode(),
                TeamGraphConstants.STATE_FINAL_OUTPUT, "{\"ok\":\"yes\"}"));

        var result = strategy.finalizeExecution(execution, context, state);

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getErrorMessage()).contains("/ok");
        assertThat(result.getOutputResult()).isNull();
    }

    @Test
    void shouldFailTeamDeliveryWithoutCurrentDesktopVerificationReceipt() {
        AgentExecutionMapper executions = mock(AgentExecutionMapper.class);
        AgentLogService logs = mock(AgentLogService.class);
        TeamAgentRuntimeStrategy strategy = createStrategy(executions, logs, mock(ExecutionEventStreamService.class));
        AgentExecution execution = new AgentExecution();
        execution.setId("parent");
        AgentExecutionContext context = AgentExecutionContext.builder().executionId("parent")
                .desktopVerificationCommand("mvn clean package").build();
        when(logs.tryTransitionExecutionStatus(eq("parent"), eq("running"), eq("failed"),
                eq(AgentLogService.DESKTOP_VERIFICATION_REQUIRED), eq(0L), eq(0L), isNull())).thenReturn(true);
        TeamGraphState state = new TeamGraphState(Map.of(
                TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.COMPLETED.getCode(),
                TeamGraphConstants.STATE_FINAL_OUTPUT, "所有测试已通过"));

        AgentExecutionResult result = strategy.finalizeExecution(execution, context, state);

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getOutputResult()).isNull();
        verify(logs).hasCurrentDesktopVerification(context);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldDiagnoseAllMemberModelFailuresWhenDesktopVerificationIsMissing(boolean contributorCompleted) {
        var executions = mock(AgentExecutionMapper.class);
        var logs = mock(AgentLogService.class);
        var events = mock(ExecutionEventStreamService.class);
        var strategy = createStrategy(executions, logs, events);
        var execution = new AgentExecution();
        execution.setId("parent");
        var context = AgentExecutionContext.builder().executionId("parent")
                .desktopVerificationCommand("mvn clean package").build();
        String providerDetail = "AI 调用失败，所有候选模型当前处于冷却期: provider fixture detail";
        var lead = Map.<String, Object>of(
                TeamGraphConstants.RESULT_ROLE_NAME, "负责人",
                TeamGraphConstants.RESULT_ROLE_TYPE, TeamMemberRoleTypeEnum.LEAD_AGENT.getCode(),
                TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode(),
                TeamGraphConstants.RESULT_OUTPUT_RESULT, "规则化兜底草案",
                TeamGraphConstants.RESULT_ERROR_MESSAGE, providerDetail);
        var contributor = Map.<String, Object>of(
                TeamGraphConstants.RESULT_ROLE_NAME, "编码",
                TeamGraphConstants.RESULT_ROLE_TYPE, "member",
                TeamGraphConstants.RESULT_STATUS, contributorCompleted ? AgentExecutionStatusEnum.COMPLETED.getCode()
                        : AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode(),
                TeamGraphConstants.RESULT_OUTPUT_RESULT, contributorCompleted ? "成员实际产出" : "规则化兜底草案",
                TeamGraphConstants.RESULT_ERROR_MESSAGE, contributorCompleted ? "" : providerDetail);
        var memberResults = List.of(lead, contributor);
        var graphData = strategy.aggregate(memberResults, 0);
        graphData.put(TeamGraphConstants.STATE_MEMBER_RESULTS, memberResults);
        String expectedError = contributorCompleted ? AgentLogService.DESKTOP_VERIFICATION_REQUIRED
                : "Team 成员的模型通道均暂不可用，请稍后重试或联系管理员。";
        when(logs.tryTransitionExecutionStatus(eq("parent"), eq("running"), eq("failed"), any(),
                eq(0L), eq(0L), isNull())).thenReturn(true);

        var result = strategy.finalizeExecution(execution, context, new TeamGraphState(graphData));

        assertThat(result.getStatus()).isEqualTo(AgentExecutionStatusEnum.FAILED.getCode());
        assertThat(result.getErrorMessage()).isEqualTo(expectedError);
        assertThat(result.getOutputResult()).isNull();
        verify(logs).hasCurrentDesktopVerification(context);
        verify(logs).tryTransitionExecutionStatus("parent", "running", "failed", expectedError, 0L, 0L, null);
        verify(events).publish(argThat(event -> AgentExecutionEventTypeEnum.FAILED.getCode().equals(event.getEventType())
                && expectedError.equals(event.getMessage())));
    }

    @Test
    void shouldReuseCompletedMemberResultsAcrossRetry() {
        TeamAgentRuntimeStrategy strategy = createStrategy();
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put(TeamGraphConstants.RESULT_MEMBER_ID, "member-1");
        completed.put(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.COMPLETED.getCode());
        Map<String, Object> completedWithFallback = new LinkedHashMap<>();
        completedWithFallback.put(TeamGraphConstants.RESULT_MEMBER_ID, "member-2");
        completedWithFallback.put(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode());
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put(TeamGraphConstants.RESULT_MEMBER_ID, "member-3");
        failed.put(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.FAILED.getCode());

        Set<String> completedIds = strategy.resolveCompletedMemberIds(
                List.of(completed, completedWithFallback, failed));

        assertThat(completedIds).containsExactlyInAnyOrder("member-1", "member-2");
    }

    @Test
    void shouldInheritSandboxPolicyAndExecutionModeToChildContext() {
        TeamAgentRuntimeStrategy strategy = createStrategy();
        Agent agent = new Agent();
        agent.setId("agent-1");
        AgentExecution parent = new AgentExecution();
        parent.setId("parent-1");
        parent.setTenantId("tenant-1");
        parent.setAgentId("agent-1");
        SandboxPolicy policy = SandboxPolicy.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .maxExecutionMinutes(45)
                .build();
        AgentExecutionContext parentContext = AgentExecutionContext.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("parent-1")
                .userId("user-1")
                .projectId("project-1")
                .inputPrompt("父任务需求")
                .inputContext(Map.of("desktopDeviceId", "device-1", "desktopWorkspaceId", "workspace-1"))
                .executionMode("plan")
                .sandboxPolicy(policy)
                .build();
        AgentTeamMember member = new AgentTeamMember();
        member.setId("member-1");
        member.setRoleName("研究员");
        member.setRoleType("contributor");
        AgentExecution child = new AgentExecution();
        child.setId("child-1");
        child.setConversationId("team:parent-1:member-1");

        AgentExecutionContext childContext = strategy.buildChildExecutionContext(
                agent, parent, parentContext, member, 0, null, null, null, null, child, null);

        assertThat(childContext.getSandboxPolicy()).isSameAs(policy);
        assertThat(childContext.getExecutionMode()).isEqualTo("plan");
        assertThat(childContext.getUserId()).isEqualTo("user-1");
        assertThat(childContext.getProjectId()).isEqualTo("project-1");
        assertThat(childContext.getInputContext()).isEqualTo(parentContext.getInputContext());

        parentContext.setTemporaryChat(true);
        parentContext.setMemoryWriteEnabled(false);
        AgentExecutionContext temporaryChild = strategy.buildChildExecutionContext(
                agent, parent, parentContext, member, 0, null, null, null, null, child, null);
        assertThat(temporaryChild.isTemporaryChat()).isTrue();
        assertThat(temporaryChild.getMemoryWriteEnabled()).isFalse();

        parentContext.setTemporaryChat(false);
        parentContext.setMemoryWriteEnabled(true);
        child.setTemporaryChat(true);
        child.setMemoryWriteEnabled(false);
        AgentExecutionContext restrictedChild = strategy.buildChildExecutionContext(
                agent, parent, parentContext, member, 0, null, null, null, null, child, null);
        assertThat(restrictedChild.isTemporaryChat()).isTrue();
        assertThat(restrictedChild.getMemoryWriteEnabled()).isFalse();
    }

    @Test
    void shouldShortCircuitAggregateWhenParentExecutionStopped() {
        TeamAgentRuntimeStrategy strategy = createStrategy();

        Map<String, Object> update = strategy.aggregate(
                List.of(Map.of(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.STOPPED.getCode())), 0);

        assertThat(update.get(TeamGraphConstants.STATE_FINAL_STATUS))
                .isEqualTo(AgentExecutionStatusEnum.STOPPED.getCode());
    }

    @Test
    void shouldKeepStoppedTerminalStateInFinalizeExecution() {
        AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
        AgentLogService agentLogService = mock(AgentLogService.class);
        ExecutionEventStreamService eventStreamService = mock(ExecutionEventStreamService.class);
        TeamAgentRuntimeStrategy strategy = createStrategy(agentExecutionMapper, agentLogService, eventStreamService);

        AgentExecution execution = new AgentExecution();
        execution.setId("parent-1");
        execution.setAgentId("agent-1");
        execution.setTenantId("tenant-1");
        execution.setConversationId("conv-1");
        // 父执行已 stopped（如工作流编排超时写入）
        AgentExecution stoppedExecution = new AgentExecution();
        stoppedExecution.setId("parent-1");
        stoppedExecution.setStatus(AgentExecutionStatusEnum.STOPPED.getCode());
        when(agentExecutionMapper.selectById("parent-1")).thenReturn(stoppedExecution);

        TeamGraphState state = new TeamGraphState(Map.of(
                TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.COMPLETED.getCode(),
                TeamGraphConstants.STATE_FINAL_OUTPUT, "已完成的内容"));

        AgentExecutionResult result = strategy.finalizeExecution(execution, AgentExecutionContext.builder().build(), state);

        assertThat(result.getStatus()).isEqualTo(AgentExecutionStatusEnum.STOPPED.getCode());
        verifyNoInteractions(eventStreamService);
    }

    @Test
    void shouldQueueOnlyOneResumeAndPersistFailureAfterClaim() {
        AgentExecutionMapper executionMapper = mock(AgentExecutionMapper.class);
        AgentLogService agentLogService = mock(AgentLogService.class);
        ExecutionEventStreamService eventStreamService = mock(ExecutionEventStreamService.class);
        AgentExecution execution = new AgentExecution();
        execution.setId("parent-1");
        execution.setStatus(AgentExecutionStatusEnum.PAUSED.getCode());
        when(executionMapper.selectById(execution.getId())).thenReturn(execution);
        AtomicReference<String> state = new AtomicReference<>(execution.getStatus());
        when(agentLogService.tryTransitionExecutionStatus(eq(execution.getId()), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String target = invocation.getArgument(2);
                    if (!state.compareAndSet(invocation.getArgument(1), target)) {
                        return false;
                    }
                    execution.setStatus(target);
                    return true;
                });
        ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        TeamAgentRuntimeStrategy strategy = createStrategy(executionMapper, agentLogService, eventStreamService, scheduled::add);
        Agent agent = new Agent();
        agent.setId("agent-1");
        AgentExecutionInputDTO input = new AgentExecutionInputDTO();
        input.setMessage("继续");
        when(executionMapper.selectList(any())).thenReturn(List.of());

        var firstResume = strategy.resume(agent, execution, null, input);
        var duplicateResume = strategy.resume(agent, execution, null, input);

        assertThat(scheduled).hasSize(1);
        assertThat(duplicateResume.join().getStatus()).isEqualTo(AgentExecutionStatusEnum.RUNNING.getCode());
        scheduled.remove().run();
        assertThat(firstResume.join().getStatus()).isEqualTo(AgentExecutionStatusEnum.FAILED.getCode());
        assertThat(state).hasValue(AgentExecutionStatusEnum.FAILED.getCode());
        ArgumentCaptor<AgentExecutionEvent> recorded = ArgumentCaptor.forClass(AgentExecutionEvent.class);
        verify(eventStreamService).publish(recorded.capture());
        assertThat(recorded.getValue().getEventType()).isEqualTo(AgentExecutionEventTypeEnum.FAILED.getCode());
        assertThat(strategy.resume(agent, execution, null, input).join().getStatus())
                .isEqualTo(AgentExecutionStatusEnum.FAILED.getCode());
        assertThat(scheduled).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepGraphDatabaseFailureDetailsOutOfTerminalOutput(boolean resume) {
        var executionMapper = mock(AgentExecutionMapper.class);
        var members = mock(AgentTeamMemberMapper.class);
        var logs = mock(AgentLogService.class);
        var events = mock(ExecutionEventStreamService.class);
        var strategy = createStrategy(executionMapper, members, logs, events, Runnable::run);
        var agent = new Agent();
        agent.setId("agent-1");
        var execution = new AgentExecution();
        execution.setId("parent-1");
        var context = AgentExecutionContext.builder().build();
        String internalDetail = "SQL fixture detail mentioning invalid api key";
        when(members.selectList(any())).thenAnswer(invocation -> {
            throw new GraphRunnerException(RunnableConfig.builder().build(),
                    new DataIntegrityViolationException(internalDetail, new SQLException(internalDetail, "22001")));
        });
        when(logs.tryTransitionExecutionStatus(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        AgentExecutionResult result = (resume
                ? strategy.resume(agent, execution, context, new AgentExecutionInputDTO())
                : strategy.execute(agent, execution, context)).join();

        String publicMessage = "Team Agent 执行失败，请查看执行详情或联系管理员。";
        assertThat(result.getStatus()).isEqualTo(AgentExecutionStatusEnum.FAILED.getCode());
        assertThat(result.getErrorMessage()).isEqualTo(publicMessage);
        assertThat(result.getOutputResult()).isNull();
        verify(logs).tryTransitionExecutionStatus(eq(execution.getId()), eq(AgentExecutionStatusEnum.RUNNING.getCode()),
                eq(AgentExecutionStatusEnum.FAILED.getCode()), eq(publicMessage), isNull(), isNull(), isNull());
        verify(events).publish(argThat(event -> AgentExecutionEventTypeEnum.FAILED.getCode().equals(event.getEventType())
                && publicMessage.equals(event.getMessage())));
    }

    @Test
    void shouldKeepActionableMissingMembersFailure() {
        var executionMapper = mock(AgentExecutionMapper.class);
        var logs = mock(AgentLogService.class);
        var events = mock(ExecutionEventStreamService.class);
        var strategy = createStrategy(executionMapper, logs, events);
        var agent = new Agent();
        agent.setId("agent-1");
        var execution = new AgentExecution();
        execution.setId("parent-1");
        when(logs.tryTransitionExecutionStatus(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        assertThat(strategy.execute(agent, execution, AgentExecutionContext.builder().build()).join().getErrorMessage())
                .isEqualTo("Team Agent 未配置成员");
    }

    @Test
    void shouldKeepExecutionFallbackWithoutShowingProviderDetails() {
        var executionMapper = mock(AgentExecutionMapper.class);
        var members = mock(AgentTeamMemberMapper.class);
        var logs = mock(AgentLogService.class);
        var events = mock(ExecutionEventStreamService.class);
        var strategy = createStrategy(executionMapper, members, logs, events, Runnable::run);
        var agent = new Agent();
        agent.setId("agent-1");
        var execution = new AgentExecution();
        execution.setId("parent-1");
        execution.setAgentId(agent.getId());
        String providerDetail = "provider fixture detail";
        when(members.selectList(any())).thenThrow(new IllegalStateException("too many requests: " + providerDetail));
        when(logs.tryTransitionExecutionStatus(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        var result = strategy.execute(agent, execution, AgentExecutionContext.builder().build()).join();

        assertThat(result.getStatus()).isEqualTo(AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode());
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getOutputResult()).contains("模型通道暂不可用").doesNotContain(providerDetail);
        var recorded = ArgumentCaptor.forClass(AgentExecutionEvent.class);
        verify(events).publish(recorded.capture());
        assertThat(recorded.getValue().getEventType()).isEqualTo(AgentExecutionEventTypeEnum.FALLBACK_USED.getCode());
        assertThat(recorded.getValue().getPayload().toString()).doesNotContain(providerDetail);
    }

    @Test
    void shouldReturnToPausedWhenResumeSchedulingIsRejected() {
        AgentExecutionMapper executionMapper = mock(AgentExecutionMapper.class);
        AgentLogService agentLogService = mock(AgentLogService.class);
        ExecutionEventStreamService eventStreamService = mock(ExecutionEventStreamService.class);
        AgentExecution execution = new AgentExecution();
        execution.setId("parent-1");
        execution.setErrorMessage("请人工确认");
        when(agentLogService.tryTransitionExecutionStatus(eq(execution.getId()), eq(AgentExecutionStatusEnum.PAUSED.getCode()),
                eq(AgentExecutionStatusEnum.RUNNING.getCode()), isNull(), isNull(), isNull(), isNull())).thenReturn(true);
        TeamAgentRuntimeStrategy strategy = createStrategy(executionMapper, agentLogService, eventStreamService,
                task -> { throw new RejectedExecutionException("队列已满"); });

        assertThatThrownBy(() -> strategy.resume(new Agent(), execution, null, new AgentExecutionInputDTO()))
                .isInstanceOf(RejectedExecutionException.class);
        verify(agentLogService).tryTransitionExecutionStatus(eq(execution.getId()), eq(AgentExecutionStatusEnum.RUNNING.getCode()),
                eq(AgentExecutionStatusEnum.PAUSED.getCode()), eq(execution.getErrorMessage()), isNull(), isNull(), isNull());
        verifyNoInteractions(eventStreamService);
    }

    @Test
    void shouldResumeApprovedChildBeforeEarlierContributorCanPauseAgain() {
        var executions = mock(AgentExecutionMapper.class);
        var engine = mock(AgentExecutionEngine.class);
        var logs = mock(AgentLogService.class);
        var strategy = new TeamAgentRuntimeStrategy(executions, mock(AgentTeamMemberMapper.class),
                mock(AgentTeamMemberToolBindingMapper.class), mock(BuiltinToolMapper.class),
                mock(AgentTeamMemberContextBindingMapper.class), mock(ContextItemMapper.class), engine,
                logs, mock(AgentLoopQualityChecker.class), mock(ExecutionEventStreamService.class),
                mock(AgentContextPublisher.class), mock(TeamCheckpointStore.class), mock(DesktopAttachmentService.class),
                mock(SandboxPolicyResolver.class), mock(ExecutionSteeringService.class), new DefaultTeamQualityDetector(),
                new TeamConversationArchive(mock(CompositeChatMemoryStore.class), mock(UserMemoryExtractionService.class), new ObjectMapper()),
                mock(TeamChildApprovalService.class), mock(TeamExecutionBudget.class),
                Runnable::run);
        var agent = new Agent();
        agent.setId("team");
        var parent = new AgentExecution();
        parent.setId("parent");
        parent.setTenantId("tenant");
        parent.setAgentId(agent.getId());
        var context = AgentExecutionContext.builder().tenantId("tenant").inputPrompt("Check the build")
                .inputContext(Map.of(DesktopExecutionTarget.DEVICE_CONTEXT_KEY, "device-1",
                        DesktopExecutionTarget.WORKSPACE_CONTEXT_KEY, "workspace-1"))
                .desktopVerificationCommand("mvn clean package").build();
        var coder = new AgentTeamMember();
        coder.setId("coder");
        var tester = new AgentTeamMember();
        tester.setId("tester");
        tester.setRoleName("测试员");
        var child = new AgentExecution();
        child.setId("tester-run");
        child.setTeamMemberId(tester.getId());
        child.setStatus(AgentExecutionStatusEnum.PAUSED.getCode());
        var input = new AgentExecutionInputDTO();
        input.setApprovalId("tester-approval");
        input.setApprovalDecision("approve");
        var approval = new TeamAgentRuntimeStrategy.MemberApprovalResume(child, input);

        when(executions.selectById(child.getId())).thenReturn(child);
        when(logs.createTeamChildWhileParentRunning(any(AgentExecution.class))).thenAnswer(invocation -> {
            invocation.<AgentExecution>getArgument(0).setId("coder-run");
            return true;
        });
        when(engine.execute(any())).thenReturn(CompletableFuture.completedFuture(
                AgentExecutionResult.builder().status("paused").build()));
        when(engine.resume(any(), any())).thenReturn(CompletableFuture.completedFuture(
                AgentExecutionResult.builder().status("paused").build()));
        var state = new TeamGraphState(Map.of(TeamAgentRuntimeStrategy.DESKTOP_VERIFICATION_OWNER_ID, tester.getId()));

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(strategy, "executeMembers",
                agent, parent, context, List.of(coder, tester), state, null, true);
        var firstRun = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(engine).execute(firstRun.capture());
        assertThat(firstRun.getValue().getTeamMemberId()).isEqualTo(coder.getId());
        assertThat(firstRun.getValue().isDesktopVerificationDelegated()).isTrue();
        assertThat(firstRun.getValue().getDesktopVerificationCommand()).isEqualTo("mvn clean package");

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(strategy, "executeMembers",
                agent, parent, context, List.of(coder, tester), state, approval, false);
        var resumed = ArgumentCaptor.forClass(AgentExecutionContext.class);
        var decidedInput = ArgumentCaptor.forClass(AgentExecutionInputDTO.class);
        verify(engine).resume(resumed.capture(), decidedInput.capture());
        assertThat(resumed.getValue().getExecutionId()).isEqualTo(child.getId());
        assertThat(resumed.getValue().isDesktopVerificationDelegated()).isFalse();
        assertThat(resumed.getValue().getInputPrompt()).contains("你负责最终验收");
        assertThat(decidedInput.getValue().getApprovalId()).isEqualTo(input.getApprovalId());
        assertThat(decidedInput.getValue().getApprovalDecision()).isEqualTo(input.getApprovalDecision());
        verifyNoMoreInteractions(engine);

        clearInvocations(engine);
        when(engine.resume(any(), any())).thenReturn(CompletableFuture.completedFuture(
                AgentExecutionResult.builder().status("completed").outputResult("Build verification passed").build()));

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(strategy, "executeMembers",
                agent, parent, context, List.of(coder, tester), state, approval, false);

        var order = inOrder(engine);
        order.verify(engine).resume(any(), any());
        var coderRetry = ArgumentCaptor.forClass(AgentExecutionContext.class);
        order.verify(engine).execute(coderRetry.capture());
        assertThat(coderRetry.getValue().getTeamMemberId()).isEqualTo(coder.getId());
        assertThat(coderRetry.getValue().getInputPrompt()).contains("Build verification passed");
        order.verifyNoMoreInteractions();
    }

    @Test
    void shouldResolveEnterprisePauseToTheOriginalTeamChild() {
        var executions = mock(AgentExecutionMapper.class);
        var strategy = createStrategy(executions);
        var parent = new AgentExecution();
        parent.setId("parent");
        parent.setTenantId("tenant");
        parent.setAgentId("team");
        var child = new AgentExecution();
        child.setId("child");
        child.setParentExecutionId(parent.getId());
        child.setTenantId(parent.getTenantId());
        child.setAgentId(parent.getAgentId());
        child.setStatus(AgentExecutionStatusEnum.PAUSED.getCode());
        when(executions.selectById(child.getId())).thenReturn(child);
        var input = new AgentExecutionInputDTO();
        input.setSecurityPausedChildExecutionId(child.getId());

        TeamAgentRuntimeStrategy.MemberApprovalResume resume =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(strategy, "resolveMemberApproval", parent, input);

        assertThat(resume.child()).isSameAs(child);
        assertThat(resume.input()).isSameAs(input);
        child.setParentExecutionId("other-parent");
        assertThatThrownBy(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                strategy, "resolveMemberApproval", parent, input))
                .hasMessageContaining("安全暂停已失效");
    }

    private TeamAgentRuntimeStrategy createStrategy() {
        return createStrategy(mock(AgentExecutionMapper.class));
    }

    private TeamAgentRuntimeStrategy createStrategy(AgentExecutionMapper agentExecutionMapper) {
        return createStrategy(
                agentExecutionMapper,
                mock(AgentLogService.class),
                mock(ExecutionEventStreamService.class)
        );
    }

    private TeamAgentRuntimeStrategy createStrategy(AgentExecutionMapper agentExecutionMapper,
                                                    AgentLogService agentLogService,
                                                    ExecutionEventStreamService eventStreamService) {
        return createStrategy(agentExecutionMapper, agentLogService, eventStreamService, Runnable::run);
    }

    private TeamAgentRuntimeStrategy createStrategy(AgentExecutionMapper agentExecutionMapper,
                                                    AgentLogService agentLogService,
                                                    ExecutionEventStreamService eventStreamService,
                                                    Executor executor) {
        return createStrategy(agentExecutionMapper, mock(AgentTeamMemberMapper.class), agentLogService,
                eventStreamService, executor);
    }

    private TeamAgentRuntimeStrategy createStrategy(AgentExecutionMapper agentExecutionMapper,
                                                    AgentTeamMemberMapper agentTeamMemberMapper,
                                                    AgentLogService agentLogService,
                                                    ExecutionEventStreamService eventStreamService,
                                                    Executor executor) {
        return createStrategy(agentExecutionMapper, agentTeamMemberMapper, agentLogService, eventStreamService,
                executor, mock(AgentTeamMemberToolBindingMapper.class));
    }

    private TeamAgentRuntimeStrategy createStrategy(AgentExecutionMapper agentExecutionMapper,
                                                    AgentTeamMemberMapper agentTeamMemberMapper,
                                                    AgentLogService agentLogService,
                                                    ExecutionEventStreamService eventStreamService,
                                                    Executor executor,
                                                    AgentTeamMemberToolBindingMapper bindingMapper) {
        return createStrategy(agentExecutionMapper, agentTeamMemberMapper, agentLogService, eventStreamService,
                executor, bindingMapper, new TeamConversationArchive(mock(CompositeChatMemoryStore.class),
                        mock(UserMemoryExtractionService.class), new ObjectMapper()));
    }

    private TeamAgentRuntimeStrategy createStrategy(AgentExecutionMapper agentExecutionMapper,
                                                    AgentTeamMemberMapper agentTeamMemberMapper,
                                                    AgentLogService agentLogService,
                                                    ExecutionEventStreamService eventStreamService,
                                                    Executor executor,
                                                    AgentTeamMemberToolBindingMapper bindingMapper,
                                                    TeamConversationArchive conversationArchive) {
        return new TeamAgentRuntimeStrategy(
                agentExecutionMapper,
                agentTeamMemberMapper,
                bindingMapper,
                mock(BuiltinToolMapper.class),
                mock(AgentTeamMemberContextBindingMapper.class),
                mock(ContextItemMapper.class),
                mock(AgentExecutionEngine.class),
                agentLogService,
                mock(AgentLoopQualityChecker.class),
                eventStreamService,
                mock(AgentContextPublisher.class),
                mock(TeamCheckpointStore.class),
                mock(DesktopAttachmentService.class),
                mock(SandboxPolicyResolver.class),
                mock(ExecutionSteeringService.class),
                new DefaultTeamQualityDetector(),
                conversationArchive,
                mock(TeamChildApprovalService.class), mock(TeamExecutionBudget.class),
                executor
        );
    }
}
