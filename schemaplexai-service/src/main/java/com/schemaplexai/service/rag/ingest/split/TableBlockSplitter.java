package com.schemaplexai.service.rag.ingest.split;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.ingest.IngestCategory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格块切分（Excel/CSV 转出的 Markdown）。
 *
 * <p>核心约束：<b>表头必须跟着数据走</b>。一张 200 行的额度表如果按行窗口硬切，
 * 后面几段只有数字没有列名，LLM 根本读不懂"50000 是哪一列"。
 * 因此每个 chunk 都重复表头与前导说明（sheet 名、合并单元格提示）。
 *
 * <p>切分单位是 sheet + 表格块，不会把多个 sheet 塞进同一个 chunk。
 */
@Component
public class TableBlockSplitter {

    private static final String SHEET_PREFIX = "## Sheet:";

    public List<RoutedChunk> split(String markdown, String fileName, RagRuntimeSettings settings) {
        List<RoutedChunk> chunks = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return chunks;
        }
        int budget = Math.max(64, settings.getChunkSize());
        boolean anchorEnabled = settings.isChunkAnchorEnabled();

        for (TableBlock block : parseBlocks(markdown)) {
            List<String> chunkTexts = buildChunks(block, budget);
            for (String body : chunkTexts) {
                int index = chunks.size();
                String anchor = RoutedChunk.buildAnchor(fileName, block.sheetName(), index, anchorEnabled);
                chunks.add(RoutedChunk.create(index, block.sheetName(), anchor, body, body,
                        IngestCategory.EXCEL));
            }
        }
        return chunks;
    }

    private List<TableBlock> parseBlocks(String markdown) {
        List<TableBlock> blocks = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);

        String sheetName = null;
        List<String> preamble = new ArrayList<>();
        List<String> tableHead = new ArrayList<>();
        List<String> rows = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(SHEET_PREFIX)) {
                if (sheetName != null) {
                    blocks.add(new TableBlock(sheetName, preamble, tableHead, rows));
                }
                sheetName = trimmed.substring(SHEET_PREFIX.length()).trim();
                preamble = new ArrayList<>();
                tableHead = new ArrayList<>();
                rows = new ArrayList<>();
                continue;
            }
            if (sheetName == null) {
                // 没有 sheet 标题的表格（如用户直接粘贴），也按一个块处理
                if (trimmed.startsWith("|")) {
                    sheetName = "表格";
                    preamble = new ArrayList<>();
                    tableHead = new ArrayList<>();
                    rows = new ArrayList<>();
                } else {
                    continue;
                }
            }
            if (trimmed.startsWith("|")) {
                if (tableHead.isEmpty()) {
                    tableHead.add(trimmed);
                } else if (tableHead.size() == 1 && isSeparator(trimmed)) {
                    tableHead.add(trimmed);
                } else {
                    rows.add(trimmed);
                }
            } else if (!trimmed.isEmpty()) {
                preamble.add(trimmed);
            }
        }
        if (sheetName != null) {
            blocks.add(new TableBlock(sheetName, preamble, tableHead, rows));
        }
        return blocks;
    }

    private List<String> buildChunks(TableBlock block, int budget) {
        List<String> chunks = new ArrayList<>();
        String head = renderHead(block);
        if (head.isBlank()) {
            return chunks;
        }
        if (block.rows().isEmpty()) {
            chunks.add(head.trim());
            return chunks;
        }

        StringBuilder current = new StringBuilder();
        for (String row : block.rows()) {
            if (SplitSupport.exceeds(head + current + row, budget) && !current.isEmpty()) {
                chunks.add((head + current).trim());
                current = new StringBuilder();
            }
            current.append(row).append('\n');
        }
        if (!current.isEmpty()) {
            chunks.add((head + current).trim());
        }
        return chunks;
    }

    private String renderHead(TableBlock block) {
        StringBuilder head = new StringBuilder();
        // sheet 名是表格语义的一部分（"额度表"和"利率表"同样的列名含义完全不同），必须进正文
        head.append(SHEET_PREFIX).append(' ').append(block.sheetName()).append('\n');
        for (String line : block.preamble()) {
            head.append(line).append('\n');
        }
        for (String line : block.tableHead()) {
            head.append(line).append('\n');
        }
        return head.toString();
    }

    private boolean isSeparator(String line) {
        String compact = line.replaceAll("[|\\s]", "");
        return !compact.isEmpty() && compact.matches("[-:]+");
    }

    /**
     * @param sheetName sheet 名（作为 section 与锚点）
     * @param preamble  表头前的说明行（含合并单元格提示、截断告警）
     * @param tableHead 表头行 + 分隔行
     * @param rows      数据行
     */
    record TableBlock(String sheetName, List<String> preamble, List<String> tableHead, List<String> rows) {
    }
}
