package com.schemaplexai.service.rag.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 合规扫描器：阈值边界、脱敏、密级关键词。
 */
class ComplianceScannerTest {

    private final ComplianceScanner scanner = new ComplianceScanner();

    @Test
    @DisplayName("单个身份证号不拦截，只告警")
    void shouldWarnButNotBlockSingleIdCard() {
        ComplianceScanner.ScanResult result = scanner.scan("法定代表人身份证 110101199001011234", "test");

        assertThat(result.blocking()).isFalse();
        assertThat(result.counters()).containsEntry("cn_id_card", 1);
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    @DisplayName("三个及以上身份证号判定为批量隐私数据，直接拦截")
    void shouldBlockBulkIdCards() {
        String text = "110101199001011234 110101199001011235 11010119900101123X";

        ComplianceScanner.ScanResult result = scanner.scan(text, "test");

        assertThat(result.blocking()).isTrue();
        assertThat(result.counters().get("cn_id_card")).isGreaterThanOrEqualTo(3);
        assertThat(result.blockSummary()).contains("cn_id_card");
    }

    @Test
    @DisplayName("手机号需达到批量阈值才拦截")
    void shouldBlockOnlyBulkMobiles() {
        String few = "联系电话 13800138000";
        String many = "联系电话 13800138000 13800138001 13800138002 13800138003 13800138004 "
                + "13800138005 13800138006 13800138007 13800138008 13800138009 "
                + "13800138010 13800138011 13800138012 13800138013 13800138014 "
                + "13800138015 13800138016 13800138017 13800138018 13800138019";

        assertThat(scanner.scan(few, "test").blocking()).isFalse();
        assertThat(scanner.scan(many, "test").blocking()).isTrue();
    }

    @Test
    @DisplayName("密级关键词命中即拦截")
    void shouldBlockClassifiedKeyword() {
        assertThat(scanner.scan("本文件为绝密材料", "test").blocking()).isTrue();
        assertThat(scanner.scan("TOP SECRET internal only", "test").blocking()).isTrue();
    }

    @Test
    @DisplayName("空文本视为通过")
    void shouldPassBlankText() {
        assertThat(scanner.scan("", "test").blocking()).isFalse();
        assertThat(scanner.scan(null, "test").blocking()).isFalse();
    }

    @Test
    @DisplayName("chunk 级复用接口与全文扫描口径一致")
    void shouldReuseSameRuleForChunk() {
        assertThat(scanner.isBlocking("绝密：不得外传")).isTrue();
        assertThat(scanner.isBlocking("普通产品说明书")).isFalse();
    }

    @Test
    @DisplayName("审计采样必须脱敏，不能把原文数字落日志")
    void shouldMaskDigitsInSample() {
        String text = "110101199001011234 110101199001011235 110101199001011236";
        ComplianceScanner.ScanResult result = scanner.scan(text, "test");

        assertThat(result.blocking()).isTrue();
        // 拦截摘要只包含规则计数，不包含原始号码
        assertThat(result.blockSummary()).doesNotContain("11010119900101123");
    }
}
