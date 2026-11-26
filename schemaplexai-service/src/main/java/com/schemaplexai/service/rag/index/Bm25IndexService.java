package com.schemaplexai.service.rag.index;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * BM25 倒排索引（Lucene）。
 *
 * <p>与向量索引并列构成双路召回。三个设计要点：
 * <ol>
 *   <li><b>前置过滤</b>：tenant_id / project_id 作为 {@code FILTER} 子句参与查询，
 *       Lucene 在打分前就裁掉其他租户的文档 —— 而不是召回后再过滤（那既慢又有泄露风险）；</li>
 *   <li><b>双字段</b>：{@code body} 用 SmartChinese 分词（中文业务文档），
 *       {@code code_body} 用空白分词（代码标识符已预拆分为空格分隔）；</li>
 *   <li><b>按文件删旧版本</b>：{@code doc_id} 是删除键，文件更新时先删后插，避免增量重算 Embedding。</li>
 * </ol>
 *
 * <p>降级：索引目录不可写时标记 {@code available=false}，检索侧自动退化为单路向量召回，
 * 不影响主流程（BM25 是增强，不是命脉）。
 */
@Slf4j
@Service
public class Bm25IndexService {

    // ── 字段名 ──
    public static final String F_CHUNK_ID = "chunk_id";
    public static final String F_TENANT_ID = "tenant_id";
    public static final String F_PROJECT_ID = "project_id";
    public static final String F_DOC_ID = "doc_id";
    public static final String F_FILE_NAME = "file_name";
    public static final String F_SECTION = "section";
    public static final String F_CATEGORY = "category";
    public static final String F_BODY = "body";
    public static final String F_CODE_BODY = "code_body";
    public static final String F_TEXT = "text";

    private static final double BODY_BOOST = 2.0F;
    private static final double CODE_BOOST = 1.0F;

    private final Path indexDir;
    private final Analyzer bodyAnalyzer = new SmartChineseAnalyzer();
    private final Analyzer codeAnalyzer = new WhitespaceAnalyzer();
    private final Analyzer perFieldAnalyzer;

    private volatile Directory directory;
    private volatile IndexWriter writer;

    public Bm25IndexService(@Value("${rag.bm25.index-dir:./data/rag-bm25}") String indexDir) {
        this.indexDir = Path.of(indexDir);
        this.perFieldAnalyzer = new PerFieldAnalyzerWrapper(bodyAnalyzer, Map.of(F_CODE_BODY, codeAnalyzer));
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(indexDir);
            this.directory = FSDirectory.open(indexDir);
            IndexWriterConfig config = new IndexWriterConfig(perFieldAnalyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            config.setSimilarity(new BM25Similarity());
            this.writer = new IndexWriter(directory, config);
            log.info("BM25 索引就绪: dir={}", indexDir.toAbsolutePath());
        } catch (Exception e) {
            log.error("BM25 索引初始化失败，将退化为纯向量召回: dir={}, error={}", indexDir, e.getMessage(), e);
            this.writer = null;
        }
    }

