package com.schemaplexai.service.desktop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.desktop.DesktopEmployeeAccess.Employee;
import com.schemaplexai.service.security.AuthorizationService;
import com.schemaplexai.service.semantic.application.orchestration.SemanticQueryExecutionApplicationService;
import com.schemaplexai.service.semantic.application.query.PreparedSemanticQuery;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DesktopDataService {
    private static final String INTERPRET = "semantic:query:interpret";
    private static final String EXECUTE = "semantic:query:execute";
    private static final int ROW_LIMIT = 5000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DesktopWorkspaceService workspace;
    private final AuthorizationService authorization;
    private final DatabaseSourceService sources;
    private final SemanticQueryExecutionApplicationService queryEngine;

    public record Dataset(String id, String name, String sourceId, String publishedVersionId, long policyRevision,
                          boolean canQuery, boolean canExport) {}
    private record Policy(Dataset dataset, String physicalObject, List<String> allowedFields) {}
    public record Ask(@NotBlank @Size(max = 128) String datasetId, @NotBlank @Size(max = 2000) String question,
                      @Size(max = 1000) String context, @NotBlank @Size(max = 128) String clientRequestId) {}
    public record Query(String id, String datasetId, String question, String context, String planHash,
                        long policyRevision, Plan plan, String resultId, OffsetDateTime expiresAt) {}
    public record Plan(String status, List<Column> columns, List<?> clarifications, List<String> reasons,
                       List<?> filters, Object timeRange, int rowLimit) {}
    public record Column(String key, String role) {}
    public record Result(String id, String datasetId, long snapshotRevision, List<Column> columns,
                         List<Map<String, Object>> rows, int rowCount, boolean truncated, String auditId,
                         List<String> warnings, OffsetDateTime expiresAt) {}
    public record ExportRequest(@NotBlank String resultId, @NotNull Long snapshotRevision,
                                @NotBlank String format, @Size(max = 100) List<@NotBlank String> columns,
                                @NotBlank String scope, @NotBlank @Size(max = 128) String clientRequestId) {}
    public record DatasetDefinition(@NotBlank @Size(max = 160) String name,
                                    @NotBlank @Size(max = 64) String sourceId,
                                    @NotBlank @Size(max = 64) String publishedVersionId,
                                    @NotBlank @Size(max = 256) String physicalObject,
                                    @Size(min = 1, max = 100) List<@NotBlank @Size(max = 128) String> allowedFields,
                                    @NotNull Boolean enabled, @NotNull Long revision) {}
    public record DatasetGrant(@NotBlank @Size(max = 64) String userId, @NotNull Boolean canExport) {}
    public record Export(String id, String filename, String mimeType, int rowCount, boolean truncated,
                         OffsetDateTime expiresAt, String status, int size) {}
    public record Download(Export export, byte[] bytes) {}

    public PageResult<Dataset> manageDatasets(int page, int size) {
        requireManage();
        var employee = Employee.current();
        long count = jdbc.queryForObject("""
                SELECT count(*) FROM sf_desktop_dataset WHERE tenant_id=?
                """, Long.class, employee.tenantId());
        var records = jdbc.query("""
                SELECT p.*,true can_export FROM sf_desktop_dataset p
                WHERE p.tenant_id=? ORDER BY p.name,p.id LIMIT ? OFFSET ?
                """, (rs, index) -> new Dataset(rs.getString("id"), rs.getString("name"), rs.getString("source_id"),
                rs.getString("version_id"), rs.getLong("revision"), true, true),
                employee.tenantId(), size, DesktopWorkspaceService.offset(page, size));
        return new PageResult<>(records, count, page, size);
    }

    public Dataset saveDataset(String id, DatasetDefinition request) {
        requireManage();
        var employee = Employee.current();
        if (!request.physicalObject().matches("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "数据集必须映射到已设置行级策略的表或视图");
        }
        sources.getById(request.sourceId());
        int versionOwned = jdbc.queryForObject("""
                SELECT count(*) FROM sf_semantic_version WHERE CAST(id AS varchar)=?
                    AND CAST(tenant_id AS varchar)=? AND status='published' AND deleted=0
                """, Integer.class, request.publishedVersionId(), employee.tenantId());
        if (versionOwned != 1) throw new BusinessException(ResultCode.FORBIDDEN, "语义版本必须属于当前企业且已发布");
        if (id == null) {
            id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO sf_desktop_dataset(id,tenant_id,name,source_id,version_id,physical_object,allowed_fields,enabled)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, id, employee.tenantId(), request.name().trim(), request.sourceId(), request.publishedVersionId(),
                    request.physicalObject(), request.allowedFields().stream().distinct().toArray(String[]::new), request.enabled());
        } else {
            int changed = jdbc.update("""
                    UPDATE sf_desktop_dataset SET name=?,source_id=?,version_id=?,physical_object=?,allowed_fields=?,
                       enabled=?,revision=revision+1 WHERE id=? AND tenant_id=? AND revision=?
                    """, request.name().trim(), request.sourceId(), request.publishedVersionId(),
                    request.physicalObject(), request.allowedFields().stream().distinct().toArray(String[]::new),
                    request.enabled(), id, employee.tenantId(), request.revision());
            DesktopWorkspaceService.revision(changed);
        }
        String datasetId = id;
        return jdbc.query("""
                SELECT p.*,true can_export FROM sf_desktop_dataset p
                WHERE p.tenant_id=? AND p.id=?
                """, (rs, index) -> new Dataset(rs.getString("id"), rs.getString("name"), rs.getString("source_id"),
                rs.getString("version_id"), rs.getLong("revision"), true, true),
                employee.tenantId(), datasetId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND));
    }

    public void grantDataset(String id, DatasetGrant grant) {
        requireManage();
        var employee = Employee.current();
        jdbc.queryForObject("""
                SELECT id FROM sf_desktop_dataset WHERE id=? AND tenant_id=? AND enabled FOR UPDATE
                """, String.class, id, employee.tenantId());
        boolean userExists = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM sf_user WHERE id=? AND tenant_id=? AND status='active' AND deleted=0)
                """, Boolean.class, grant.userId(), employee.tenantId()));
        if (!userExists) throw new BusinessException(ResultCode.NOT_FOUND, "员工不存在");
        jdbc.update("""
                INSERT INTO sf_desktop_dataset_grant(tenant_id,dataset_id,user_id,can_export)
                VALUES(?,?,?,?) ON CONFLICT(tenant_id,dataset_id,user_id)
                DO UPDATE SET can_export=EXCLUDED.can_export
                """, employee.tenantId(), id, grant.userId(), grant.canExport());
    }

    public void revokeDataset(String id, String userId) {
        requireManage();
        jdbc.update("""
                DELETE FROM sf_desktop_dataset_grant WHERE tenant_id=? AND dataset_id=? AND user_id=?
                """, Employee.current().tenantId(), id, userId);
    }

    public List<Dataset> catalog() {
        require(INTERPRET);
        return policies(null).stream().map(Policy::dataset).toList();
    }

    public DesktopWorkspaceService.Conversation create(String requestId) {
        require(INTERPRET);
        return workspace.createConversation(new DesktopWorkspaceService.CreateConversation(
                "data", null, null, null, requestId));
    }

    public List<Query> history(String conversation, int page, int size) {
        require(INTERPRET);
        conversation(conversation, false);
        return jdbc.query("""
                SELECT * FROM sf_desktop_data_query WHERE conversation_id=? AND expires_at>now()
                ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """, (rs, index) -> new Query(rs.getString("id"), rs.getString("dataset_id"),
                rs.getString("question"), rs.getString("context"), rs.getString("plan_hash"),
                rs.getLong("policy_revision"), decode(rs.getString("plan"), Plan.class),
                rs.getString("result_id"), rs.getObject("expires_at", OffsetDateTime.class)),
                conversation, size, DesktopWorkspaceService.offset(page, size)).stream()
                .filter(query -> policies(query.datasetId()).stream().anyMatch(
                        policy -> policy.dataset().policyRevision() == query.policyRevision())).toList();
    }

    @Transactional
    public Query ask(String conversation, Ask command) {
        require(INTERPRET);
        lockConversation(conversation);
        Policy policy = policy(command.datasetId());
        PreparedSemanticQuery prepared = prepare(policy, command.question(), command.context());
        var plan = view(prepared, policy);
        String hash = prepared.ready() ? prepared.plan().planHash() : null;
        jdbc.update("""
                INSERT INTO sf_desktop_data_query(id,conversation_id,dataset_id,policy_revision,question,context,
                         request_id,plan_hash,plan,expires_at)
                VALUES(?,?,?,?,?,?,?,?,CAST(? AS jsonb),now()+interval '7 days')
                ON CONFLICT(conversation_id,request_id) DO NOTHING
                """, UUID.randomUUID().toString(), conversation, command.datasetId(), policy.dataset().policyRevision(),
                command.question(), command.context(), command.clientRequestId(), hash, encode(plan));
        var query = findQuery(conversation, "request_id", command.clientRequestId());
        if (!command.datasetId().equals(query.datasetId()) || !command.question().equals(query.question())
                || !java.util.Objects.equals(command.context(), query.context())) {
            throw new BusinessException(409, "请求编号已用于其他数据问题");
        }
        workspace.activateConversation(conversation, command.question());
        return query;
    }

    @Transactional
    public Result execute(String conversation, String queryId, String expectedHash) {
        require(EXECUTE);
        lockConversation(conversation);
        Query request = findQuery(conversation, "id", queryId);
        lockPolicy(request.datasetId());
        Policy policy = policy(request.datasetId());
        if (request.policyRevision() != policy.dataset().policyRevision() || request.planHash() == null
                || !request.planHash().equals(expectedHash)) {
            throw new BusinessException(409, "数据授权或查询计划已更新，请重新提问");
        }
        if (request.resultId() != null) return result(request.resultId());
        var latest = jdbc.queryForObject("""
                SELECT id FROM sf_desktop_data_query WHERE conversation_id=?
                ORDER BY created_at DESC,id DESC LIMIT 1
                """, String.class, conversation);
        if (!queryId.equals(latest)) throw new BusinessException(409, "请确认当前最新的查询计划");
        var prepared = prepare(policy, request.question(), request.context());
        checkLineage(prepared, policy);
        var output = queryEngine.execute(prepared, expectedHash, ROW_LIMIT, 30);
        var columns = output.columns().stream().map(c -> new Column(c.alias(), c.role())).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var outputRow : output.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Column column : columns) row.put(column.key(), normalize(outputRow.get(column.key())));
            rows.add(row);
        }
        String id = UUID.randomUUID().toString();
        var snapshot = new Result(id, request.datasetId(), request.policyRevision(), columns, rows,
                output.rowCount(), output.truncated(), output.auditId(), output.warnings(), OffsetDateTime.now().plusDays(1));
        var payload = encode(snapshot);
        if (payload.length() > 16_000_000) throw new BusinessException(413, "查询结果过大，请缩小查询范围");
        jdbc.update("""
                INSERT INTO sf_desktop_data_result(id,query_id,snapshot,expires_at)
                VALUES(?,?,CAST(? AS jsonb),?)
                """, id, queryId, payload, snapshot.expiresAt());
        jdbc.update("UPDATE sf_desktop_data_query SET result_id=? WHERE id=?", id, queryId);
        return snapshot;
    }

    public Result result(String id) {
        require(EXECUTE);
        Employee employee = Employee.current();
        Result result = single(jdbc.query("""
                SELECT r.snapshot FROM sf_desktop_data_result r JOIN sf_desktop_data_query q ON r.query_id=q.id
                JOIN sf_desktop_conversation c ON c.id=q.conversation_id
                WHERE r.id=? AND r.expires_at>now() AND c.tenant_id=? AND c.owner_id=?
                """, (rs, index) -> decode(rs.getString(1), Result.class), id, employee.tenantId(), employee.userId()));
        if (policy(result.datasetId()).dataset().policyRevision() != result.snapshotRevision()) {
            throw new BusinessException(409, "数据授权版本已更新");
        }
        return result;
    }

    public PageResult<Map<String, Object>> rows(String id, int page, int size) {
        var result = result(id);
        int start = (int) Math.min(result.rows().size(), DesktopWorkspaceService.offset(page, size));
        int stop = Math.min(result.rows().size(), start + size);
        return new PageResult<>(result.rows().subList(start, stop), result.rows().size(), page, size);
    }

    public Export export(ExportRequest command) {
        var result = result(command.resultId());
        requireExport(result);
        if (!"currentResult".equals(command.scope()) || !List.of("csv", "xlsx").contains(command.format())
                || result.snapshotRevision() != command.snapshotRevision()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "导出格式、查询范围或版本无效");
        }
        var keys = result.columns().stream().map(Column::key).toList();
        var selected = command.columns() == null || command.columns().isEmpty() ? keys : command.columns().stream().distinct().toList();
        if (!keys.containsAll(selected)) throw new BusinessException(ResultCode.FORBIDDEN);
        var employee = Employee.current();
        var signature = encode(command);
        var bytes = new DesktopDataExport().encode(command.format(), selected, result.rows(), result.truncated());
        var id = UUID.randomUUID().toString();
        var metadata = new Export(id, "查询结果-" + result.id().substring(0, 8) + "." + command.format(),
                "csv".equals(command.format()) ? "text/csv; charset=utf-8"
                        : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                result.rows().size(), result.truncated(), result.expiresAt(), "succeeded", bytes.length);
        jdbc.update("""
                INSERT INTO sf_desktop_data_export(id,tenant_id,owner_id,result_id,request_id,signature,metadata,content,expires_at)
                VALUES(?,?,?,?,?,?,CAST(? AS jsonb),?,?) ON CONFLICT(tenant_id,owner_id,request_id) DO NOTHING
                """, id, employee.tenantId(), employee.userId(), result.id(), command.clientRequestId(),
                signature, encode(metadata), bytes, result.expiresAt());
        return single(jdbc.query("""
                SELECT metadata,signature FROM sf_desktop_data_export WHERE tenant_id=? AND owner_id=? AND request_id=?
                """, (rs, index) -> {
            if (!signature.equals(rs.getString("signature"))) throw new BusinessException(409, "请求编号已用于其他导出");
            return decode(rs.getString("metadata"), Export.class);
        }, employee.tenantId(), employee.userId(), command.clientRequestId()));
    }

    public Download download(String id, boolean includeBytes) {
        Employee employee = Employee.current();
        var entry = single(jdbc.query("""
                SELECT result_id FROM sf_desktop_data_export WHERE id=? AND tenant_id=? AND owner_id=? AND expires_at>now()
                """, (rs, index) -> rs.getString(1), id, employee.tenantId(), employee.userId()));
        requireExport(result(entry));
        return single(jdbc.query("""
                SELECT metadata, content FROM sf_desktop_data_export WHERE id=? AND tenant_id=? AND owner_id=? AND expires_at>now()
                """, (rs, index) -> new Download(decode(rs.getString("metadata"), Export.class),
                includeBytes ? rs.getBytes("content") : null), id, employee.tenantId(), employee.userId()));
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void prune() {
        jdbc.update("DELETE FROM sf_desktop_data_export WHERE expires_at<=now()");
        jdbc.update("DELETE FROM sf_desktop_data_result WHERE expires_at<=now()");
        jdbc.update("DELETE FROM sf_desktop_data_query WHERE expires_at<=now()");
    }

    private void conversation(String id, boolean writable) {
        var row = workspace.conversation(id);
        if (!"data".equals(row.type()) || (writable && row.archived())) throw new BusinessException(ResultCode.NOT_FOUND);
    }

    private void lockPolicy(String id) {
        var employee = Employee.current();
        jdbc.queryForObject("""
                SELECT revision FROM sf_desktop_dataset WHERE id=? AND tenant_id=? AND enabled FOR SHARE
                """, Long.class, id, employee.tenantId());
        jdbc.queryForObject("""
                SELECT can_export FROM sf_desktop_dataset_grant
                WHERE tenant_id=? AND dataset_id=? AND user_id=? FOR SHARE
                """, Boolean.class, employee.tenantId(), id, employee.userId());
    }

    private void lockConversation(String id) {
        conversation(id, true);
        jdbc.queryForObject("SELECT id FROM sf_desktop_conversation WHERE id=? FOR UPDATE", String.class, id);
        conversation(id, true);
    }

    private Query findQuery(String conversation, String column, String value) {
        String predicate = "id".equals(column) ? "id=?" : "request_id=?";
        return single(jdbc.query("""
                SELECT * FROM sf_desktop_data_query WHERE conversation_id=? AND expires_at>now() AND
                """ + predicate + " FOR UPDATE", (rs, index) -> new Query(rs.getString("id"),
                rs.getString("dataset_id"), rs.getString("question"), rs.getString("context"), rs.getString("plan_hash"),
                rs.getLong("policy_revision"), decode(rs.getString("plan"), Plan.class),
                rs.getString("result_id"), rs.getObject("expires_at", OffsetDateTime.class)), conversation, value));
    }

    private List<Policy> policies(String id) {
        var employee = Employee.current();
        boolean canExecute = authorization.hasPermission(EXECUTE);
        return jdbc.query("""
                SELECT p.*, g.can_export FROM sf_desktop_dataset p
                JOIN sf_desktop_dataset_grant g ON p.tenant_id=g.tenant_id AND p.id=g.dataset_id
                JOIN sf_semantic_version v ON CAST(v.id AS varchar)=p.version_id AND CAST(v.tenant_id AS varchar)=p.tenant_id
                WHERE p.tenant_id=? AND g.user_id=? AND p.enabled AND v.status='published' AND v.deleted=0
                  AND (CAST(? AS varchar) IS NULL OR p.id=?) ORDER BY p.name,p.id
                """, (rs, index) -> {
            var dataset = new Dataset(rs.getString("id"), rs.getString("name"), rs.getString("source_id"),
                    rs.getString("version_id"), rs.getLong("revision"), canExecute, canExecute && rs.getBoolean("can_export"));
            return new Policy(dataset, rs.getString("physical_object"),
                    List.of((String[]) rs.getArray("allowed_fields").getArray()));
        }, employee.tenantId(), employee.userId(), id, id);
    }

    private Policy policy(String id) {
        var policy = policies(id).stream().findFirst().orElseThrow(() -> new BusinessException(ResultCode.FORBIDDEN));
        sources.getById(policy.dataset().sourceId());
        return policy;
    }

    private PreparedSemanticQuery prepare(Policy policy, String question, String context) {
        var dataset = policy.dataset();
        var plan = queryEngine.prepare(question, dataset.publishedVersionId(), dataset.sourceId(), context);
        if (plan.ready()) checkLineage(plan, policy);
        return plan;
    }

    private void checkLineage(PreparedSemanticQuery plan, Policy policy) {
        if (!plan.ready() || plan.plan().query().lineage().isEmpty() || plan.plan().query().lineage().stream().anyMatch(
                line -> !policy.dataset().sourceId().equals(line.sourceId()) || !policy.physicalObject().equals(line.physicalObject())
                        || !policy.allowedFields().contains(line.physicalField()))) {
            throw new BusinessException(ResultCode.FORBIDDEN, "查询超出数据集已授权字段或行范围");
        }
    }

    private Plan view(PreparedSemanticQuery prepared, Policy policy) {
        var interpretation = prepared.interpretation();
        if (!prepared.ready()) {
            return new Plan(interpretation.status().name(), List.of(), List.of(),
                    List.of("请在「" + policy.dataset().name() + "」中明确要查询的指标、维度和时间范围。"),
                    List.of(), null, 0);
        }
        var intent = interpretation.intent();
        var columns = prepared.plan().query().columns().stream().map(c -> new Column(c.alias(), c.role())).toList();
        var filters = intent.filters().stream().map(f -> List.of(f.alias(), f.operator(), f.value())).toList();
        var range = intent.timeRange();
        Object timeRange = range == null ? null : Map.of("from", String.valueOf(range.from()),
                "to", String.valueOf(range.to()), "relativeDays", String.valueOf(range.relativeDays()));
        return new Plan("READY", columns, List.of(), List.of(), filters, timeRange, Math.min(ROW_LIMIT, intent.limit()));
    }

    private void requireExport(Result result) {
        if (!policy(result.datasetId()).dataset().canExport()) throw new BusinessException(ResultCode.FORBIDDEN);
    }

    private void requireManage() {
        if (!authorization.hasPermission("database:source:manage")) throw new BusinessException(ResultCode.FORBIDDEN);
    }

    private void require(String permission) {
        if (!authorization.hasPermission(permission)) throw new BusinessException(ResultCode.FORBIDDEN);
    }

    private Object normalize(Object value) {
        return switch (value) {
            case Date day -> day.toLocalDate();
            case Timestamp time -> time.toLocalDateTime();
            default -> value;
        };
    }

    private static <T> T single(List<T> items) {
        return items.stream().findFirst().orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "记录不存在或已过期"));
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法保存查询结果", ex);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法读取查询结果", ex);
        }
    }
}
