package com.schemaplexai.model.dto.quality;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CrossReviewCreateRequest {
    @NotBlank(message = "Spec ID不能为空")
    private String specId;
    private String taskId;
    private String profileId;
    private String issueType = "both";
    private String artifactId;
    private String targetContent;
    @Size(min = 1, max = 2, message = "模型数量必须为1到2个")
    private List<String> modelIds = new ArrayList<>();
}
