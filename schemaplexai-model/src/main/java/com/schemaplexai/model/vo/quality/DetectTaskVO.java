package com.schemaplexai.model.vo.quality;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DetectTaskVO {
    private String detectTaskId;
    private String status;
    private String message;
}
