package com.schemaplexai.web.controller;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.common.util.FilenameSanitizer;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.dto.agent.AgentConfigRequest;
import com.schemaplexai.model.dto.agent.AgentContextBindingDTO;
import com.schemaplexai.model.dto.agent.AgentCreateRequest;
import com.schemaplexai.model.dto.agent.AgentExecuteDTO;
import com.schemaplexai.model.dto.agent.AgentExecutionQueryDTO;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.dto.agent.AgentInitInstructionsDTO;
import com.schemaplexai.model.dto.agent.AgentQueryRequest;
import com.schemaplexai.model.dto.agent.AgentTeamMemberBatchRequest;
import com.schemaplexai.model.dto.agent.AgentToolBindingBatchRequest;
import com.schemaplexai.model.dto.agent.AgentUpdateRequest;
import com.schemaplexai.model.vo.agent.AgentConfigVO;
import com.schemaplexai.model.vo.agent.AgentContextBindingVO;
import com.schemaplexai.model.vo.agent.AgentExecuteResultVO;
import com.schemaplexai.model.vo.agent.AgentExecutionVO;
import com.schemaplexai.model.vo.agent.AgentInstructionsCheckVO;
import com.schemaplexai.model.vo.agent.AgentTeamMemberVO;
import com.schemaplexai.model.vo.agent.AgentToolBindingVO;
import com.schemaplexai.model.vo.agent.AgentVO;
import com.schemaplexai.model.vo.agent.AvailableToolVO;
import com.schemaplexai.model.vo.agent.ConversationMessageVO;
import com.schemaplexai.service.agent.AgentService;
import com.schemaplexai.service.agent.execution.ExecutionEventStreamService;
import com.schemaplexai.service.storage.DocumentStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.tika.Tika;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent管理控制器
 */
@RestController
@RequestMapping("/agents")
@RequiredArgsConstructor
@Tag(name = "Agent管理")
public class AgentController {

    private static final Tika TIKA = new Tika();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final AgentService agentService;
    private final ExecutionEventStreamService executionEventStreamService;
    private final DocumentStorageService documentStorageService;

    @GetMapping
    @Operation(summary = "分页查询Agent列表")
    public R<PageResult<AgentVO>> list(AgentQueryRequest request) {
        return R.ok(agentService.listAgents(request));
    }

