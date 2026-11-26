package com.schemaplexai.service.rag.ingest.split;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.ingest.IngestCategory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节语义切分（普通文档 / OCR 文本）。
 *
 * <p>和"固定窗口切分"的区别在于：先按标题把文档切成语义块，再在块内按预算聚合。
 * 这样"违约责任"这一节不会被拦腰截断到两个 chunk 里——指代跑偏的第一大来源就是
 * chunk 边界切断了上下文主语。
 *
 * <p>标题识别覆盖中文文档的常见写法：{@code # 标题}、{@code 1. / 1.1 / 1.1.1}、
 * {@code 一、}、{@code 第一条}、{@code 第X章}。
 */
@Component
public class SectionSemanticSplitter {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern NUMERIC_HEADING = Pattern.compile("^(\\d+(?:\\.\\d+){0,3})[.、)\\s]+\\S.{0,60}$");
    private static final Pattern CHINESE_NUM_HEADING = Pattern.compile("^([一二三四五六七八九十]+)[、.]\\s*\\S.{0,60}$");
    private static final Pattern CHAPTER_HEADING = Pattern.compile("^第([一二三四五六七八九十百零〇\\d]+)[章节条]\\s*\\S.{0,60}$");

    /**
     * @param text     解析后的全文
     * @param fileName 文件名（用于锚点）
     * @param settings 运行时配置
     */
    public List<RoutedChunk> split(String text, String fileName, RagRuntimeSettings settings) {
        List<RoutedChunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int budget = Math.max(64, settings.getChunkSize());
        int overlap = Math.max(0, Math.min(settings.getChunkOverlap(), budget / 2));
        boolean anchorEnabled = settings.isChunkAnchorEnabled();

        for (Section section : parseSections(text)) {
            List<String> sentences = SplitSupport.sentences(section.body());
            if (sentences.isEmpty()) {
                continue;
            }
            List<String> windows = SplitSupport.window(sentences, "", budget, overlap);
            for (String window : windows) {
                String body = window.trim();
                if (body.isBlank()) {
                    continue;
                }
                int index = chunks.size();
                String anchor = RoutedChunk.buildAnchor(fileName, section.path(), index, anchorEnabled);
                chunks.add(RoutedChunk.create(index, section.path(), anchor, body, body, IngestCategory.DOCUMENT));
            }
        }
        return chunks;
    }

    /** 章节结构：标题层级路径 + 正文 */
    record Section(String path, String body) {
    }

    List<Section> parseSections(String text) {
        List<Section> sections = new ArrayList<>();
        String[] lines = text.split("\n", -1);

        List<String> headingStack = new ArrayList<>();
        StringBuilder body = new StringBuilder();

        for (String line : lines) {
            Heading heading = detectHeading(line);
            if (heading != null) {
                flush(sections, headingStack, body);
                while (headingStack.size() >= heading.level()) {
                    headingStack.remove(headingStack.size() - 1);
                }
                headingStack.add(heading.title());
            } else {
                body.append(line).append('\n');
            }
        }
        flush(sections, headingStack, body);
        return sections;
    }

    private void flush(List<Section> sections, List<String> headingStack, StringBuilder body) {
        String content = body.toString().trim();
        body.setLength(0);
        if (content.isBlank()) {
            return;
        }
        String path = headingStack.isEmpty() ? "正文" : String.join(" › ", headingStack);
        sections.add(new Section(path, content));
    }

    private Heading detectHeading(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.length() > 120) {
            return null;
        }
        Matcher markdown = MARKDOWN_HEADING.matcher(trimmed);
        if (markdown.matches()) {
            return new Heading(markdown.group(1).length(), markdown.group(2).trim());
        }
        Matcher chapter = CHAPTER_HEADING.matcher(trimmed);
        if (chapter.matches()) {
            return new Heading(1, trimmed);
        }
        Matcher chinese = CHINESE_NUM_HEADING.matcher(trimmed);
        if (chinese.matches()) {
            return new Heading(2, trimmed);
        }
        Matcher numeric = NUMERIC_HEADING.matcher(trimmed);
        if (numeric.matches()) {
            int level = Math.min(4, numeric.group(1).split("\\.").length);
            return new Heading(level, trimmed);
        }
        return null;
    }

    private record Heading(int level, String title) {
    }
}
