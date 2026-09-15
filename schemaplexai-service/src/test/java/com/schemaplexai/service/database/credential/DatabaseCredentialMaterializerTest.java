package com.schemaplexai.service.database.credential;

import com.schemaplexai.model.entity.McpServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseCredentialMaterializerTest {

    @Test
    void shouldMaterializeCredentialIntoRuntimeCopyOnly() {
        DatabaseCredentialVault vault = mock(DatabaseCredentialVault.class);
        when(vault.resolve("secret-1")).thenReturn(Map.of("password", "plain-secret"));
        DatabaseCredentialMaterializer materializer = new DatabaseCredentialMaterializer(vault);

        McpServer stored = new McpServer();
        stored.setId("source-1");
        stored.setConnectionConfig(Map.of(
                "host", "db.internal",
                DatabaseCredentialMaterializer.SECRET_REF_KEY, "secret-1"
        ));

        McpServer runtime = materializer.materialize(stored);

        assertThat(runtime).isNotSameAs(stored);
        assertThat(runtime.getConnectionConfig()).containsEntry("password", "plain-secret");
        assertThat(stored.getConnectionConfig()).doesNotContainKey("password");
    }
}
