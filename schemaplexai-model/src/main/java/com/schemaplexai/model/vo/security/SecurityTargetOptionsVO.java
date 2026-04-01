package com.schemaplexai.model.vo.security;

import lombok.Data;

import java.util.List;

/**
 * 安全策略目标选项集合
 */
@Data
public class SecurityTargetOptionsVO {

    private List<SecurityTargetOptionVO> tenant;

    private List<SecurityTargetOptionVO> agent;

    private List<SecurityTargetOptionVO> workflow;

    private List<SecurityTargetOptionVO> project;
}
