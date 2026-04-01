package com.schemaplexai.model.dto.quality;

import lombok.Data;

@Data
public class DeviationQueryRequest {
    private String workspaceId;
    private String specId;
    private String deviationType;
    private String severity;
    private String status;
    private String keyword;
    private Integer page = 1;
    private Integer size = 20;
}
