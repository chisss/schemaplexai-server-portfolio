package com.schemaplexai.service.workflow;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactSceneResolverTest {

    @Test
    void shouldInferDeliveryDocTypeFromDeliveryConfig() {
        String docType = ArtifactSceneResolver.inferArtifactDocType(Map.of(
                "artifactOutputPath", "deliveries/finance/customer-demo.md",
                "artifactDeliveryType", "feishu_doc",
                "outputVariableKey", "financeDeliveryResult"
        ));

        assertThat(docType).isEqualTo("delivery");
        assertThat(ArtifactSceneResolver.isCustomerDelivery(Map.of(
                "artifactOutputPath", "deliveries/finance/customer-demo.md",
                "artifactDeliveryType", "feishu_doc"
        ))).isTrue();
    }

    @Test
    void shouldRecognizeCustomerDeliveryPrompt() {
        assertThat(ArtifactSceneResolver.isCustomerDeliveryPrompt("""
                请输出最终交付文档。
                目标产物: deliveries/blogger/customer-demo.md
                artifactDeliveryType: feishu_doc
                """)).isTrue();
    }
}
