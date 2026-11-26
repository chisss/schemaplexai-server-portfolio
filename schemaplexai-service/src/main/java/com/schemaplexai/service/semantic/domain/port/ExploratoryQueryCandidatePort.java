package com.schemaplexai.service.semantic.domain.port;

import com.schemaplexai.service.semantic.domain.model.query.ExploratoryQuerySelection;

import java.util.List;

/** 单次模型调用只读取用户问题和获准字段的最小结构描述。 */
public interface ExploratoryQueryCandidatePort {

    ExploratoryQuerySelection propose(String tenantId, Request request);

    record Request(String question, List<Field> fields, String datasetName) {
        public Request(String question, List<Field> fields) {
            this(question, fields, null);
        }
    }

    record Field(String id, String name, String type) {
    }
}
