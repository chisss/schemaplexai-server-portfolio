package com.schemaplexai.dao.mapper.semantic;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
// 每条语句显式通过租户键限制范围。
@InterceptorIgnore(tenantLine = "true")
public interface OntologyConfigurationArtifactMapper {

    @Select("""
            SELECT revision, configuration_json::text AS configuration_json, ontology_turtle,
                   shapes_turtle, inferred_turtle, published_checksum, query_profile_json::text AS query_profile_json
            FROM sf_ontology_configuration_artifact
            WHERE tenant_id = CAST(#{tenantId} AS UUID) AND version_id = CAST(#{versionId} AS UUID)
            """)
    ArtifactRow find(@Param("tenantId") String tenantId, @Param("versionId") String versionId);

    @Select("""
            SELECT published_checksum IS NOT NULL
            FROM sf_ontology_configuration_artifact
            WHERE tenant_id = CAST(#{tenantId} AS UUID) AND version_id = CAST(#{versionId} AS UUID)
            """)
    Boolean published(@Param("tenantId") String tenantId, @Param("versionId") String versionId);

    @Select("""
            SELECT published_checksum IS NOT NULL AND query_profile_json IS NOT NULL
            FROM sf_ontology_configuration_artifact
            WHERE tenant_id = CAST(#{tenantId} AS UUID) AND version_id = CAST(#{versionId} AS UUID)
            """)
    Boolean queryReady(@Param("tenantId") String tenantId, @Param("versionId") String versionId);

    @Update("""
            INSERT INTO sf_ontology_configuration_artifact AS artifact
                (tenant_id, version_id, revision, configuration_json, ontology_turtle, shapes_turtle)
            VALUES (CAST(#{tenantId} AS UUID), CAST(#{versionId} AS UUID), #{revision},
                    CAST(#{configurationJson} AS JSONB), #{ontologyTurtle}, #{shapesTurtle})
            ON CONFLICT (tenant_id, version_id) DO UPDATE
            SET revision = EXCLUDED.revision, configuration_json = EXCLUDED.configuration_json,
                ontology_turtle = EXCLUDED.ontology_turtle, shapes_turtle = EXCLUDED.shapes_turtle
            WHERE artifact.published_checksum IS NULL AND artifact.revision < EXCLUDED.revision
            """)
    int saveDraft(
            @Param("tenantId") String tenantId,
            @Param("versionId") String versionId,
            @Param("revision") long revision,
            @Param("configurationJson") String configurationJson,
            @Param("ontologyTurtle") String ontologyTurtle,
            @Param("shapesTurtle") String shapesTurtle);

    @Update("""
            UPDATE sf_ontology_configuration_artifact
            SET revision = #{publishedRevision}, published_checksum = #{checksum}, inferred_turtle = #{inferredTurtle},
                query_profile_json = CAST(#{queryProfileJson} AS JSONB)
            WHERE tenant_id = CAST(#{tenantId} AS UUID) AND version_id = CAST(#{versionId} AS UUID)
              AND revision = #{expectedRevision} AND published_checksum IS NULL
            """)
    int freeze(
            @Param("tenantId") String tenantId,
            @Param("versionId") String versionId,
            @Param("expectedRevision") long expectedRevision,
            @Param("publishedRevision") long publishedRevision,
            @Param("checksum") String checksum,
            @Param("inferredTurtle") String inferredTurtle,
            @Param("queryProfileJson") String queryProfileJson);

    record ArtifactRow(
            long revision, String configurationJson, String ontologyTurtle, String shapesTurtle,
            String inferredTurtle, String publishedChecksum, String queryProfileJson) {
    }
}
