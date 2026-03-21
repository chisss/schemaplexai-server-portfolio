package com.schemaplexai.model.vo.context;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 上下文详情VO（含条目列表）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ContextDetailVO extends ContextVO {

    private List<ContextItemVO> items;
}