    @GetMapping("/all")
    @Operation(summary = "获取所有Agent（不分页，用于下拉选择）")
    public R<List<AgentVO>> listAll() {
        return R.ok(agentService.listAllAgents());
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取Agent详情")
    public R<AgentVO> getById(@PathVariable String id) {
        return R.ok(agentService.getAgentById(id));
    }

    @PostMapping
    @Operation(summary = "创建Agent")
    public R<AgentVO> create(@Valid @RequestBody AgentCreateRequest request) {
        return R.ok(agentService.createAgent(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新Agent")
    public R<AgentVO> update(@PathVariable String id, @Valid @RequestBody AgentUpdateRequest request) {
        return R.ok(agentService.updateAgent(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除Agent")
    public R<Void> delete(@PathVariable String id) {
        agentService.deleteAgent(id);
        return R.ok();
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "更新Agent状态（激活/停用）")
    public R<Void> updateStatus(@PathVariable String id, @RequestParam String status) {
        agentService.updateStatus(id, status);
        return R.ok();
    }

    @GetMapping("/{id}/configs")
    @Operation(summary = "获取Agent配置列表")
    public R<List<AgentConfigVO>> getConfigs(@PathVariable String id) {
        return R.ok(agentService.getConfigs(id));
    }

    @PostMapping("/{id}/configs")
    @Operation(summary = "保存Agent配置")
    public R<AgentConfigVO> saveConfig(@PathVariable String id,
                                       @Valid @RequestBody AgentConfigRequest request) {
        return R.ok(agentService.saveConfig(id, request));
    }

    @DeleteMapping("/{id}/configs/{configId}")
    @Operation(summary = "删除Agent配置")
    public R<Void> deleteConfig(@PathVariable String id, @PathVariable String configId) {
        agentService.deleteConfig(id, configId);
        return R.ok();
    }

    // ========== 团队成员管理 ==========

    @GetMapping("/{id}/team-members")
    @Operation(summary = "获取Agent团队成员列表")
    public R<List<AgentTeamMemberVO>> getTeamMembers(@PathVariable String id) {
        return R.ok(agentService.getTeamMembers(id));
    }

    @PostMapping("/{id}/team-members")
    @Operation(summary = "批量保存团队成员（全量覆盖）")
    public R<List<AgentTeamMemberVO>> saveTeamMembers(@PathVariable String id,
                                                       @Valid @RequestBody AgentTeamMemberBatchRequest request) {
        return R.ok(agentService.saveTeamMembers(id, request));
    }

    @DeleteMapping("/{id}/team-members/{memberId}")
    @Operation(summary = "删除团队成员")
    public R<Void> deleteTeamMember(@PathVariable String id, @PathVariable String memberId) {
        agentService.deleteTeamMember(id, memberId);
        return R.ok();
    }

    // ========== 上下文绑定管理 ==========

    @GetMapping("/{id}/context-bindings")
    @Operation(summary = "获取Agent上下文绑定列表")
    public R<List<AgentContextBindingVO>> getContextBindings(@PathVariable String id) {
        return R.ok(agentService.getContextBindings(id));
    }

    @PostMapping("/{id}/context-bindings")
    @Operation(summary = "创建上下文绑定")
    public R<AgentContextBindingVO> createContextBinding(@PathVariable String id,
                                                          @Valid @RequestBody AgentContextBindingDTO request) {
        return R.ok(agentService.createContextBinding(id, request));
    }

    @DeleteMapping("/{id}/context-bindings/{bindingId}")
    @Operation(summary = "删除上下文绑定")
    public R<Void> deleteContextBinding(@PathVariable String id, @PathVariable String bindingId) {
        agentService.deleteContextBinding(id, bindingId);
        return R.ok();
    }

    // ========== 工具绑定管理 ==========

    @GetMapping("/{id}/tools")
    @Operation(summary = "获取Agent工具绑定列表")
    public R<List<AgentToolBindingVO>> getToolBindings(@PathVariable String id) {
        return R.ok(agentService.getToolBindings(id));
    }

    @PutMapping("/{id}/tools")
    @Operation(summary = "批量更新Agent工具绑定（全量覆盖）")
    public R<List<AgentToolBindingVO>> saveToolBindings(@PathVariable String id,
                                                         @Valid @RequestBody AgentToolBindingBatchRequest request) {
        return R.ok(agentService.saveToolBindings(id, request));
    }

    @GetMapping("/{id}/available-tools")
    @Operation(summary = "获取Agent可绑定工具列表（内置工具/Skill/MCP）")
    public R<List<AvailableToolVO>> getAvailableTools(@PathVariable String id) {
        return R.ok(agentService.getAvailableTools(id));
    }

    // ========== 执行管理 ==========

    @PostMapping("/{id}/execute")
    @Operation(summary = "触发Agent执行任务（异步）")
    public R<AgentExecuteResultVO> execute(@PathVariable String id,
                                           @Valid @RequestBody AgentExecuteDTO dto) {
        return R.ok(agentService.execute(id, dto));
    }

    @PostMapping(value = "/chat/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传 AI 对话附件")
    public R<Map<String, String>> uploadChatAttachment(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        String tenantId = SecurityUtil.getCurrentTenantId();
        String fileId = UUID.randomUUID().toString();
        String fileName = FilenameSanitizer.sanitize(file.getOriginalFilename());
        String datePath = LocalDate.now().format(DATE_FORMATTER);
        String objectKey = "tenants/" + tenantId + "/chat/" + datePath + "/" + fileId + "/" + fileName;
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "读取上传文件失败");
        }
        String contentType = TIKA.detect(bytes, fileName);
        documentStorageService.putObject(
                documentStorageService.getDefaultBucket(),
                objectKey,
                new ByteArrayInputStream(bytes),
                bytes.length,
                contentType
        );
        return R.ok(Map.of(
                "fileId", objectKey,
                "fileName", fileName,
                "fileUrl", documentStorageService.getPresignedDownloadUrl(
                        documentStorageService.getDefaultBucket(),
                        objectKey,
                        3600
                )
        ));
    }

    @GetMapping("/{id}/executions")
    @Operation(summary = "分页查询执行记录")
    public R<PageResult<AgentExecutionVO>> pageExecutions(@PathVariable String id,
                                                          AgentExecutionQueryDTO query) {
        return R.ok(agentService.pageExecutions(id, query));
    }

    @GetMapping("/{id}/executions/{execId}")
    @Operation(summary = "获取执行详情（含日志）")
    public R<AgentExecutionVO> getExecution(@PathVariable String id,
                                             @PathVariable String execId) {
        return R.ok(agentService.getExecution(id, execId));
    }

    @PostMapping("/{id}/executions/{execId}/stop")
    @Operation(summary = "停止执行")
    public R<Void> stopExecution(@PathVariable String id, @PathVariable String execId) {
        agentService.stopExecution(id, execId);
        return R.ok();
    }

    @GetMapping("/conversations/{conversationId}/history")
    @Operation(summary = "查询对话历史消息")
    public R<List<ConversationMessageVO>> getConversationHistory(
            @PathVariable String conversationId) {
        return R.ok(agentService.getConversationHistory(conversationId));
    }

    @GetMapping(value = "/{id}/executions/{execId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅执行事件流（SSE）")
    public SseEmitter streamExecutionEvents(@PathVariable String id, @PathVariable String execId) {
        // 复用读取权限校验：不存在或不归属会抛业务异常
        agentService.getExecution(id, execId);
        return executionEventStreamService.subscribe(execId);
    }

    @PostMapping("/{id}/executions/{execId}/input")
    @Operation(summary = "提交 Human-in-Loop 输入")
    public R<Void> submitExecutionInput(@PathVariable String id, @PathVariable String execId,
                                        @Valid @RequestBody AgentExecutionInputDTO dto) {
        agentService.submitExecutionInput(id, execId, dto);
        return R.ok();
    }

    @PostMapping("/{id}/executions/{execId}/cancel")
    @Operation(summary = "取消执行（兼容 stop）")
    public R<Void> cancelExecution(@PathVariable String id, @PathVariable String execId) {
        agentService.stopExecution(id, execId);
        return R.ok();
    }

    @GetMapping("/{id}/instructions-check")
    @Operation(summary = "检查Agent是否已配置专属指令")
    public R<AgentInstructionsCheckVO> checkInstructions(@PathVariable String id) {
        return R.ok(agentService.checkInstructions(id));
    }

    @PostMapping("/{id}/instructions/init")
    @Operation(summary = "基于模板初始化Agent专属指令")
    public R<AgentInstructionsCheckVO> initInstructions(@PathVariable String id,
                                                         @Valid @RequestBody AgentInitInstructionsDTO dto) {
        return R.ok(agentService.initInstructions(id, dto));
    }
}
