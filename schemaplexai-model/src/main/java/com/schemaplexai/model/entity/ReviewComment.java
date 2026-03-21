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
 * 评审意见表实体
 */
@Data
@TableName("sf_review_comment")
public class ReviewComment implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 评审会话ID */
    private String sessionId;

    /** 评审人ID */
    private String reviewerId;

    /** 级别: Critical/Warning/Info */
    private String level;

    /** 分类: 功能完整性/技术可行性/性能/安全/其他 */
    private String category;

    /** 意见内容 */
    private String content;

    /** 文档位置(行号/章节) */
    private String location;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
