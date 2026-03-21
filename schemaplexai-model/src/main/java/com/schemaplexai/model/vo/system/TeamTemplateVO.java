package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 团队模板VO
 */
@Data
public class TeamTemplateVO {

    private String id;
    private String name;
    private String code;
    private String description;
    private String category;
    private String icon;
    private List<Map<String, Object>> recommendedRoles;
    private String status;
    private LocalDateTime createdAt;
}
