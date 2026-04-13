package com.schemaplexai.service.artifact;

import com.schemaplexai.model.vo.artifact.ArtifactVO;

import java.util.List;
import java.util.Map;

/**
 * 统一产物服务
 */
public interface ArtifactService {

    ArtifactVO getById(String id);

    List<ArtifactVO> listByWorkspaceId(String workspaceId);

    List<ArtifactVO> listBySpecId(String specId);

    WorkflowArtifactPersistResult saveWorkflowArtifact(WorkflowArtifactPersistCommand command);

    DownloadPayload download(String id);

    record WorkflowArtifactPersistCommand(
            String tenantId,
            String specId,
            String workflowInstanceId,
            String workspaceId,
            String sourceNodeId,
            String name,
            String title,
            String artifactType,
            String format,
            String mimeType,
            String contentText,
            Map<String, Object> metadataJson,
            List<WorkspaceDeliveryTarget> workspaceTargets
    ) {
    }

    record WorkspaceDeliveryTarget(
            String workspaceId,
            String targetPath,
            String targetUri,
            String deliveryType,
            String message,
            Map<String, Object> metadataJson
    ) {
    }

    record WorkflowArtifactPersistResult(
            String artifactId,
            Integer versionNumber
    ) {
    }

    record DownloadPayload(
            String fileName,
            String mimeType,
            byte[] content
    ) {
    }
}
