package com.schemaplexai.service.vector.impl;

import com.schemaplexai.service.vector.ScoringService;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 基于 ONNX Cross-Encoder 的内置评分（Reranker）服务实现
 *
 * <p>系统内置 ONNX Scoring 模型，支持两种加载方式（按优先级）：
 * <ol>
 *   <li>Classpath 资源：从 JAR 内 {@code /onnx-scoring/model.onnx} 和
 *       {@code /onnx-scoring/tokenizer.json} 自动提取到临时目录加载</li>
 *   <li>文件系统路径：通过 {@code ai.scoring.model-path} 和
 *       {@code ai.scoring.tokenizer-path} 指定外部文件路径（运维部署场景）</li>
 * </ol>
 *
 * <p>模型惰性初始化：首次调用 {@link #rerank} 时才加载，避免启动变慢。
 */
@Slf4j
@Service
public class OnnxScoringServiceImpl implements ScoringService {

    /** 模型名称标识 */
    public static final String BUILTIN_MODEL_ID = "onnx-scoring";
    public static final String BUILTIN_MODEL_NAME = "ONNX Cross-Encoder Reranker";

    /** Classpath 中内置模型资源路径 */
    private static final String CLASSPATH_MODEL = "/onnx-scoring/model.onnx";
    private static final String CLASSPATH_TOKENIZER = "/onnx-scoring/tokenizer.json";

    @Value("${ai.scoring.model-path:}")
    private String externalModelPath;

    @Value("${ai.scoring.tokenizer-path:}")
    private String externalTokenizerPath;

    @Value("${ai.scoring.min-score:0.0}")
    private double minScore;

    private volatile OnnxScoringModel scoringModel;
    private volatile boolean initAttempted;

    @Override
    public List<ScoredPassage> rerank(String query, List<String> passages, int topN) {
        if (!isAvailable() || passages == null || passages.isEmpty()) {
            return passages == null ? List.of()
                    : passages.stream().map(p -> new ScoredPassage(p, 0.0)).limit(topN).toList();
        }

        OnnxScoringModel model = getOrInitModel();
        if (model == null) {
            // 模型加载失败，返回原始顺序
            return passages.stream().map(p -> new ScoredPassage(p, 0.0)).limit(topN).toList();
        }

        try {
            List<ScoredPassage> scored = IntStream.range(0, passages.size())
                    .mapToObj(i -> {
                        String passage = passages.get(i);
                        double score = model.score(query, passage).content();
                        return new ScoredPassage(passage, score);
                    })
                    .filter(sp -> sp.score() >= minScore)
                    .sorted() // ScoredPassage 实现了 Comparable，降序
                    .limit(topN)
                    .toList();

            if (scored.isEmpty()) {
                log.debug("Reranker 未保留任何结果，回退原始顺序: 输入 {} 条, topN={}", passages.size(), topN);
                return passages.stream()
                        .map(p -> new ScoredPassage(p, 0.0))
                        .limit(topN)
                        .toList();
            }

            log.debug("Reranker 精排完成: 输入 {} 条, 输出 {} 条, topN={}", passages.size(), scored.size(), topN);
            return scored;
        } catch (Exception e) {
            log.warn("Reranker 精排异常，返回原始顺序: {}", e.getMessage());
            return passages.stream().map(p -> new ScoredPassage(p, 0.0)).limit(topN).toList();
        }
    }

    @Override
    public boolean isAvailable() {
        // 已尝试初始化但失败
        if (initAttempted && scoringModel == null) {
            return false;
        }
        // 检查是否有可加载的模型来源（classpath 或外部路径）
        return hasClasspathModel() || hasExternalModel();
    }

    /**
     * 获取内置模型ID
     */
    public String getModelId() {
        return BUILTIN_MODEL_ID;
    }

    /**
     * 获取内置模型名称
     */
    public String getModelName() {
        return BUILTIN_MODEL_NAME;
    }

    private boolean hasClasspathModel() {
        return getClass().getResource(CLASSPATH_MODEL) != null
                && getClass().getResource(CLASSPATH_TOKENIZER) != null;
    }

    private boolean hasExternalModel() {
        return externalModelPath != null && !externalModelPath.isBlank()
                && externalTokenizerPath != null && !externalTokenizerPath.isBlank()
                && Files.exists(Path.of(externalModelPath))
                && Files.exists(Path.of(externalTokenizerPath));
    }

    private OnnxScoringModel getOrInitModel() {
        if (scoringModel != null) {
            return scoringModel;
        }
        synchronized (this) {
            if (scoringModel != null) {
                return scoringModel;
            }
            if (initAttempted) {
                return null;
            }
            initAttempted = true;
            try {
                // 优先从 classpath 加载内置模型
                if (hasClasspathModel()) {
                    return initFromClasspath();
                }
                // 降级使用外部文件路径
                if (hasExternalModel()) {
                    return initFromExternalPath();
                }
                log.warn("ONNX Scoring 模型未找到: classpath 和外部路径均不可用");
                return null;
            } catch (Exception e) {
                log.error("ONNX Scoring 模型加载失败: {}", e.getMessage(), e);
                return null;
            }
        }
    }

    /**
     * 从 classpath 资源提取到临时目录后加载
     */
    private OnnxScoringModel initFromClasspath() throws Exception {
        Path tempDir = Files.createTempDirectory("onnx-scoring-");
        tempDir.toFile().deleteOnExit();

        Path modelFile = tempDir.resolve("model.onnx");
        Path tokenizerFile = tempDir.resolve("tokenizer.json");

        try (InputStream modelStream = getClass().getResourceAsStream(CLASSPATH_MODEL);
             InputStream tokenizerStream = getClass().getResourceAsStream(CLASSPATH_TOKENIZER)) {
            if (modelStream == null || tokenizerStream == null) {
                log.error("Classpath ONNX Scoring 资源读取失败");
                return null;
            }
            Files.copy(modelStream, modelFile, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(tokenizerStream, tokenizerFile, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("从 classpath 加载 ONNX Scoring 模型: {}", tempDir);
        this.scoringModel = new OnnxScoringModel(modelFile.toString(), tokenizerFile.toString());
        log.info("ONNX Scoring 模型加载完成（classpath 模式）");
        return this.scoringModel;
    }

    /**
     * 从外部文件路径加载
     */
    private OnnxScoringModel initFromExternalPath() {
        log.info("从外部路径加载 ONNX Scoring 模型: model={}, tokenizer={}", externalModelPath, externalTokenizerPath);
        this.scoringModel = new OnnxScoringModel(externalModelPath, externalTokenizerPath);
        log.info("ONNX Scoring 模型加载完成（外部路径模式）");
        return this.scoringModel;
    }
}
