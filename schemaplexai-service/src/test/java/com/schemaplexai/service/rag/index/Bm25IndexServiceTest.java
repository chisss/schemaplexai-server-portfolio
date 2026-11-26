package com.schemaplexai.service.rag.index;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BM25 索引：前置租户/项目过滤、中文与代码召回、按文件删旧版本。
 */
class Bm25IndexServiceTest {

    @TempDir
    Path tempDir;

    private Bm25IndexService service;

    @BeforeEach
    void setUp() {
        service = new Bm25IndexService(tempDir.toString());
        service.init();
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    @DisplayName("检索必须前置过滤：同租户不同项目互不可见")
    void shouldIsolateByTenantAndProject() {
        service.upsertChunks(List.of(
                chunk("c1", "tenant-1", "proj-A", "doc-1", "额度说明.pdf", "现金贷产品最高额度为五万元"),
                chunk("c2", "tenant-1", "proj-B", "doc-2", "额度说明.pdf", "现金贷产品最高额度为五万元"),
                chunk("c3", "tenant-2", "proj-A", "doc-3", "额度说明.pdf", "现金贷产品最高额度为五万元")));

        List<Bm25Hit> hits = service.search("现金贷 额度", "tenant-1", List.of("proj-A"), 10);

        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(hit -> assertThat(hit.chunkId()).isEqualTo("c1"));
    }

    @Test
    @DisplayName("跨租户检索永远拿不到别人的数据")
    void shouldNeverLeakAcrossTenants() {
        service.upsertChunks(List.of(
                chunk("c1", "tenant-1", "proj-A", "doc-1", "a.pdf", "机密授信口径")));

        assertThat(service.search("机密授信口径", "tenant-2", List.of("proj-A"), 10)).isEmpty();
    }

    @Test
    @DisplayName("中文查询按 SmartChinese 分词命中")
    void shouldMatchChineseQuery() {
        service.upsertChunks(List.of(
                chunk("c1", "tenant-1", "proj-A", "doc-1", "合同.pdf", "逾期后按日收取罚息，罚息利率为借款利率的一点五倍")));

        List<Bm25Hit> hits = service.search("逾期罚息怎么算", "tenant-1", List.of("proj-A"), 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).text()).contains("罚息");
        assertThat(hits.get(0).rank()).isEqualTo(1);
    }

    @Test
    @DisplayName("代码标识符拆分后可用自然语言片段命中")
    void shouldMatchCodeBySplitIdentifier() {
        service.upsertChunks(List.of(
                new Bm25Chunk("c1", "tenant-1", "proj-A", "doc-1", "PaymentService.java",
                        "payAmount()", "code",
                        "【来源：PaymentService.java › payAmount()】\npublic void payAmount(Long amount) {}",
                        "public void pay Amount Long amount")));

        List<Bm25Hit> hits = service.search("pay amount", "tenant-1", List.of("proj-A"), 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunkId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("同一 chunk 重复写入按 chunk_id 幂等覆盖")
    void shouldUpsertByIdempotently() {
        service.upsertChunks(List.of(chunk("c1", "tenant-1", "proj-A", "doc-1", "a.pdf", "利率三点六")));
        service.upsertChunks(List.of(chunk("c1", "tenant-1", "proj-A", "doc-1", "a.pdf", "利率四点二")));

        assertThat(service.chunkCount("tenant-1")).isEqualTo(1);
        assertThat(service.search("四点二", "tenant-1", List.of("proj-A"), 5)).isNotEmpty();
    }

    @Test
    @DisplayName("文件重新摄入时按 doc_id 清掉旧 chunk")
    void shouldDeleteByDocument() {
        service.upsertChunks(List.of(
                chunk("c1", "tenant-1", "proj-A", "doc-1", "a.pdf", "旧版本内容"),
                chunk("c2", "tenant-1", "proj-A", "doc-2", "b.pdf", "另一个文件")));

        service.deleteByDocument("tenant-1", "doc-1");

        assertThat(service.chunkCount("tenant-1")).isEqualTo(1);
        assertThat(service.search("旧版本", "tenant-1", List.of("proj-A"), 5)).isEmpty();
    }

    @Test
    @DisplayName("空查询与空租户直接返回空，不抛异常")
    void shouldReturnEmptyForBlankInput() {
        service.upsertChunks(List.of(chunk("c1", "tenant-1", "proj-A", "doc-1", "a.pdf", "内容")));

        assertThat(service.search("", "tenant-1", List.of("proj-A"), 5)).isEmpty();
        assertThat(service.search("内容", null, List.of("proj-A"), 5)).isEmpty();
    }

    private Bm25Chunk chunk(String chunkId, String tenantId, String projectId, String docId,
                            String fileName, String text) {
        return new Bm25Chunk(chunkId, tenantId, projectId, docId, fileName, "正文", "document", text, text);
    }
}
