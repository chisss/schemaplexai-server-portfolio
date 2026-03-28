package com.schemaplexai.service.tool.loader;

import com.schemaplexai.common.enums.CustomToolTypeEnum;
import com.schemaplexai.spi.CustomToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 自定义工具动态加载器
 * <p>
 * 支持三种加载方式：
 * <ul>
 *   <li>SPI 加载：从 META-INF/services 读取</li>
 *   <li>JAR 加载：从指定目录动态加载</li>
 *   <li>HTTP 加载：远程 HTTP API</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomToolLoader {

    private static final String SPI_SERVICE_FILE = "META-INF/services/" + CustomToolExecutor.class.getName();
    private static final String CUSTOM_TOOLS_DIR = System.getProperty("user.dir") + "/custom-tools";

    /** 缓存：toolCode -> executor 实例，TTL 5分钟 */
    private final Map<String, CachedExecutor> executorCache = new ConcurrentHashMap<>();

    /** 类加载器缓存 */
    private final Map<String, URLClassLoader> classLoaderCache = new ConcurrentHashMap<>();

    private final ScriptExecutor scriptExecutor;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void init() {
        // 启动缓存清理任务
        scheduler.scheduleAtFixedRate(this::cleanExpiredCache, 5, 5, TimeUnit.MINUTES);

        // 加载 SPI 实现
        loadSpiExecutors();
    }

    /**
     * 获取执行器
     */
    public CustomToolExecutor getExecutor(String toolCode) {
        CachedExecutor cached = executorCache.get(toolCode);
        if (cached != null && !cached.isExpired()) {
            return cached.executor;
        }

        // 尝试从 JAR 加载
        CustomToolExecutor executor = loadFromJar(toolCode);
        if (executor != null) {
            cacheExecutor(toolCode, executor);
            return executor;
        }

        // 尝试从 HTTP 加载
        executor = loadFromHttp(toolCode);
        if (executor != null) {
            cacheExecutor(toolCode, executor);
        }

        return executor;
    }

    /**
     * 注册执行器（手动注册，不走缓存）
     */
    public void register(CustomToolExecutor executor) {
        if (executor == null || executor.getToolCode() == null) {
            return;
        }
        cacheExecutor(executor.getToolCode(), executor);
        log.info("手动注册自定义工具: toolCode={}", executor.getToolCode());
    }

    /**
     * 移除执行器
     */
    public void unregister(String toolCode) {
        executorCache.remove(toolCode);
        log.info("移除自定义工具: toolCode={}", toolCode);
    }

    /**
     * 列出所有已加载的执行器
     */
    public List<CustomToolExecutor> listAll() {
        cleanExpiredCache();
        return executorCache.values().stream()
                .map(c -> c.executor)
                .toList();
    }

    /**
     * 执行脚本类型工具
     */
    public Object executeScript(CustomToolTypeEnum scriptType, String script, Map<String, Object> args) {
        return scriptExecutor.execute(scriptType, script, args);
    }

    // ==================== 私有方法 ====================

    private void loadSpiExecutors() {
        try {
            ServiceLoader<CustomToolExecutor> loader = ServiceLoader.load(CustomToolExecutor.class);
            for (CustomToolExecutor executor : loader) {
                register(executor);
                log.info("SPI 加载自定义工具: toolCode={}", executor.getToolCode());
            }
        } catch (Exception e) {
            log.warn("SPI 加载失败: {}", e.getMessage());
        }
    }

    private CustomToolExecutor loadFromJar(String toolCode) {
        Path toolsDir = Path.of(CUSTOM_TOOLS_DIR);
        if (!Files.exists(toolsDir)) {
            return null;
        }

        try {
            File[] jars = toolsDir.toFile().listFiles((dir, name) -> name.endsWith(".jar"));
            if (jars == null || jars.length == 0) {
                return null;
            }

            for (File jar : jars) {
                CustomToolExecutor executor = loadFromJarFile(jar, toolCode);
                if (executor != null) {
                    return executor;
                }
            }
        } catch (Exception e) {
            log.warn("从 JAR 加载工具失败: toolCode={}, error={}", toolCode, e.getMessage());
        }
        return null;
    }

    private CustomToolExecutor loadFromJarFile(File jar, String toolCode) {
        try {
            URL jarUrl = jar.toURI().toURL();
            URLClassLoader classLoader = classLoaderCache.computeIfAbsent(jar.getAbsolutePath(),
                    k -> new URLClassLoader(new URL[]{jarUrl}, getClass().getClassLoader()));

            ServiceLoader<CustomToolExecutor> loader = ServiceLoader.load(CustomToolExecutor.class, classLoader);
            for (CustomToolExecutor executor : loader) {
                if (executor.getToolCode().equals(toolCode)) {
                    return executor;
                }
            }
        } catch (Exception e) {
            log.debug("从 {} 加载 {} 失败: {}", jar.getName(), toolCode, e.getMessage());
        }
        return null;
    }

    private CustomToolExecutor loadFromHttp(String toolCode) {
        // HTTP 类型通过 HttpCustomToolExecutor 动态调用，这里返回 null
        // 实际调用由 CustomToolExecutorWrapper 处理
        return null;
    }

    private void cacheExecutor(String toolCode, CustomToolExecutor executor) {
        executorCache.put(toolCode, new CachedExecutor(executor, System.currentTimeMillis()));
    }

    private void cleanExpiredCache() {
        long now = System.currentTimeMillis();
        executorCache.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                log.debug("清理过期工具缓存: toolCode={}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * 缓存的执行器
     */
    private record CachedExecutor(CustomToolExecutor executor, long createTime) {
        private static final long TTL_MS = 5 * 60 * 1000; // 5 分钟

        boolean isExpired() {
            return System.currentTimeMillis() - createTime > TTL_MS;
        }
    }
}
