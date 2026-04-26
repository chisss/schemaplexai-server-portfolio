package com.schemaplexai.service.ai;

import com.schemaplexai.service.storage.DocumentStorageService;
import com.schemaplexai.service.util.FileContentExtractor;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * 多模态消息构建器
 *
 * <p>根据模型是否支持多模态，将附件转换为 {@link ImageContent}（图片）或文本摘要（文档）。
 * 图片走视觉通道，其余文件继续走文本提取通道。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultimodalMessageBuilder {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    private final DocumentStorageService documentStorageService;
    private final FileContentExtractor fileContentExtractor;

    /**
     * 构建用户消息：多模态模型将图片附件转为 ImageContent，文档附件转为文本摘要追加到文本中。
     * 非多模态模型退化为纯文本（与原有行为一致）。
     *
     * @param userText      用户输入文本
     * @param attachmentIds 附件 ID 列表（对象存储 key）
     * @param multimodal    模型是否支持多模态
     * @return 构建好的 UserMessage
     */
    public UserMessage build(String userText, List<String> attachmentIds, boolean multimodal) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return UserMessage.from(userText);
        }
        if (!multimodal) {
            String textSummary = buildTextSummary(attachmentIds);
            return StringUtils.hasText(textSummary)
                    ? UserMessage.from(userText + "\n\n" + textSummary)
                    : UserMessage.from(userText);
        }
        return buildMultimodalMessage(userText, attachmentIds);
    }

    private UserMessage buildMultimodalMessage(String userText, List<String> attachmentIds) {
        List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
        contents.add(TextContent.from(userText));
        StringBuilder textAppend = new StringBuilder();

        for (String attachmentId : attachmentIds) {
            if (!StringUtils.hasText(attachmentId)) continue;
            String ext = resolveExtension(attachmentId).toLowerCase();
            if (IMAGE_EXTENSIONS.contains(ext)) {
                buildImageContent(attachmentId, ext).ifPresent(contents::add);
            } else {
                String extracted = extractText(attachmentId);
                if (StringUtils.hasText(extracted)) {
                    textAppend.append("\n\n### ").append(attachmentId).append("\n").append(extracted);
                }
            }
        }

        if (!textAppend.isEmpty()) {
            contents.set(0, TextContent.from(userText + textAppend));
        }
        return UserMessage.from(contents);
    }

    private java.util.Optional<ImageContent> buildImageContent(String objectKey, String ext) {
        try (InputStream is = documentStorageService.getObject(
                documentStorageService.getDefaultBucket(), objectKey)) {
            byte[] bytes = is.readAllBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String mimeType = resolveMimeType(ext);
            return java.util.Optional.of(ImageContent.from(base64, mimeType));
        } catch (Exception e) {
            log.warn("[Multimodal] 读取图片附件失败: objectKey={}, error={}", objectKey, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * 提取附件文本摘要字符串，供非多模态场景拼入 SystemPrompt。
     * 图片文件跳过（无法文本化），仅处理文档类附件。
     */
    public String extractDocumentSummary(List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return "";
        List<String> sections = new ArrayList<>();
        for (String attachmentId : attachmentIds) {
            if (!StringUtils.hasText(attachmentId)) continue;
            String ext = resolveExtension(attachmentId).toLowerCase();
            if (IMAGE_EXTENSIONS.contains(ext)) continue; // 图片跳过
            String extracted = extractText(attachmentId);
            if (StringUtils.hasText(extracted)) {
                sections.add("### " + resolveFileName(attachmentId) + "\n" + extracted);
            }
        }
        return String.join("\n\n", sections);
    }

    private static String resolveFileName(String objectKey) {
        int slash = objectKey.lastIndexOf('/');
        return slash >= 0 && slash < objectKey.length() - 1 ? objectKey.substring(slash + 1) : objectKey;
    }

    private String buildTextSummary(List<String> attachmentIds) {
        List<String> sections = new ArrayList<>();
        for (String attachmentId : attachmentIds) {
            if (!StringUtils.hasText(attachmentId)) continue;
            String extracted = extractText(attachmentId);
            if (StringUtils.hasText(extracted)) {
                sections.add("### " + attachmentId + "\n" + extracted);
            }
        }
        return String.join("\n\n", sections);
    }

    private String extractText(String objectKey) {
        try (InputStream is = documentStorageService.getObject(
                documentStorageService.getDefaultBucket(), objectKey)) {
            return fileContentExtractor.extract(objectKey, is);
        } catch (Exception e) {
            log.warn("[Multimodal] 读取文本附件失败: objectKey={}, error={}", objectKey, e.getMessage());
            return "";
        }
    }

    private static String resolveExtension(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        return dot >= 0 && dot < objectKey.length() - 1 ? objectKey.substring(dot + 1) : "";
    }

    private static String resolveMimeType(String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png"         -> "image/png";
            case "gif"         -> "image/gif";
            case "webp"        -> "image/webp";
            case "bmp"         -> "image/bmp";
            default            -> "image/jpeg";
        };
    }
}
