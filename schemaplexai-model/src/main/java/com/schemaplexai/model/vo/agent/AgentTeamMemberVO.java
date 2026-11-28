package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent团队成员VO
 */
@Data
public class AgentTeamMemberVO {

    private String id;
    private String agentId;
    private String roleName;
    private String roleType;
    private Integer quantity;
    private String modelOverride;
    private String description;
    /** 依赖的前置成员ID（逗号分隔，声明 DAG 依赖） */
    private String dependsOn;
    /** 允许读取的同队成员ID（逗号分隔，对等读取） */
    private String canReadPeers;
    private Integer sortOrder;
    /** 该成员已绑定的工具数 */
    private Integer boundToolCount;
    /** 该成员已绑定的工具名称列表（逗号分隔） */
    private String boundToolNames;
    /** 该成员已绑定的工具详情列表 */
    private List<AgentTeamMemberToolBindingVO> boundTools;
    /** 该成员已绑定的上下文数 */
    private Integer boundContextCount;
    /** 该成员已绑定的上下文标题列表（逗号分隔） */
    private String boundContextNames;
    /** 该成员已绑定的上下文详情列表 */
    private List<AgentTeamMemberContextBindingVO> boundContexts;
    private LocalDateTime createdAt;
}
