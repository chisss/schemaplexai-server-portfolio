package com.schemaplexai.service.semantic.domain.model.ontology;

/** 受限邻域查询参数，不支持原始 SPARQL。 */
public record GraphQuery(String focusNodeIri, String keyword, int depth, int page, int size) {

    public GraphQuery {
        focusNodeIri = normalize(focusNodeIri);
        keyword = normalize(keyword);
        if (focusNodeIri != null && !(focusNodeIri.startsWith("urn:")
                || focusNodeIri.startsWith("http://") || focusNodeIri.startsWith("https://"))) {
            throw new IllegalArgumentException("focusNodeIri must be an absolute IRI");
        }
        if (depth < 0 || depth > 2) {
            throw new IllegalArgumentException("depth must be between 0 and 2");
        }
        if (page < 1) {
            throw new IllegalArgumentException("page must be positive");
        }
        if (size < 1 || size > 500) {
            throw new IllegalArgumentException("size must be between 1 and 500");
        }
    }

    public static GraphQuery firstPage() {
        return new GraphQuery(null, null, 1, 1, 100);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
