package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent团队成员配置表实体
 */
@Data
@TableName("sf_agent_team_member")
public class AgentTeamMember implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联AgentID */
    private String agentId;

    /** 角色显示名称 */
    private String roleName;

    /** 角色类型编码 */
    private String roleType;

    /** 该角色数量 */
    private Integer quantity;

    /** 模型覆盖（不填则使用Agent默认模型） */
    private String modelOverride;

    /** 描述 */
    private String description;

    /** 依赖的前置成员ID（逗号分隔，声明 DAG 依赖） */
    private String dependsOn;

    /** 允许读取的同队成员ID（逗号分隔，对等读取） */
    private String canReadPeers;

    /** 排序序号 */
    private Integer sortOrder;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