    @PreDestroy
    public void close() {
        try {
            if (writer != null) {
                writer.close();
            }
            if (directory != null) {
                directory.close();
            }
        } catch (Exception e) {
            log.warn("关闭 BM25 索引失败: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return writer != null;
    }

    /** 批量写入/更新（按 chunk_id 幂等覆盖） */
    public synchronized void upsertChunks(List<Bm25Chunk> chunks) {
        if (!isAvailable() || CollectionUtils.isEmpty(chunks)) {
            return;
        }
        try {
            for (Bm25Chunk chunk : chunks) {
                writer.updateDocument(new Term(F_CHUNK_ID, chunk.chunkId()), toDocument(chunk));
            }
            writer.commit();
            log.debug("BM25 写入完成: chunks={}", chunks.size());
        } catch (Exception e) {
            log.error("BM25 写入失败: chunks={}, error={}", chunks.size(), e.getMessage(), e);
        }
    }

    /** 文件更新/删除：按 file_id 清掉旧 chunk */
    public synchronized void deleteByDocument(String tenantId, String docId) {
        if (!isAvailable() || !StringUtils.hasText(docId)) {
            return;
        }
        try {
            BooleanQuery.Builder filter = new BooleanQuery.Builder();
            filter.add(new TermInSetQuery(F_DOC_ID, List.of(new BytesRef(docId))), BooleanClause.Occur.FILTER);
            if (StringUtils.hasText(tenantId)) {
                filter.add(new TermInSetQuery(F_TENANT_ID, List.of(new BytesRef(tenantId))),
                        BooleanClause.Occur.FILTER);
            }
            writer.deleteDocuments(filter.build());
            writer.commit();
        } catch (Exception e) {
            log.warn("BM25 删除旧版本失败: tenantId={}, docId={}, error={}", tenantId, docId, e.getMessage());
        }
    }

    /**
     * BM25 检索。
     *
     * @param query      用户查询
     * @param tenantId   租户（必填，前置过滤）
     * @param projectIds 项目/上下文范围（为空表示租户全量，仍受租户过滤约束）
     * @param topK       召回数量
     */
    public List<Bm25Hit> search(String query, String tenantId, Collection<String> projectIds, int topK) {
        if (!isAvailable() || !StringUtils.hasText(query) || !StringUtils.hasText(tenantId)) {
            return List.of();
        }
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());
            Query finalQuery = buildQuery(query, tenantId, projectIds);
            int size = Math.max(1, topK);
            TopDocs topDocs = searcher.search(finalQuery, size);

            List<Bm25Hit> hits = new ArrayList<>(topDocs.scoreDocs.length);
            int rank = 1;
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                hits.add(new Bm25Hit(
                        doc.get(F_CHUNK_ID),
                        doc.get(F_DOC_ID),
                        doc.get(F_FILE_NAME),
                        doc.get(F_SECTION),
                        doc.get(F_CATEGORY),
                        doc.get(F_TEXT),
                        scoreDoc.score,
                        rank++));
            }
            return hits;
        } catch (Exception e) {
            log.warn("BM25 检索失败，退化为单路召回: tenantId={}, error={}", tenantId, e.getMessage());
            return List.of();
        }
    }

    /** 租户内 chunk 数（诊断用） */
    public long chunkCount(String tenantId) {
        if (!isAvailable() || !StringUtils.hasText(tenantId)) {
            return 0L;
        }
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            BooleanQuery.Builder filter = new BooleanQuery.Builder();
            filter.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
            filter.add(new TermInSetQuery(F_TENANT_ID, List.of(new BytesRef(tenantId))),
                    BooleanClause.Occur.FILTER);
            return searcher.count(filter.build());
        } catch (Exception e) {
            log.warn("BM25 统计失败: tenantId={}, error={}", tenantId, e.getMessage());
            return 0L;
        }
    }

    // ==================== 内部 ====================

    private Document toDocument(Bm25Chunk chunk) {
        Document doc = new Document();
        doc.add(new StringField(F_CHUNK_ID, nullSafe(chunk.chunkId()), Field.Store.YES));
        doc.add(new StringField(F_TENANT_ID, nullSafe(chunk.tenantId()), Field.Store.YES));
        doc.add(new StringField(F_PROJECT_ID, nullSafe(chunk.projectId()), Field.Store.YES));
        doc.add(new StringField(F_DOC_ID, nullSafe(chunk.docId()), Field.Store.YES));
        doc.add(new StringField(F_CATEGORY, nullSafe(chunk.category()), Field.Store.YES));
        doc.add(new StoredField(F_FILE_NAME, nullSafe(chunk.fileName())));
        doc.add(new StoredField(F_SECTION, nullSafe(chunk.section())));
        doc.add(new StoredField(F_TEXT, nullSafe(chunk.text())));
        doc.add(new TextField(F_BODY, nullSafe(chunk.text()), Field.Store.NO));
        doc.add(new TextField(F_CODE_BODY, nullSafe(chunk.bm25Text()), Field.Store.NO));
        return doc;
    }

    private Query buildQuery(String queryText, String tenantId, Collection<String> projectIds) throws Exception {
        QueryParser bodyParser = new QueryParser(F_BODY, bodyAnalyzer);
        bodyParser.setDefaultOperator(QueryParser.Operator.OR);
        Query bodyQuery = bodyParser.parse(QueryParser.escape(queryText));

        QueryParser codeParser = new QueryParser(F_CODE_BODY, codeAnalyzer);
        codeParser.setDefaultOperator(QueryParser.Operator.OR);
        Query codeQuery = codeParser.parse(QueryParser.escape(queryText));

        BooleanQuery.Builder should = new BooleanQuery.Builder();
        should.add(new BoostQuery(bodyQuery, (float) BODY_BOOST), BooleanClause.Occur.SHOULD);
        should.add(new BoostQuery(codeQuery, (float) CODE_BOOST), BooleanClause.Occur.SHOULD);
        should.setMinimumNumberShouldMatch(1);

        BooleanQuery.Builder filter = new BooleanQuery.Builder();
        filter.add(new TermInSetQuery(F_TENANT_ID, List.of(new BytesRef(tenantId))), BooleanClause.Occur.FILTER);
        if (!CollectionUtils.isEmpty(projectIds)) {
            List<BytesRef> projects = projectIds.stream()
                    .filter(StringUtils::hasText)
                    .distinct()
                    .map(BytesRef::new)
                    .toList();
            if (!projects.isEmpty()) {
                filter.add(new TermInSetQuery(F_PROJECT_ID, projects), BooleanClause.Occur.FILTER);
            }
        }

        return new BooleanQuery.Builder()
                .add(should.build(), BooleanClause.Occur.MUST)
                .add(filter.build(), BooleanClause.Occur.FILTER)
                .build();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
