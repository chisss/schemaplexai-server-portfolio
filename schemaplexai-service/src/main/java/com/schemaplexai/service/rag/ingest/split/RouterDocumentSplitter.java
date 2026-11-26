package com.schemaplexai.service.rag.ingest.split;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.ingest.GatewayDecision;
import com.schemaplexai.service.rag.ingest.IngestCategory;
import com.schemaplexai.service.rag.ingest.parse.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 路由式切分器（设计稿「层 2」的核心）。
 *
 * <p>按分类选策略，并在租户关掉某项策略时退回通用窗口切分（而不是不切分）：
 * <ul>
 *   <li>CODE → {@link CodeAstSplitter}（AST 单元）</li>
 *   <li>EXCEL → {@link TableBlockSplitter}（sheet + 表格块，重复表头）</li>
 *   <li>DOCUMENT / IMAGE → {@link SectionSemanticSplitter}（章节语义 + 来源锚点）</li>
 * </ul>
 *
 * <p>兜底窗口切分同样带锚点，保证任何情况下 chunk 都"自包含"。
 */
@Slf4j
@Component
public class RouterDocumentSplitter {

    private final SectionSemanticSplitter sectionSplitter;
    private final CodeAstSplitter codeSplitter;
    private final TableBlockSplitter tableSplitter;

    public RouterDocumentSplitter(SectionSemanticSplitter sectionSplitter,
                                  CodeAstSplitter codeSplitter,
                                  TableBlockSplitter tableSplitter) {
        this.sectionSplitter = sectionSplitter;
        this.codeSplitter = codeSplitter;
        this.tableSplitter = tableSplitter;
    }

    public List<RoutedChunk> split(ParsedDocument parsed, String fileName, RagRuntimeSettings settings) {
        if (parsed == null || !parsed.hasText()) {
            return List.of();
        }
        IngestCategory category = parsed.category();
        String text = parsed.text();

        return switch (category) {
            case CODE -> settings.isCodeAstSplitEnabled()
                    ? normalize(codeSplitter.split(text, fileName, settings), category)
                    : normalize(sectionSplitter.split(text, fileName, settings), category);
            case EXCEL -> settings.isTableBlockSplitEnabled()
                    ? normalize(tableSplitter.split(text, fileName, settings), category)
                    : normalize(sectionSplitter.split(text, fileName, settings), category);
            case DOCUMENT, IMAGE -> normalize(sectionSplitter.split(text, fileName, settings), category);
        };
    }

    /**
     * 统一分类标记：section/table 切分器产出的 category 是固定的，
     * 这里以解析结果为准（例如扫描件 OCR 后分类是 IMAGE，但可能走了表格块切分）。
     */
    private List<RoutedChunk> normalize(List<RoutedChunk> chunks, IngestCategory category) {
        if (chunks.isEmpty()) {
            log.warn("切分结果为空: category={}", category.code());
            return chunks;
        }
        return chunks.stream()
                .map(chunk -> chunk.category() == category ? chunk
                        : new RoutedChunk(chunk.index(), chunk.section(), chunk.anchor(), chunk.body(),
                        chunk.text(), chunk.bm25Text(), category))
                .toList();
    }

    /** 供摄入层判断"解析成功但切不出 chunk"的异常场景 */
    public static String parserIdOf(ParsedDocument parsed) {
        return parsed == null ? GatewayDecision.PARSER_TIKA : parsed.parserId();
    }
}
