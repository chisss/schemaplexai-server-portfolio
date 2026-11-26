package com.schemaplexai.service.rag.eval;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回答接地校验器（确定性版本）。
 *
 * <p>设计稿要求"忠实度校验 → 不通过转 HITL"。工业界的做法通常是用裁判模型打分，
 * 但那有两个现实问题：一是每次回答多一次 LLM 调用（金融场景成本敏感），
 * 二是裁判模型本身也会漂移，难以解释"为什么这条被判为不忠实"。
 *
 * <p>因此这里先落一层<b>可解释的确定性校验</b>，专门抓住金融问答里最致命的三类问题：
 * <ol>
 *   <li><b>不标引用</b>：回答没有任何 [n] 标记，等于无法回溯证据；</li>
 *   <li><b>引用越界</b>：标了 [7] 但上下文只有 5 条，属于编造来源；</li>
 *   <li><b>数字无出处</b>：利率/额度/期限这类数字在上下文里找不到，是最典型的幻觉形态。</li>
 * </ol>
 *
 * <p>刻意<b>不</b>校验中文数词（"三点六"）与同义改写：那需要语义模型，
 * 硬做只会制造大量误判，反而让运营不信任这条链路。
 *
 * <p>定位：这是"低于模型裁判成本"的第一道闸，可作为生成后置校验或人工复核前的预筛，
 * 结果落 {@code RagOperationLog}，配合 {@code hitlOnLowFaithfulness} 决定是否转人工。
 */
@Service
public class GroundingGuardService {

    /** 引用标记：[1] ~ [99] */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2})]");

    /** 数字：整数/小数/千分位/百分比。单字符数字不校验（多半是序号），避免噪声 */
    private static final int MIN_NUMBER_LENGTH = 2;

    /** 单次最多报出的问题数，避免响应体膨胀 */
    private static final int MAX_VIOLATIONS = 12;

    /**
     * @param answer        模型回答
     * @param contextChunks 实际进入上下文的片段（按 [n] 顺序）
     * @param settings      租户配置（引用强制开关、HITL 开关、忠实度阈值）
     */
    public GroundingVerdict check(String answer, List<String> contextChunks, RagRuntimeSettings settings) {
        List<String> chunks = contextChunks == null ? List.of() : contextChunks;
        boolean citationRequired = settings == null || settings.isCitationRequired();
        boolean hitlEnabled = settings == null || settings.isHitlOnLowFaithfulness();

        if (!StringUtils.hasText(answer)) {
            return new GroundingVerdict(false, 0.0D, List.of("EMPTY_ANSWER"), List.of(), List.of(),
                    hitlEnabled, "回答为空");
        }

        List<String> violations = new ArrayList<>();
        List<Integer> cited = extractCitations(answer);

        // ── 1. 引用校验 ──
        if (citationRequired && cited.isEmpty()) {
            violations.add("NO_CITATION");
        }
        for (Integer index : cited) {
            if (index < 1 || index > chunks.size()) {
                violations.add("CITATION_OUT_OF_RANGE:" + index);
                if (violations.size() >= MAX_VIOLATIONS) {
                    break;
                }
            }
        }

        // ── 2. 数值接地校验 ──
        List<String> unsupportedNumbers = new ArrayList<>();
        if (!chunks.isEmpty()) {
            String contextText = normalize(String.join("\n", chunks));
            for (String number : extractNumbers(answer)) {
                if (!contextText.contains(number)) {
                    unsupportedNumbers.add(number);
                    if (unsupportedNumbers.size() >= MAX_VIOLATIONS) {
                        break;
                    }
                }
            }
            if (!unsupportedNumbers.isEmpty()) {
                violations.add("UNSUPPORTED_NUMBER");
            }
        }

        double score = computeScore(violations, unsupportedNumbers);
        boolean grounded = violations.isEmpty();

        String summary = buildSummary(violations, unsupportedNumbers, cited, grounded);
        return new GroundingVerdict(grounded, round(score), List.copyOf(violations), cited,
                List.copyOf(unsupportedNumbers), !grounded && hitlEnabled, summary);
    }

    /** 提取 [n] 引用编号（去重保序） */
    public List<Integer> extractCitations(String answer) {
        Set<Integer> indexes = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            indexes.add(Integer.parseInt(matcher.group(1)));
        }
        return new ArrayList<>(indexes);
    }

    /**
     * 提取需要校验的数字：去掉千分位与百分号后长度 ≥2 的数字 token。
     * 例："3.6%" → "3.6"，"50,000" → "50000"，"12 期" → "12"。
     */
    public List<String> extractNumbers(String text) {
        Set<String> numbers = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        Matcher matcher = Pattern.compile("\\d[\\d,，]*(?:\\.\\d+)?").matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            String normalized = raw.replace(",", "").replace("，", "");
            if (normalized.length() >= MIN_NUMBER_LENGTH) {
                numbers.add(normalized);
            }
            if (numbers.size() >= 50) {
                break;
            }
        }
        return new ArrayList<>(numbers);
    }

    /** 统一上下文数字形态，保证 "50,000" 与 "50000" 能对上 */
    private String normalize(String text) {
        return text.replace(",", "").replace("，", "");
    }

    private double computeScore(List<String> violations, List<String> unsupportedNumbers) {
        double score = 1.0D;
        if (violations.contains("NO_CITATION")) {
            score -= 0.3D;
        }
        if (violations.stream().anyMatch(v -> v.startsWith("CITATION_OUT_OF_RANGE"))) {
            score -= 0.4D;
        }
        if (violations.contains("UNSUPPORTED_NUMBER")) {
            score -= Math.min(0.5D, 0.15D * unsupportedNumbers.size());
        }
        return Math.max(0.0D, score);
    }

    private String buildSummary(List<String> violations, List<String> unsupportedNumbers,
                                List<Integer> cited, boolean grounded) {
        if (grounded) {
            return "引用 " + cited.size() + " 处，数字均可在上下文找到";
        }
        StringBuilder sb = new StringBuilder();
        if (violations.contains("NO_CITATION")) {
            sb.append("回答未标注引用；");
        }
        long outOfRange = violations.stream().filter(v -> v.startsWith("CITATION_OUT_OF_RANGE")).count();
        if (outOfRange > 0) {
            sb.append("存在 ").append(outOfRange).append(" 个越界引用；");
        }
        if (!unsupportedNumbers.isEmpty()) {
            sb.append("以下数字在上下文中无出处：")
                    .append(String.join("、", unsupportedNumbers.subList(0, Math.min(5, unsupportedNumbers.size()))));
        }
        return sb.toString();
    }

    private double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    /**
     * 校验结论。
     *
     * @param grounded          是否通过
     * @param score             0~1 置信分（确定性扣分，不是模型打分）
     * @param violations        违规码：NO_CITATION / CITATION_OUT_OF_RANGE:n / UNSUPPORTED_NUMBER
     * @param citedIndexes      引用到的片段编号
     * @param unsupportedNumbers 上下文中找不到的数字
     * @param hitlRequired      是否应转人工（= 未通过 且 租户开启 HITL）
     * @param summary           人类可读结论
     */
    public record GroundingVerdict(boolean grounded,
                                   double score,
                                   List<String> violations,
                                   List<Integer> citedIndexes,
                                   List<String> unsupportedNumbers,
                                   boolean hitlRequired,
                                   String summary) {
    }
}
