package com.schemaplexai.model.vo.homepage;

import lombok.Data;

import java.util.List;

/**
 * 阻塞链路
 */
@Data
public class BlockChainVO {

    private String id;
    private String blockerType;
    private String blockerTitle;
    private String blockedResource;
    private String status;
    private String targetUrl;
}
