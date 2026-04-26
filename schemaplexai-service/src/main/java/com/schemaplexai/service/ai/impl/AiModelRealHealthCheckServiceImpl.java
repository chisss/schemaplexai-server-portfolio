package com.schemaplexai.service.ai.impl;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.vo.system.AiModelRealHealthCheckVO;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.AiModelHealthErrorClassifier;
import com.schemaplexai.service.ai.AiModelRealHealthCheckService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 模型真实协议健康检查服务实现
 */
@Service
@RequiredArgsConstructor
public class AiModelRealHealthCheckServiceImpl implements AiModelRealHealthCheckService {

    private static final String HEALTH_PROMPT = "Return exactly: pong";

    private final AiModelMapper aiModelMapper;
    private final AIModelRouter aiModelRouter;
    private final AiModelHealthErrorClassifier errorClassifier;

    @Override
    public AiModelRealHealthCheckVO testRealConnectivity(String modelId) {
        AiModel model = aiModelMapper.selectById(modelId);
        if (model == null) {
            throw new BusinessException(ResultCode.CONFIG_NOT_FOUND, "AI模型不存在");
        }
        AiModelConfig config = AiModelConfig.from(model);
        AiModelRealHealthCheckVO result = baseResult(model, config);
        long startMs = System.currentTimeMillis();
        try {
            ChatModel chatModel = aiModelRouter.buildChatModel(config);
            ChatResponse response = chatModel.chat(List.of(
                    SystemMessage.from("You are a health check endpoint. Keep response minimal."),
                    UserMessage.from(HEALTH_PROMPT)
            ));
            long inputTokens = extractInputTokens(response);
            long outputTokens = extractOutputTokens(response);
            result.setSuccess(true);
            result.setCategory(AiModelHealthErrorClassifier.OK);
            result.setLatencyMs(System.currentTimeMillis() - startMs);
            result.setInputTokens(inputTokens);
            result.setOutputTokens(outputTokens);
            result.setEstimatedCost(calculateCost(model, inputTokens, outputTokens));
            result.setMessage("真实协议探测成功");
            if (!Boolean.TRUE.equals(result.getPriceConfigured())) {
                result.setCategory(AiModelHealthErrorClassifier.MISSING_PRICE_CONFIG);
                result.setMessage("真实协议探测成功，但模型价格缺失，成本不可信");
            }
            return result;
        } catch (Exception exception) {
            result.setSuccess(false);
            result.setLatencyMs(System.currentTimeMillis() - startMs);
            result.setInputTokens(0L);
            result.setOutputTokens(0L);
            result.setEstimatedCost(BigDecimal.ZERO);
            result.setCategory(errorClassifier.classify(exception));
            result.setMessage(trim(exception.getMessage()));
            return result;
        }
    }

    private AiModelRealHealthCheckVO baseResult(AiModel model, AiModelConfig config) {
        AiModelRealHealthCheckVO result = new AiModelRealHealthCheckVO();
        result.setModelId(model.getId());
        result.setModelName(model.getName());
        result.setProtocol(config.getProtocol());
        result.setBaseUrl(config.getBaseUrl());
        result.setPriceConfigured(model.getInputPrice() != null && model.getOutputPrice() != null);
        result.setTestedAt(LocalDateTime.now());
        return result;
    }

    private long extractInputTokens(ChatResponse response) {
        return response != null && response.metadata() != null && response.metadata().tokenUsage() != null
                ? response.metadata().tokenUsage().inputTokenCount()
                : 0L;
    }

    private long extractOutputTokens(ChatResponse response) {
        return response != null && response.metadata() != null && response.metadata().tokenUsage() != null
                ? response.metadata().tokenUsage().outputTokenCount()
                : 0L;
    }

    private BigDecimal calculateCost(AiModel model, long inputTokens, long outputTokens) {
        BigDecimal inputPrice = model.getInputPrice() == null ? BigDecimal.ZERO : model.getInputPrice();
        BigDecimal outputPrice = model.getOutputPrice() == null ? BigDecimal.ZERO : model.getOutputPrice();
        return inputPrice.multiply(BigDecimal.valueOf(inputTokens))
                .add(outputPrice.multiply(BigDecimal.valueOf(outputTokens)))
                .setScale(6, RoundingMode.HALF_UP);
    }

    private String trim(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
