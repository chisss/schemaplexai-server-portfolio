package com.schemaplexai.service.integration.platform;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Git 平台适配器工厂
 * <p>
 * 自动注册所有 {@link GitPlatformAdapter} 实现，按 platform code 路由。
 * </p>
 */
@Component
public class GitPlatformAdapterFactory {

    private final Map<String, GitPlatformAdapter> adapterMap = new HashMap<>();

    public GitPlatformAdapterFactory(List<GitPlatformAdapter> adapters) {
        for (GitPlatformAdapter adapter : adapters) {
            adapterMap.put(adapter.platform(), adapter);
        }
    }

    public GitPlatformAdapter getAdapter(String platform) {
        GitPlatformAdapter adapter = adapterMap.get(platform);
        if (adapter == null) {
            throw new BusinessException(ResultCode.INTEGRATION_CONFIG_INVALID,
                    "不支持的 Git 平台: " + platform);
        }
        return adapter;
    }
}
