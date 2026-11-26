package com.schemaplexai.service.rag.ingest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 前置合规扫描器（网关级 + chunk 级复用）。
 *
 * <p>金融场景的第一道红线：批量隐私数据、密级标记文档不允许进入解析与向量化。
 * 这里刻意不使用 NER 模型，只用正则 + 关键词，原因有三：
 * <ol>
 *   <li>网关阶段要求"零模型开销"，否则并发上传会打爆算力；</li>
 *   <li>正则可解释，审计日志能说清"因为命中哪条规则被拦"；</li>
 *   <li>召回阶段的实体过滤另有轻量抽取，不依赖本类。</li>
 * </ol>
 *
 * <p>阈值设计：单个身份证/银行卡号可能是正常业务数据（如合同模板），
 * 因此只有<b>批量</b>命中才判定为"隐私数据导出"并拦截；手机号阈值更宽。
 */
@Slf4j
@Component
public class ComplianceScanner {

    /** 18 位身份证（含末位 X） */
    private static final Pattern CN_ID_CARD = Pattern.compile("(?<![0-9A-Za-z])\\d{17}[\\dXx](?![0-9A-Za-z])");
    /** 11 位手机号 */
    private static final Pattern CN_MOBILE = Pattern.compile("(?<![0-9])(?:\\+?86)?1[3-9]\\d{9}(?![0-9])");
    /** 16~19 位银行卡号（允许空格/短横线分组） */
    private static final Pattern BANK_CARD = Pattern.compile("(?<![0-9])(?:\\d[ -]?){15,18}\\d(?![0-9])");
    /** 邮箱（仅统计，不单独拦截） */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 密级/禁传关键词：命中即拦截 */
    private static final List<String> BLOCKING_KEYWORDS = List.of(
            "绝密", "机密级", "禁止外传", "不得外传", "内部绝密", "top secret", "confidential - do not distribute");

    /** 命中即拦截的批量阈值 */
    static final int ID_CARD_BLOCK_THRESHOLD = 3;
    static final int BANK_CARD_BLOCK_THRESHOLD = 3;
    static final int MOBILE_BLOCK_THRESHOLD = 20;

    /** 采样保留的最大字符数（用于审计取证，做脱敏） */
    private static final int SAMPLE_LENGTH = 120;

    /**
     * 扫描文本。
     *
     * @param text    待扫描文本（可为空，空文本视为通过）
     * @param context 审计上下文描述，例如 "gateway:合同.pdf" / "chunk:docId#3"
     */
    public ScanResult scan(String text, String context) {
        if (!StringUtils.hasText(text)) {
            return ScanResult.clean();
        }

        Map<String, Integer> counters = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        int idCards = count(CN_ID_CARD, text);
        int mobiles = count(CN_MOBILE, text);
        int bankCards = count(BANK_CARD, text);
        int emails = count(EMAIL, text);
        if (idCards > 0) {
            counters.put("cn_id_card", idCards);
        }
        if (mobiles > 0) {
            counters.put("cn_mobile", mobiles);
        }
        if (bankCards > 0) {
            counters.put("bank_card", bankCards);
        }
        if (emails > 0) {
            counters.put("email", emails);
        }

        List<String> matchedKeywords = new ArrayList<>();
        String lower = text.toLowerCase();
        for (String keyword : BLOCKING_KEYWORDS) {
            if (lower.contains(keyword)) {
                matchedKeywords.add(keyword);
            }
        }

        boolean blocking = idCards >= ID_CARD_BLOCK_THRESHOLD
                || bankCards >= BANK_CARD_BLOCK_THRESHOLD
                || mobiles >= MOBILE_BLOCK_THRESHOLD
                || !matchedKeywords.isEmpty();

        if (!blocking) {
            if (idCards > 0 || bankCards > 0 || mobiles > 0) {
                warnings.add("检测到少量隐私字段（身份证 " + idCards + " / 手机号 " + mobiles
                        + " / 银行卡 " + bankCards + "），未达拦截阈值");
            }
            return new ScanResult(false, counters, matchedKeywords, warnings, null);
        }

        String summary = buildSummary(counters, matchedKeywords);
        String sample = mask(text);
        log.warn("合规扫描拦截: context={}, summary={}, sample={}", context, summary, sample);
        return new ScanResult(true, counters, matchedKeywords, warnings, summary);
    }

    /** chunk 级二次扫描：命中即丢弃该 chunk，不写索引 */
    public boolean isBlocking(String text) {
        return scan(text, "chunk").blocking();
    }

    private int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int total = 0;
        while (matcher.find()) {
            total++;
            if (total > 1000) {
                break;
            }
        }
        return total;
    }

    private String buildSummary(Map<String, Integer> counters, List<String> keywords) {
        StringBuilder sb = new StringBuilder();
        counters.forEach((key, value) -> {
            if (!sb.isEmpty()) {
                sb.append('、');
            }
            sb.append(key).append('=').append(value);
        });
        if (!keywords.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("；");
            }
            sb.append("密级关键词=").append(String.join("/", keywords));
        }
        return sb.toString();
    }

    /** 审计采样脱敏：只保留首尾，中间打码 */
    private String mask(String text) {
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() <= SAMPLE_LENGTH) {
            return compact.replaceAll("\\d", "*");
        }
        String head = compact.substring(0, SAMPLE_LENGTH / 2);
        String tail = compact.substring(compact.length() - SAMPLE_LENGTH / 2);
        return (head + "…" + tail).replaceAll("\\d", "*");
    }

    /**
     * 扫描结果。
     *
     * @param blocking        是否拦截
     * @param counters        规则命中计数（规则名 → 次数）
     * @param matchedKeywords 命中的密级关键词
     * @param warnings        非阻断告警
     * @param blockSummary    拦截原因摘要（未拦截时为 null）
     */
    public record ScanResult(boolean blocking,
                             Map<String, Integer> counters,
                             List<String> matchedKeywords,
                             List<String> warnings,
                             String blockSummary) {

        public static ScanResult clean() {
            return new ScanResult(false, Map.of(), List.of(), List.of(), null);
        }

        public boolean hasSensitiveData() {
            return !counters.isEmpty();
        }
    }
}
