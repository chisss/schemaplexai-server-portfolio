package com.schemaplexai.service.database.credential;

import com.schemaplexai.model.entity.McpServer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 为单次 MCP 调用构造带明文凭据的内存副本
 */
@Component
@RequiredArgsConstructor
public class DatabaseCredentialMaterializer {

    public static final String SECRET_REF_KEY = "secretRef";

    private final DatabaseCredentialVault credentialVault;

    public McpServer materialize(McpServer source) {
        if (source == null || source.getConnectionConfig() == null) {
            return source;
        }
        Object secretRefValue = source.getConnectionConfig().get(SECRET_REF_KEY);
        if (secretRefValue == null || !StringUtils.hasText(String.valueOf(secretRefValue))) {
            return source;
        }

        Map<String, Object> runtimeConfig = new LinkedHashMap<>(source.getConnectionConfig());
        runtimeConfig.putAll(credentialVault.resolve(String.valueOf(secretRefValue)));
        McpServer runtime = new McpServer();
        BeanUtils.copyProperties(source, runtime);
        runtime.setConnectionConfig(runtimeConfig);
        return runtime;
    }
}
