package com.schemaplexai.service.semantic.common;

/** 语义目录内部使用的受控 RDF 词汇。 */
public final class SemanticVocabulary {

    public static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    public static final String RDFS_CLASS = "http://www.w3.org/2000/01/rdf-schema#Class";
    public static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    public static final String RDFS_COMMENT = "http://www.w3.org/2000/01/rdf-schema#comment";
    public static final String RDFS_RANGE = "http://www.w3.org/2000/01/rdf-schema#range";
    public static final String RDF_PROPERTY = "http://www.w3.org/1999/02/22-rdf-syntax-ns#Property";
    public static final String OWL_CLASS = "http://www.w3.org/2002/07/owl#Class";
    public static final String OWL_DATATYPE_PROPERTY = "http://www.w3.org/2002/07/owl#DatatypeProperty";
    public static final String SKOS_ALT_LABEL = "http://www.w3.org/2004/02/skos/core#altLabel";
    public static final String SHACL_NODE_SHAPE = "http://www.w3.org/ns/shacl#NodeShape";
    public static final String SHACL_PROPERTY = "http://www.w3.org/ns/shacl#property";
    public static final String SHACL_PATH = "http://www.w3.org/ns/shacl#path";
    public static final String SHACL_MIN_COUNT = "http://www.w3.org/ns/shacl#minCount";
    public static final String SHACL_TARGET_SUBJECTS_OF = "http://www.w3.org/ns/shacl#targetSubjectsOf";
    public static final String XSD_BOOLEAN = "http://www.w3.org/2001/XMLSchema#boolean";
    public static final String XSD_INTEGER = "http://www.w3.org/2001/XMLSchema#integer";

    public static final String NAME = "urn:schemaplexai:semantic:name";
    public static final String REQUIRED = "urn:schemaplexai:semantic:required";
    public static final String MAPPING_SOURCE = "urn:schemaplexai:semantic:mappingSource";
    public static final String PHYSICAL_OBJECT = "urn:schemaplexai:semantic:physicalObject";
    public static final String PHYSICAL_FIELD = "urn:schemaplexai:semantic:physicalField";
    public static final String MAPPING_KIND = "urn:schemaplexai:semantic:mappingKind";

    private SemanticVocabulary() {
    }
}
