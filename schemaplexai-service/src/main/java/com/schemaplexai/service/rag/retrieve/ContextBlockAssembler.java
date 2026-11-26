package com.schemaplexai.service.rag.retrieve;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 引用块装配（LLM 生成层的输入格式）。
 *
 * <p>格式刻意固定为：
 * <pre>
 * [1] 【来源：额度说明.pdf › 1.1 利率 › #0】
 * 年化利率 3.6% 起……
 * </pre>
 * 三个作用：
 * <ol>
 *   <li>编号让模型能写出 {@code 依据[1][2]}，引用可回溯；</li>
 *   <li>锚点让模型知道每段的出处，跨文件对比时不会张冠李戴；</li>
 *   <li>空行分隔让模型能区分片段边界，不会把两段合成一句。</li>
 * </ol>
 */
@Component
public class ContextBlockAssembler {

    public String assemble(List<RetrievalCandidate> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievalCandidate chunk = chunks.get(i);
            sb.append('[').append(i + 1).append(']');
            String header = buildHeader(chunk);
            if (StringUtils.hasText(header)) {
                sb.append(' ').append(header);
            }
            sb.append('\n').append(stripAnchor(chunk.text())).append('\n');
            if (i < chunks.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String buildHeader(RetrievalCandidate chunk) {
        String fileName = chunk.fileName();
        String section = chunk.section();
        if (!StringUtils.hasText(fileName) && !StringUtils.hasText(section)) {
            return "";
        }
        return "【来源：" + (StringUtils.hasText(fileName) ? fileName : "未知文件")
                + (StringUtils.hasText(section) ? " › " + section : "") + "】";
    }

    /**
     * 去掉 chunk 自带的锚点，避免引用块里出现两层来源（[1] 一行 + 正文里的【来源…】）。
     * 只剥离开头第一行的锚点，正文中间出现的方括号保持不动。
     */
    private String stripAnchor(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("【来源：")) {
            int end = trimmed.indexOf('】');
            if (end > 0) {
                return trimmed.substring(end + 1).stripLeading();
            }
        }
        return trimmed;
    }
}
