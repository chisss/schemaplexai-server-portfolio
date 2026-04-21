package com.schemaplexai.service.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示场景数据服务
 */
@Service
public class DemoScenarioDatasetService {

    private static final String DATASET_RESOURCE_PATH = "demo/scene-datasets.json";

    private final Map<String, Map<String, Object>> datasetMap;

    public DemoScenarioDatasetService(ObjectMapper objectMapper) {
        this.datasetMap = loadDatasets(objectMapper);
    }

    public List<Map<String, Object>> listAll() {
        return datasetMap.values().stream().toList();
    }

    public Map<String, Object> getBySceneKey(String sceneKey) {
        if (!StringUtils.hasText(sceneKey)) {
            return Map.of();
        }
        return datasetMap.getOrDefault(sceneKey.trim(), Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadDatasets(ObjectMapper objectMapper) {
        try {
            ClassPathResource resource = new ClassPathResource(DATASET_RESOURCE_PATH);
            try (InputStream inputStream = resource.getInputStream()) {
                List<Map<String, Object>> datasets = objectMapper.readValue(inputStream, new TypeReference<>() {
                });
                Map<String, Map<String, Object>> result = new LinkedHashMap<>();
                for (Map<String, Object> dataset : datasets) {
                    if (dataset == null) {
                        continue;
                    }
                    Object sceneKey = dataset.get("sceneKey");
                    if (sceneKey == null || !StringUtils.hasText(String.valueOf(sceneKey))) {
                        continue;
                    }
                    result.put(String.valueOf(sceneKey).trim(), new LinkedHashMap<>(dataset));
                }
                return result;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("加载演示场景数据失败: " + exception.getMessage(), exception);
        }
    }
}
