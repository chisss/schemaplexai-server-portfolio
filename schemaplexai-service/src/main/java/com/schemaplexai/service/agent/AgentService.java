package com.schemaplexai.service.agent;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.agent.AgentConfigRequest;
import com.schemaplexai.model.dto.agent.AgentContextBindingDTO;
import com.schemaplexai.model.dto.agent.AgentCreateRequest;
import com.schemaplexai.model.dto.agent.AgentExecuteDTO;
import com.schemaplexai.model.dto.agent.AgentExecutionQueryDTO;
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

import java.util.List;

/**
 * Agent管理服务接口
 */
public interface AgentService {

    /**
     * 分页查询Agent列表
     */
    PageResult<AgentVO> listAgents(AgentQueryRequest request);

    /**
     * 获取所有Agent列表（不分页，用于下拉选择等场景）
     */
    List<AgentVO> listAllAgents();

    /**
     * 获取Agent详情（含配置）
     */
    AgentVO getAgentById(String id);

    /**
     * 创建Agent
     */
    AgentVO createAgent(AgentCreateRequest request);

    /**
     * 更新Agent
     */
    AgentVO updateAgent(String id, AgentUpdateRequest request);

    /**
     * 删除Agent
     */
    void deleteAgent(String id);

    /**
     * 更新Agent状态（激活/停用）
     */
    void updateStatus(String id, String status);

    /**
     * 获取Agent配置列表
     */
    List<AgentConfigVO> getConfigs(String agentId);

    /**
     * 保存Agent配置
     */
    AgentConfigVO saveConfig(String agentId, AgentConfigRequest request);

    /**
     * 删除Agent配置
     */
    void deleteConfig(String agentId, String configId);

    /**
     * 获取团队成员列表
     */
    List<AgentTeamMemberVO> getTeamMembers(String agentId);

    /**
     * 批量保存团队成员（全量覆盖）
     */
    List<AgentTeamMemberVO> saveTeamMembers(String agentId, AgentTeamMemberBatchRequest request);

    /**
     * 删除团队成员
     */
    void deleteTeamMember(String agentId, String memberId);

    /**
     * 获取上下文绑定列表
     */
    List<AgentContextBindingVO> getContextBindings(String agentId);

    /**
     * 创建上下文绑定
     */
    AgentContextBindingVO createContextBinding(String agentId, AgentContextBindingDTO request);

    /**
     * 删除上下文绑定
     */
    void deleteContextBinding(String agentId, String bindingId);

    /**
     * 获取 Agent 工具绑定列表
     */
    List<AgentToolBindingVO> getToolBindings(String agentId);

    /**
     * 批量保存 Agent 工具绑定（全量覆盖）
     */
    List<AgentToolBindingVO> saveToolBindings(String agentId, AgentToolBindingBatchRequest request);

    /**
     * 获取 Agent 可绑定工具列表（内置工具/Skill/MCP，过滤已绑定）
     */
    List<AvailableToolVO> getAvailableTools(String agentId);

    // ==================== Agent 执行 ====================

    /**
     * 触发 Agent 执行（异步入队）
     */
    AgentExecuteResultVO execute(String agentId, AgentExecuteDTO dto);

    /**
     * 分页查询执行记录
     */
    PageResult<AgentExecutionVO> pageExecutions(String agentId, AgentExecutionQueryDTO query);

    /**
     * 获取执行详情（含日志）
     */
    AgentExecutionVO getExecution(String agentId, String executionId);

    /**
     * 停止执行
     */
    void stopExecution(String agentId, String executionId);

    // ==================== Agent 专属指令 ====================

    /**
     * 检查 Agent 是否配置了专属指令（isAgentInstructions=true 的上下文条目）
     */
    AgentInstructionsCheckVO checkInstructions(String agentId);

    /**
     * 基于模板初始化 Agent 专属指令
     */
    AgentInstructionsCheckVO initInstructions(String agentId, AgentInitInstructionsDTO dto);
}
