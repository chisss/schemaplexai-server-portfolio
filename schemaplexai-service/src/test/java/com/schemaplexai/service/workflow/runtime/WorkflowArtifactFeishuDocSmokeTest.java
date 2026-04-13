package com.schemaplexai.service.workflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.dao.mapper.NotificationChannelMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.model.entity.NotificationChannel;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.service.artifact.ArtifactService;
import com.schemaplexai.service.integration.feishu.FeishuDocDeliveryService;
import com.schemaplexai.service.integration.git.GitOperationService;
import com.schemaplexai.service.spec.handler.SpecVersionHandler;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowArtifactFeishuDocSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDeliverMarketingBundleToRealFeishuDocWhenEnabled() {
        Assumptions.assumeTrue(Boolean.getBoolean("feishu.smoke.enabled"),
                "未开启 feishu.smoke.enabled，跳过真实飞书烟测");

        String appId = requiredProperty("feishu.appId");
        String appSecret = requiredProperty("feishu.appSecret");

        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.saveWorkflowArtifact(any()))
                .thenReturn(new ArtifactService.WorkflowArtifactPersistResult("artifact-smoke", 1));

        NotificationChannel channel = new NotificationChannel();
        channel.setId("feishu-smoke-channel");
        channel.setName("飞书文档烟测渠道");
        channel.setChannelType("feishu");
        channel.setStatus("active");
        Map<String, Object> config = new HashMap<>();
        config.put("app_id", appId);
        config.put("app_secret", appSecret);
        putOptionalProperty(config, "document_folder_token", "feishu.folderToken");
        putOptionalProperty(config, "base_url", "feishu.baseUrl");
        putOptionalProperty(config, "document_url_prefix", "feishu.documentUrlPrefix");
        channel.setConfig(config);

        NotificationChannelMapper notificationChannelMapper = mock(NotificationChannelMapper.class);
        when(notificationChannelMapper.selectById("feishu-smoke-channel")).thenReturn(channel);

        WorkflowArtifactService service = new WorkflowArtifactService(
                specMapper,
                specVersionHandler,
                gitOperationService,
                artifactService,
                new ObjectMapper(),
                notificationChannelMapper,
                new FeishuDocDeliveryService(new OkHttpClient(), new ObjectMapper())
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-feishu-smoke");
        instance.setSpecId("spec-feishu-smoke");
        Map<String, Object> variables = new HashMap<>();
        variables.put("specType", "marketing");
        variables.put("workspacePath", tempDir.toString());
        variables.put("artifactOutputPath", "marketing/smoke/");
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("marketing_copy_team");
        nodeExecution.setNodeLabel("营销文案 Team Agent");

        String docTitle = "菲律宾信贷系统营销文案烟测-" + System.currentTimeMillis();
        String result = """
                {
                  "summary": "SchemaPlexAI 飞书文档烟测：已生成 AB 版营销文案。",
                  "complianceNotes": "需披露 APR、放款条件与适用人群。",
                  "bundleItems": [
                    {
                      "variantKey": "A",
                      "title": "利益导向版",
                      "content": "# 利益导向版\\n\\n- 最快 5 分钟完成申请\\n- 在线提交资料\\n"
                    },
                    {
                      "variantKey": "B",
                      "title": "信任导向版",
                      "content": "# 信任导向版\\n\\n- 透明披露费用结构\\n- 全流程状态可追踪\\n"
                    }
                  ]
                }
                """;

        Map<String, Object> artifact = service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of(
                        "artifactType", "marketing_copy_bundle",
                        "artifactTitle", docTitle,
                        "artifactDeliveryType", "feishu_doc",
                        "artifactDeliveryChannelId", "feishu-smoke-channel"
                ),
                result
        );

        String documentUrl = String.valueOf(artifact.get("artifactDeliveryUrl"));
        String documentId = String.valueOf(artifact.get("artifactDeliveryDocumentId"));
        assertThat(artifact.get("artifactDeliveryType")).isEqualTo("feishu_doc");
        assertThat(documentUrl).startsWith("http");
        assertThat(documentId).isNotBlank();
        assertThat(documentUrl).contains(documentId);
        System.out.println("SMOKE_FEISHU_DOC_ID=" + documentId);
        System.out.println("SMOKE_FEISHU_DOC_URL=" + documentUrl);
    }

    private String requiredProperty(String key) {
        String value = System.getProperty(key);
        Assumptions.assumeTrue(value != null && !value.isBlank(), "缺少系统属性: " + key);
        return value;
    }

    private void putOptionalProperty(Map<String, Object> config, String configKey, String propertyKey) {
        String value = System.getProperty(propertyKey);
        if (value != null && !value.isBlank()) {
            config.put(configKey, value);
        }
    }
}
