package com.schemaplexai.model.vo.quality;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzeTaskVO {
    private String analyzeTaskId;
    private String status;
    private String message;
}
