package com.schemaplexai.service.rag.ingest.split;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.ingest.IngestCategory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码 AST 切分（轻量实现，不引入完整语法分析器）。
 *
 * <p>为什么不用真 AST：本平台要覆盖 java/py/js/ts/go 多语言，为每种语言挂一个 parser 会让
 * 依赖体积和维护成本失控。这里用"大括号深度 + 声明行正则"识别类/方法边界，
 * 对主流语言足够，且纯 Java 无额外依赖。
 *
 * <p>额外的 BM25 价值：代码检索的痛点是人写 {@code payAmount}、搜 {@code pay amount} 找不到。
 * 因此每个 chunk 额外产出 {@code bm25Text}（驼峰/下划线拆分 + 符号替换），只给 BM25 用，
 * 不影响向量与 LLM 看到的原文。
 */
@Component
public class CodeAstSplitter {

    private static final Pattern JAVA_TYPE = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed)\\s+)*"
                    + "(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|static|final|synchronized|abstract|default|native)\\s+)*"
                    + "[\\w<>\\[\\],\\s\\.\\?]+\\s+(\\w+)\\s*\\([^;]*\\)\\s*(?:throws\\s+[\\w\\s,\\.]+)?\\{?\\s*$");
    private static final Pattern PYTHON_DEF = Pattern.compile("^\\s*(?:async\\s+)?(def|class)\\s+(\\w+)");
    private static final Pattern JS_FUNCTION = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)|^\\s*(?:export\\s+)?const\\s+(\\w+)\\s*=\\s*(?:async\\s*)?\\(");
    private static final Pattern GO_FUNC = Pattern.compile("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?(\\w+)");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");
    private static final Pattern NON_IDENTIFIER = Pattern.compile("[^A-Za-z0-9_]+");

    public List<RoutedChunk> split(String source, String fileName, RagRuntimeSettings settings) {
        List<RoutedChunk> chunks = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return chunks;
        }
        int budget = Math.max(64, settings.getChunkSize());
        int overlap = Math.max(0, Math.min(settings.getChunkOverlap(), budget / 2));
        boolean anchorEnabled = settings.isChunkAnchorEnabled();

        List<CodeUnit> units = extractUnits(source);
        for (CodeUnit unit : units) {
            List<String> parts = SplitSupport.exceeds(unit.body(), budget)
                    ? splitLongUnit(unit, budget, overlap)
                    : List.of(unit.body());

            for (String part : parts) {
                String body = part.trim();
                if (body.isBlank()) {
                    continue;
                }
                int index = chunks.size();
                String anchor = RoutedChunk.buildAnchor(fileName, unit.name(), index, anchorEnabled);
                chunks.add(RoutedChunk.create(index, unit.name(), anchor, body,
                        identifierSplit(body), IngestCategory.CODE));
            }
        }
        return chunks;
    }

    /** 抽取代码单元：类型声明、函数/方法；无法识别时整文件兜底为一个单元 */
    List<CodeUnit> extractUnits(String source) {
        List<CodeUnit> units = new ArrayList<>();
        String[] lines = source.split("\n", -1);

        String currentName = "文件";
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean collecting = false;

        for (String line : lines) {
            String name = detectUnitName(line);
            if (name != null && !collecting) {
                collecting = true;
                currentName = name;
                current.setLength(0);
            }

            if (collecting) {
                current.append(line).append('\n');
                depth += braceDelta(line);
                // 顶层单元在括号回到 0（或 Python 靠 dedent 结束，见下方处理）时收束
                if (depth <= 0 && isBlockClosed(line)) {
                    String body = current.toString();
                    if (!body.isBlank()) {
                        units.add(new CodeUnit(currentName, body));
                    }
                    current.setLength(0);
                    collecting = false;
                    depth = 0;
                }
            } else if (!line.isBlank()) {
                current.append(line).append('\n');
            }
        }

        if (!current.isEmpty() && !current.toString().isBlank()) {
            units.add(new CodeUnit(currentName, current.toString()));
        }
        if (units.isEmpty()) {
            units.add(new CodeUnit("文件", source));
        }
        return units;
    }

    /** 超长单元（几千行的方法/巨型类）按行窗口二次切分，保留签名行作为每个 chunk 的表头 */
    private List<String> splitLongUnit(CodeUnit unit, int budget, int overlap) {
        String[] lines = unit.body().split("\n", -1);
        String signature = lines.length > 0 ? lines[0] : "";
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        List<String> window = new ArrayList<>();

        for (String line : lines) {
            if (SplitSupport.exceeds(signature + "\n" + current + line, budget) && !current.isEmpty()) {
                chunks.add(current.toString());
                current = new StringBuilder();
                for (int i = Math.max(0, window.size() - overlapLines(overlap)); i < window.size(); i++) {
                    current.append(window.get(i)).append('\n');
                }
            }
            current.append(line).append('\n');
            window.add(line);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private int overlapLines(int overlapTokens) {
        return Math.max(0, Math.min(20, overlapTokens / 20));
    }

    private String detectUnitName(String line) {
        Matcher type = JAVA_TYPE.matcher(line);
        if (type.find()) {
            return type.group(1);
        }
        Matcher method = JAVA_METHOD.matcher(line);
        if (method.find() && !line.trim().startsWith("if") && !line.trim().startsWith("for")
                && !line.trim().startsWith("while") && !line.trim().startsWith("switch")
                && !line.trim().startsWith("return")) {
            return method.group(1) + "()";
        }
        Matcher python = PYTHON_DEF.matcher(line);
        if (python.find()) {
            return python.group(2) + ("def".equals(python.group(1)) ? "()" : "");
        }
        Matcher js = JS_FUNCTION.matcher(line);
        if (js.find()) {
            String name = js.group(1) != null ? js.group(1) : js.group(2);
            return name + "()";
        }
        Matcher go = GO_FUNC.matcher(line);
        if (go.find()) {
            return go.group(1) + "()";
        }
        return null;
    }

    private int braceDelta(String line) {
        int delta = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == quote && (i == 0 || line.charAt(i - 1) != '\\')) {
                    inString = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '{') {
                delta++;
            } else if (c == '}') {
                delta--;
            }
        }
        return delta;
    }

    private boolean isBlockClosed(String line) {
        String trimmed = line.trim();
        return trimmed.endsWith("}") || trimmed.endsWith("};");
    }

    /** 标识符拆分：payAmount → pay Amount，pay_amount → pay amount，供 BM25 代码字段使用 */
    String identifierSplit(String code) {
        String spaced = CAMEL_BOUNDARY.matcher(code).replaceAll(" ");
        spaced = spaced.replace('_', ' ');
        return NON_IDENTIFIER.matcher(spaced).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    /** 代码单元：名称 + 原文 */
    record CodeUnit(String name, String body) {
    }
}
