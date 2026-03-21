package com.schemaplexai.model.vo.quality;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class IntentDefectVO {
    private String id;
    private String specId;
    private String specName;
    private String docType;
    private String docTypeLabel;
    private String defectType;
    private String defectTypeLabel;
    private String severity;
    private String title;
    private String description;
    private String location;
    private String suggestion;
    private String status;
    private LocalDateTime createdAt;
}
