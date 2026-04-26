package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统内置工具注册表
 */
@Data
@TableName("sf_builtin_tool")
public class BuiltinTool implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 工具代码: sys.read / sys.bash / ... */
    private String code;

    /** 显示名称 */
    private String name;

    /** 工具描述 */
    private String description;

    /** 输入参数 JSON Schema */
    private String inputSchema;

    /** 支持的操作系统: all / linux / windows / macos */
    private String osSupport;

    /** 是否启用 */
    private Boolean enabled;

    /** 排序号 */
    private Integer sortOrder;

    /** IO 类型: READ / WRITE / READ_WRITE */
    private String ioType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
