package com.schemaplexai.service.semantic.infrastructure.jena;

import com.schemaplexai.service.semantic.domain.port.SemanticGraphLocator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/** 服务端生成语义版本图 IRI，避免客户端注入任意图名。 */
@Component
public class SemanticGraphIriFactory implements SemanticGraphLocator {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    public GraphSet create(String tenantId, String modelId, long version) {
        String normalizedTenantId = normalizeId(tenantId, "tenantId");
        String normalizedModelId = normalizeId(modelId, "modelId");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        String prefix = "urn:spx:tenant:" + normalizedTenantId + ":semantic:" + normalizedModelId + ":v:" + version;
        return new GraphSet(prefix);
    }

    @Override
    public String assertedGraph(String tenantId, String modelId, long version) {
        return create(tenantId, modelId, version).asserted();
    }

    static String normalizeId(String value, String name) {
        String normalized = value == null ? null : value.trim();
        if (!StringUtils.hasText(normalized) || !normalized.equals(value)
                || !SAFE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }

    public static final class GraphSet {

        private final String prefix;

        private GraphSet(String prefix) {
            this.prefix = prefix;
        }

        public String asserted() {
            return prefix + ":asserted";
        }

        public String shapes() {
            return prefix + ":shapes";
        }

        public String inferred() {
            return prefix + ":inferred";
        }

        public String temporary(String operationId) {
            return prefix + ":temporary:" + normalizeId(operationId, "operationId");
        }
    }
}
