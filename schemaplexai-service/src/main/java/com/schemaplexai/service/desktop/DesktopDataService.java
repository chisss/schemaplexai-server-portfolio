package com.schemaplexai.service.desktop;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.desktop.DesktopEmployeeAccess.Employee;
import com.schemaplexai.service.security.AuthorizationService;
import com.schemaplexai.service.semantic.application.orchestration.AutomaticDatasetQueryService;
import com.schemaplexai.service.semantic.application.orchestration.AutomaticDatasetQueryService.Candidate;
import com.schemaplexai.service.semantic.application.orchestration.CompositeQueryPlanner;
import com.schemaplexai.service.semantic.application.orchestration.SemanticQueryExecutionApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.ExploratoryQueryScopeApplicationService;
import com.schemaplexai.service.semantic.application.query.PreparedSemanticQuery;
import com.schemaplexai.service.semantic.application.query.SemanticQueryDatasetAccess;
import com.schemaplexai.service.semantic.domain.model.query.QueryResultValidation;
import com.schemaplexai.service.semantic.domain.model.query.QueryResultValidation.BusinessStatus;
import com.schemaplexai.model.vo.semantic.SemanticQueryValidationVO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

@Service
public class DesktopDataService {
    private static final String INTERPRET = "semantic:query:interpret";
    private static final String EXECUTE = "semantic:query:execute";
    private static final String EXPLORE = "semantic:query:explore";
    private static final int ROW_LIMIT = 5000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DesktopWorkspaceService workspace;
    private final AuthorizationService authorization;
    private final DatabaseSourceService sources;
    private final SemanticQueryExecutionApplicationService queryEngine;
    private final ExploratoryQueryScopeApplicationService exploratoryScope;
    private final AutomaticDatasetQueryService automaticQueries;

    @Autowired
    public DesktopDataService(JdbcTemplate jdbc, ObjectMapper json, DesktopWorkspaceService workspace,
                              AuthorizationService authorization, DatabaseSourceService sources,
                              SemanticQueryExecutionApplicationService queryEngine,
                              ExploratoryQueryScopeApplicationService exploratoryScope,
                              AutomaticDatasetQueryService automaticQueries) {
        this.jdbc = jdbc;
        this.json = json;
        this.workspace = workspace;
        this.authorization = authorization;
        this.sources = sources;
        this.queryEngine = queryEngine;
        this.exploratoryScope = exploratoryScope;
        this.automaticQueries = automaticQueries;
    }

    public DesktopDataService(JdbcTemplate jdbc, ObjectMapper json, DesktopWorkspaceService workspace,
                              AuthorizationService authorization, DatabaseSourceService sources,
                              SemanticQueryExecutionApplicationService queryEngine,
                              ExploratoryQueryScopeApplicationService exploratoryScope) {
        this(jdbc, json, workspace, authorization, sources, queryEngine, exploratoryScope, null);
    }

    public DesktopDataService(JdbcTemplate jdbc, ObjectMapper json, DesktopWorkspaceService workspace,
                              AuthorizationService authorization, DatabaseSourceService sources,
                              SemanticQueryExecutionApplicationService queryEngine) {
        this(jdbc, json, workspace, authorization, sources, queryEngine, null);
    }

    public record Dataset(String id, String name, String sourceId, String publishedVersionId, long policyRevision,
                          boolean canQuery, boolean canExport, boolean allowNl2sql, String schemaSnapshotId,
                          boolean canExplore, @JsonInclude(JsonInclude.Include.NON_NULL) List<String> allowedFields) {
        public Dataset(String id, String name, String sourceId, String publishedVersionId, long policyRevision,
                       boolean canQuery, boolean canExport) {
            this(id, name, sourceId, publishedVersionId, policyRevision, canQuery, canExport, false, null, false, null);
        }
    }
    private record Policy(Dataset dataset, String physicalObject, List<String> allowedFields, String schemaSnapshotId) {
        private SemanticQueryDatasetAccess access() {
            return new SemanticQueryDatasetAccess(dataset.id(), dataset.policyRevision(),
                    dataset.publishedVersionId(), dataset.sourceId(), physicalObject, allowedFields,
                    schemaSnapshotId, dataset.canExplore());
        }
    }
    public record Ask(@Size(max = 128) String datasetId, @NotBlank @Size(max = 2000) String question,
                      @Size(max = 1000) String context, @NotBlank @Size(max = 128) String clientRequestId) {}
    public record Query(String id, String datasetId, String question, String context, String planHash,
                        long policyRevision, Plan plan, String resultId, OffsetDateTime expiresAt, String datasetName) {
        private Query withDataset(Dataset dataset) {
            return new Query(id, datasetId, question, context, planHash, policyRevision, plan, resultId, expiresAt,
                    dataset.name());
        }
    }
    public record AutomaticPlan(Dataset dataset, PreparedSemanticQuery prepared) {}
    public record DataTurn(String id, String question, List<Query> queries, List<String> progress,
                           String presentation) {}
    public record Plan(String status, List<Column> columns, List<?> clarifications, List<String> reasons,
                       List<?> filters, Object timeRange, int rowLimit, String queryPath, String routeReason,
                       boolean comparableMonth) {}
    public record Column(String key, String role, String label) {
        public Column(String key, String role) {
            this(key, role, null);
        }
    }
    public record Result(String id, String datasetId, long snapshotRevision, List<Column> columns,
                         List<Map<String, Object>> rows, int rowCount, boolean truncated, String auditId,
                         List<String> warnings, OffsetDateTime expiresAt, String queryPath,
                         SemanticQueryValidationVO validation) {}
    public record ExportRequest(@NotBlank String resultId, @NotNull Long snapshotRevision,
                                @NotBlank String format, @Size(max = 100) List<@NotBlank String> columns,
                                @NotBlank String scope, @NotBlank @Size(max = 128) String clientRequestId) {}
    public record DatasetDefinition(@NotBlank @Size(max = 160) String name,
                                    @NotBlank @Size(max = 64) String sourceId,
                                    @Size(max = 64) String publishedVersionId,
                                    @NotBlank @Size(max = 256) String physicalObject,
                                    @Size(min = 1, max = 100) List<@NotBlank @Size(max = 128) String> allowedFields,
                                    @NotNull Boolean enabled, @NotNull Long revision,
                                    @Size(max = 64) String schemaSnapshotId, Boolean allowNl2sql) {
        public DatasetDefinition(String name, String sourceId, String publishedVersionId, String physicalObject,
                                 List<String> allowedFields, Boolean enabled, Long revision) {
            this(name, sourceId, publishedVersionId, physicalObject, allowedFields, enabled, revision, null, false);
        }
    }
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
                rs.getString("version_id"), rs.getLong("revision"), true, true, rs.getBoolean("allow_nl2sql"),
                rs.getString("schema_snapshot_id"), false, List.of((String[]) rs.getArray("allowed_fields").getArray())),
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
        String versionId = optionalId(request.publishedVersionId());
        boolean allowExplore = Boolean.TRUE.equals(request.allowNl2sql());
        String snapshotId = allowExplore ? optionalId(request.schemaSnapshotId()) : null;
        List<String> fields = request.allowedFields().stream().distinct().toList();
        if (allowExplore) {
            if (snapshotId == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "请为探索数据集选择完整的 Schema 快照");
            }
            exploratoryScope.authorize(new SemanticQueryDatasetAccess(id, request.revision(), versionId,
                    request.sourceId(), request.physicalObject(), fields, snapshotId, true));
        } else {
            requirePublishedDataset(request, employee, versionId, fields);
        }
        if (id == null) {
            id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO sf_desktop_dataset(id,tenant_id,name,source_id,version_id,physical_object,
                        allowed_fields,enabled,allow_nl2sql,schema_snapshot_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, id, employee.tenantId(), request.name().trim(), request.sourceId(), versionId,
                    request.physicalObject(), fields.toArray(String[]::new), request.enabled(), allowExplore, snapshotId);
        } else {
            int changed = jdbc.update("""
                    UPDATE sf_desktop_dataset SET name=?,source_id=?,version_id=?,physical_object=?,allowed_fields=?,
                       enabled=?,allow_nl2sql=?,schema_snapshot_id=?,revision=revision+1
                    WHERE id=? AND tenant_id=? AND revision=?
                    """, request.name().trim(), request.sourceId(), versionId, request.physicalObject(),
                    fields.toArray(String[]::new), request.enabled(), allowExplore, snapshotId, id, employee.tenantId(),
                    request.revision());
            DesktopWorkspaceService.revision(changed);
        }
        return jdbc.query("""
                SELECT p.* FROM sf_desktop_dataset p WHERE p.tenant_id=? AND p.id=?
                """, (rs, index) -> new Dataset(rs.getString("id"), rs.getString("name"), rs.getString("source_id"),
                rs.getString("version_id"), rs.getLong("revision"), true, true, rs.getBoolean("allow_nl2sql"),
                rs.getString("schema_snapshot_id"), false, fields), employee.tenantId(), id).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND));
    }

    private void requirePublishedDataset(DatasetDefinition request, Employee employee, String versionId, List<String> fields) {
        boolean versionOwned = versionId != null && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM sf_semantic_version v
                LEFT JOIN sf_ontology_configuration_artifact a ON a.tenant_id=v.tenant_id AND a.version_id=v.id
                WHERE CAST(v.id AS varchar)=?
                    AND CAST(v.tenant_id AS varchar)=? AND v.status='published' AND v.deleted=0
                    AND (a.version_id IS NULL OR (
                         a.published_checksum=v.checksum AND a.query_profile_json->>'sourceId'=?
                         AND a.query_profile_json->>'physicalObject'=?
                         AND NOT EXISTS (
                             SELECT 1 FROM unnest(?::text[]) AS requested(field)
                             WHERE NOT EXISTS (
                                 SELECT 1
                                 FROM jsonb_array_elements(
                                     (a.query_profile_json->'measures') || (a.query_profile_json->'dimensions')
                                         || (a.query_profile_json->'filters')
                                         || jsonb_build_array(a.query_profile_json->'time')
                                 ) AS binding
                                 WHERE binding->>'physicalField'=requested.field)))))
                """, Boolean.class, versionId, employee.tenantId(), request.sourceId(),
                request.physicalObject(), fields.toArray(String[]::new)));
        if (!versionOwned) {
            throw new BusinessException(ResultCode.FORBIDDEN, "语义版本必须属于当前企业、具备查询能力并与数据集范围一致");
        }
    }

    private String optionalId(String id) {
        return id == null || id.isBlank() ? null : id.trim();
    }

    @Transactional
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
        int changed = jdbc.update("""
                INSERT INTO sf_desktop_dataset_grant(tenant_id,dataset_id,user_id,can_export)
                VALUES(?,?,?,?) ON CONFLICT(tenant_id,dataset_id,user_id)
                DO UPDATE SET can_export=EXCLUDED.can_export
                WHERE sf_desktop_dataset_grant.can_export IS DISTINCT FROM EXCLUDED.can_export
                """, employee.tenantId(), id, grant.userId(), grant.canExport());
        if (changed > 0) bumpDatasetRevision(employee, id);
    }

    @Transactional
    public void revokeDataset(String id, String userId) {
        requireManage();
        var employee = Employee.current();
        jdbc.query("""
                SELECT id FROM sf_desktop_dataset WHERE id=? AND tenant_id=? FOR UPDATE
                """, (rs, row) -> rs.getString("id"), id, employee.tenantId());
        int changed = jdbc.update("""
                DELETE FROM sf_desktop_dataset_grant WHERE tenant_id=? AND dataset_id=? AND user_id=?
                """, employee.tenantId(), id, userId);
        if (changed > 0) bumpDatasetRevision(employee, id);
    }

    private void bumpDatasetRevision(Employee employee, String id) {
        jdbc.update("UPDATE sf_desktop_dataset SET revision=revision+1 WHERE id=? AND tenant_id=?",
                id, employee.tenantId());
    }

    public List<Dataset> catalog() {
        require(INTERPRET);
        return policies(null).stream().map(Policy::dataset).toList();
    }

    public AutomaticPlan planAutomatically(String question, String context) {
        return planAutomatically(question, context, ignored -> {});
    }

    public AutomaticPlan planAutomatically(String question, String context, Consumer<String> progress) {
        require(INTERPRET);
        var policies = policies(null);
        var resolution = automaticQueries.plan(question, context, candidates(policies), progress);
        return automaticPlan(policies, resolution);
    }

    public AutomaticPlan planJointly(String question, String context, List<CompositeQueryPlanner.Part> parts) {
        require(INTERPRET);
        var policies = policies(null);
        var resolution = automaticQueries.planJointly(question, context, candidates(policies), parts);
        return resolution == null ? null : automaticPlan(policies, resolution);
    }

    private List<Candidate> candidates(List<Policy> policies) {
        return policies.stream().map(policy -> new Candidate(policy.dataset().name(), policy.access())).toList();
    }

    private AutomaticPlan automaticPlan(List<Policy> policies, AutomaticDatasetQueryService.Resolution resolution) {
        var dataset = resolution.candidate() == null ? null : policies.stream().map(Policy::dataset)
                .filter(item -> item.id().equals(resolution.candidate().access().datasetId())).findFirst().orElseThrow();
        return new AutomaticPlan(dataset, resolution.prepared());
    }

    public SemanticQueryDatasetAccess queryAccess(String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "查询需要已授权的数据集");
        }
        return policy(datasetId).access();
    }

    public SemanticQueryDatasetAccess queryAccessForConfirmation(String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "查询需要已授权的数据集");
        }
        lockPolicy(datasetId);
        return policy(datasetId).access();
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
                rs.getString("result_id"), rs.getObject("expires_at", OffsetDateTime.class), null),
                conversation, size, DesktopWorkspaceService.offset(page, size)).stream()
                .filter(query -> query.datasetId() != null && policies(query.datasetId()).stream().anyMatch(
                        policy -> policy.dataset().policyRevision() == query.policyRevision() && canReadPlan(query.plan(), policy)))
                .toList();
    }

    public List<DataTurn> turns(String conversation, int page, int size) {
        require(INTERPRET);
        conversation(conversation, false);
        return jdbc.query("""
                SELECT id, question, presentation, progress, created_at, true AS grouped
                FROM sf_desktop_data_turn WHERE conversation_id=? AND expires_at>now()
                UNION ALL
                SELECT id, question, 'SEPARATE', '[]'::jsonb, created_at, false
                FROM sf_desktop_data_query WHERE conversation_id=? AND turn_id IS NULL AND expires_at>now()
                ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """, (rs, index) -> new TurnRow(rs.getString("id"), rs.getString("question"),
                rs.getString("presentation"), rs.getString("progress"), rs.getBoolean("grouped")),
                conversation, conversation, size, DesktopWorkspaceService.offset(page, size)).stream()
                .map(row -> loadTurn(conversation, row))
                .filter(turn -> !turn.queries().isEmpty()).toList();
    }

    private record TurnRow(String id, String question, String presentation, String progress, boolean grouped) {}

    private DataTurn loadTurn(String conversation, TurnRow row) {
        var queries = jdbc.query("""
                SELECT * FROM sf_desktop_data_query WHERE conversation_id=? AND expires_at>now()
                AND ((? AND turn_id=?) OR (NOT ? AND id=?)) ORDER BY turn_position,id
                """, (rs, index) -> new Query(rs.getString("id"), rs.getString("dataset_id"),
                rs.getString("question"), rs.getString("context"), rs.getString("plan_hash"),
                rs.getLong("policy_revision"), decode(rs.getString("plan"), Plan.class),
                rs.getString("result_id"), rs.getObject("expires_at", OffsetDateTime.class), null),
                conversation, row.grouped(), row.id(), row.grouped(), row.id());
        var granted = new LinkedHashMap<String, Policy>();
        policies(null).forEach(policy -> granted.put(policy.dataset().id(), policy));
        var visible = queries.stream().map(query -> {
            if (query.datasetId() == null) {
                return query;
            }
            var policy = granted.get(query.datasetId());
            return policy != null && policy.dataset().policyRevision() == query.policyRevision()
                    && canReadPlan(query.plan(), policy) ? query.withDataset(policy.dataset()) : null;
        }).filter(Objects::nonNull).toList();
        return new DataTurn(row.id(), row.question(), visible, List.of(decode(row.progress(), String[].class)),
                row.presentation());
    }

    @Transactional
    public DataTurn askTurn(String conversation, Ask command, CompositeQueryPlanner planner, Consumer<String> progress) {
        require(INTERPRET);
        lockConversation(conversation);
        String turnId = UUID.nameUUIDFromBytes((conversation + ":" + command.clientRequestId())
                .getBytes(StandardCharsets.UTF_8)).toString();
        var existing = jdbc.query("""
                SELECT * FROM sf_desktop_data_turn WHERE id=? AND conversation_id=? AND expires_at>now()
                """, (rs, index) -> {
            if (!command.question().equals(rs.getString("question"))
                    || !Objects.equals(command.context(), rs.getString("context"))) {
                throw new BusinessException(409, "请求编号已用于其他数据问题");
            }
            return new TurnRow(turnId, rs.getString("question"), rs.getString("presentation"),
                    rs.getString("progress"), true);
        }, turnId, conversation);
        if (!existing.isEmpty()) {
            return loadTurn(conversation, existing.getFirst());
        }
        var messages = new ArrayList<String>();
        messages.add("正在识别问题中的查询目标");
        progress.accept(messages.getLast());
        var composite = planner.plan(command.question(), command.context());
        var joint = composite.parts().size() > 1 && composite.presentation() != CompositeQueryPlanner.Presentation.DISTINCT
                ? planJointly(command.question(), command.context(), composite.parts()) : null;
        var parts = joint == null ? composite.parts() : List.of(new CompositeQueryPlanner.Part(command.question(),
                command.question()));
        messages.add(joint == null ? "识别到 " + parts.size() + " 项查询，正在检查授权范围和业务口径"
                : "已验证多个指标可以共享已发布口径和统计条件");
        progress.accept(messages.getLast());
        String presentation = joint == null ? composite.presentation().name() : "JOINT";
        jdbc.update("""
                INSERT INTO sf_desktop_data_turn(id,conversation_id,question,context,presentation,progress,expires_at)
                VALUES(?,?,?,?,?,CAST(? AS jsonb),now()+interval '7 days')
                """, turnId, conversation, command.question(), command.context(), presentation, "[]");
        for (int index = 0; index < parts.size(); index++) {
            var part = parts.get(index);
            int position = index + 1;
            progress.accept("正在规划第 " + position + " 项查询");
            AutomaticPlan automatic = joint == null ? tryPlan(part.question(), command.context(), message -> {
                messages.add("第 " + position + " 项：" + message);
                progress.accept(messages.getLast());
            }) : joint;
            var dataset = automatic == null ? null : automatic.dataset();
            var prepared = automatic == null ? null : automatic.prepared();
            var plan = automatic == null
                    ? new Plan("REJECTED", List.of(), List.of(), List.of("该项查询暂时无法规划，请补充业务条件后重试。"),
                            List.of(), null, 0, null, null, false)
                    : view(prepared, dataset);
            String queryId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO sf_desktop_data_query(id,conversation_id,dataset_id,policy_revision,question,context,
                        request_id,plan_hash,plan,expires_at,turn_id,turn_position)
                    VALUES(?,?,?,?,?,?,?,?,CAST(? AS jsonb),now()+interval '7 days',?,?)
                    """, queryId, conversation, dataset == null ? null : dataset.id(),
                    dataset == null ? 0L : dataset.policyRevision(), part.question(), command.context(), queryId,
                    prepared != null && prepared.ready() ? prepared.plan().planHash() : null, encode(plan), turnId, index);
            String status = prepared != null && prepared.ready() ? ("NL2SQL".equals(plan.queryPath()) ? "已生成探索查询计划" : "已生成语义查询计划")
                    : "需要补充条件或未找到适用口径";
            messages.add("第 " + (index + 1) + " 项：" + status);
            progress.accept(messages.getLast());
        }
        String storedProgress = encode(messages);
        jdbc.update("UPDATE sf_desktop_data_turn SET progress=CAST(? AS jsonb) WHERE id=?", storedProgress, turnId);
        workspace.activateConversation(conversation, command.question());
        return loadTurn(conversation, new TurnRow(turnId, command.question(), presentation, storedProgress, true));
    }

    private AutomaticPlan tryPlan(String question, String context, Consumer<String> progress) {
        try {
            return planAutomatically(question, context, progress);
        } catch (BusinessException failure) {
            return null;
        }
    }

    @Transactional
    public Query ask(String conversation, Ask command) {
        require(INTERPRET);
        lockConversation(conversation);
        String requestedDataset = optionalId(command.datasetId());
        var existing = queryRows(conversation, "request_id", command.clientRequestId());
        if (!existing.isEmpty()) {
            var query = existing.getFirst();
            if ((requestedDataset != null && !requestedDataset.equals(query.datasetId()))
                    || !command.question().equals(query.question())
                    || !Objects.equals(command.context(), query.context())) {
                throw new BusinessException(409, "请求编号已用于其他数据问题");
            }
            var existingPolicy = policy(query.datasetId());
            requireCurrentPlan(existingPolicy, query.plan(), query.policyRevision());
            return query.withDataset(existingPolicy.dataset());
        }
        AutomaticPlan automatic = requestedDataset == null ? planAutomatically(command.question(), command.context()) : null;
        if (automatic != null && !automatic.prepared().ready()) {
            var plan = view(automatic.prepared(), automatic.dataset());
            return new Query(UUID.randomUUID().toString(), null, command.question(), command.context(), null,
                    0, plan, null, null, null);
        }
        Policy explicitPolicy = automatic == null ? policy(requestedDataset) : null;
        Dataset dataset = automatic == null ? explicitPolicy.dataset() : automatic.dataset();
        PreparedSemanticQuery prepared = automatic == null
                ? prepare(explicitPolicy, command.question(), command.context()) : automatic.prepared();
        var plan = view(prepared, dataset);
        String hash = prepared.ready() ? prepared.plan().planHash() : null;
        jdbc.update("""
                INSERT INTO sf_desktop_data_query(id,conversation_id,dataset_id,policy_revision,question,context,
                         request_id,plan_hash,plan,expires_at)
                VALUES(?,?,?,?,?,?,?,?,CAST(? AS jsonb),now()+interval '7 days')
                ON CONFLICT(conversation_id,request_id) DO NOTHING
                """, UUID.randomUUID().toString(), conversation, dataset.id(), dataset.policyRevision(),
                command.question(), command.context(), command.clientRequestId(), hash, encode(plan));
        var query = findQuery(conversation, "request_id", command.clientRequestId());
        workspace.activateConversation(conversation, command.question());
        return query.withDataset(dataset);
    }

    @Transactional
    public Result execute(String conversation, String queryId, String expectedHash) {
        require(EXECUTE);
        lockConversation(conversation);
        Query request = findQuery(conversation, "id", queryId);
        if (request.datasetId() == null || request.planHash() == null) {
            throw new BusinessException(ResultCode.SEMANTIC_QUERY_PLAN_CHANGED);
        }
        lockPolicy(request.datasetId());
        Policy policy = policy(request.datasetId());
        requireCurrentPlan(policy, request.plan(), request.policyRevision());
        if (request.planHash() == null || !request.planHash().equals(expectedHash)) {
            throw new BusinessException(409, "数据授权或查询计划已更新，请重新提问");
        }
        if (request.resultId() != null) return result(request.resultId());
        var latest = jdbc.queryForObject("""
                SELECT coalesce(turn_id,id) FROM sf_desktop_data_query WHERE conversation_id=?
                ORDER BY created_at DESC,id DESC LIMIT 1
                """, String.class, conversation);
        var requestTurn = jdbc.queryForObject("SELECT coalesce(turn_id,id) FROM sf_desktop_data_query WHERE id=?",
                String.class, queryId);
        if (!Objects.equals(requestTurn, latest)) throw new BusinessException(409, "请确认当前最新的查询计划");
        var dataset = policy.dataset();
        var prepared = queryEngine.confirm(request.question(), dataset.publishedVersionId(), dataset.sourceId(),
                request.context(), policy.access(), expectedHash);
        checkLineage(prepared, policy);
        var output = queryEngine.execute(prepared, expectedHash, ROW_LIMIT, 30);
        var columns = request.plan().columns();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var outputRow : output.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Column column : columns) row.put(column.key(), normalize(outputRow.get(column.key())));
            rows.add(row);
        }
        String id = UUID.randomUUID().toString();
        var snapshot = new Result(id, request.datasetId(), request.policyRevision(), columns, rows,
                output.rowCount(), output.truncated(), output.auditId(), output.warnings(), OffsetDateTime.now().plusDays(1),
                prepared.plan().queryPath(), validationView(output.validation()));
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
        var stored = single(jdbc.query("""
                SELECT r.snapshot, q.plan FROM sf_desktop_data_result r JOIN sf_desktop_data_query q ON r.query_id=q.id
                JOIN sf_desktop_conversation c ON c.id=q.conversation_id
                WHERE r.id=? AND r.expires_at>now() AND c.tenant_id=? AND c.owner_id=?
                """, (rs, index) -> new StoredResult(decode(rs.getString("snapshot"), Result.class),
                decode(rs.getString("plan"), Plan.class)), id, employee.tenantId(), employee.userId()));
        var result = stored.result();
        requireCurrentPlan(policy(result.datasetId()), stored.plan(), result.snapshotRevision());
        return result;
    }

    private record StoredResult(Result result, Plan plan) {}

    private void requireCurrentPlan(Policy policy, Plan plan, long revision) {
        if (policy.dataset().policyRevision() != revision) {
            throw new BusinessException(409, "数据授权版本已更新");
        }
        if (!canReadPlan(plan, policy)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前账号或数据集未开放探索查询");
        }
    }

    private boolean canReadPlan(Plan plan, Policy policy) {
        return !"NL2SQL".equals(plan.queryPath()) || policy.dataset().canExplore();
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
        var bytes = new DesktopDataExport().encode(command.format(), selected, result.rows(), result.truncated(),
                result.validation());
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
        jdbc.update("""
                DELETE FROM sf_desktop_data_turn t WHERE t.expires_at<=now()
                AND NOT EXISTS(SELECT 1 FROM sf_desktop_data_query q WHERE q.turn_id=t.id)
                """);
    }

    private void conversation(String id, boolean writable) {
        var row = workspace.conversation(id);
        if (!"data".equals(row.type()) || (writable && row.archived())) throw new BusinessException(ResultCode.NOT_FOUND);
    }

    private void lockPolicy(String id) {
        var employee = Employee.current();
        var revisions = jdbc.query("""
                SELECT revision FROM sf_desktop_dataset WHERE id=? AND tenant_id=? AND enabled FOR SHARE
                """, (rs, index) -> rs.getLong("revision"), id, employee.tenantId());
        if (revisions.isEmpty()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "查询需要已授权的数据集");
        }
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
        return single(queryRows(conversation, column, value));
    }

    private List<Query> queryRows(String conversation, String column, String value) {
        String predicate = "id".equals(column) ? "id=?" : "request_id=?";
        return jdbc.query("""
                SELECT * FROM sf_desktop_data_query WHERE conversation_id=? AND expires_at>now() AND
                """ + predicate + " FOR UPDATE", (rs, index) -> new Query(rs.getString("id"),
                rs.getString("dataset_id"), rs.getString("question"), rs.getString("context"), rs.getString("plan_hash"),
                rs.getLong("policy_revision"), decode(rs.getString("plan"), Plan.class),
                rs.getString("result_id"), rs.getObject("expires_at", OffsetDateTime.class), null), conversation, value);
    }

    private List<Policy> policies(String id) {
        var employee = Employee.current();
        boolean canExecute = authorization.hasPermission(EXECUTE);
        boolean canExplore = authorization.hasPermission(EXPLORE);
        return jdbc.query("""
                SELECT p.*, g.can_export FROM sf_desktop_dataset p
                JOIN sf_desktop_dataset_grant g ON p.tenant_id=g.tenant_id AND p.id=g.dataset_id
                LEFT JOIN sf_semantic_version v ON CAST(v.id AS varchar)=p.version_id AND CAST(v.tenant_id AS varchar)=p.tenant_id
                LEFT JOIN sf_ontology_configuration_artifact a ON a.tenant_id=v.tenant_id AND a.version_id=v.id
                WHERE p.tenant_id=? AND g.user_id=? AND p.enabled
                  AND ((p.version_id IS NULL AND p.allow_nl2sql AND ?)
                    OR (v.status='published' AND v.deleted=0
                      AND (a.version_id IS NULL OR (a.published_checksum=v.checksum
                       AND a.query_profile_json->>'sourceId'=p.source_id
                       AND a.query_profile_json->>'physicalObject'=p.physical_object))))
                  AND (CAST(? AS varchar) IS NULL OR p.id=?) ORDER BY p.name,p.id
                """, (rs, index) -> {
            var dataset = new Dataset(rs.getString("id"), rs.getString("name"), rs.getString("source_id"),
                    rs.getString("version_id"), rs.getLong("revision"), canExecute, canExecute && rs.getBoolean("can_export"),
                    rs.getBoolean("allow_nl2sql"), null, canExplore && rs.getBoolean("allow_nl2sql"), null);
            return new Policy(dataset, rs.getString("physical_object"),
                    List.of((String[]) rs.getArray("allowed_fields").getArray()), rs.getString("schema_snapshot_id"));
        }, employee.tenantId(), employee.userId(), canExplore, id, id);
    }

    private Policy policy(String id) {
        var policy = policies(id).stream().findFirst().orElseThrow(() -> new BusinessException(ResultCode.FORBIDDEN));
        sources.getById(policy.dataset().sourceId());
        return policy;
    }

    private PreparedSemanticQuery prepare(Policy policy, String question, String context) {
        var dataset = policy.dataset();
        return queryEngine.prepare(question, dataset.publishedVersionId(), dataset.sourceId(), context, policy.access());
    }

    private void checkLineage(PreparedSemanticQuery plan, Policy policy) {
        if (!plan.ready()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "查询超出数据集已授权字段或行范围");
        }
        policy.access().requireLineage(plan.plan().query().lineage());
    }

    private Plan view(PreparedSemanticQuery prepared, Dataset dataset) {
        var interpretation = prepared.interpretation();
        if (!prepared.ready()) {
            var reasons = interpretation.reasons().isEmpty()
                    ? List.of(dataset == null ? "请明确要查询的指标、维度和时间范围。"
                        : "请补充「" + dataset.name() + "」的指标、维度或时间范围。")
                    : interpretation.reasons();
            return new Plan(interpretation.status().name(), List.of(), interpretation.clarifications(), reasons,
                    List.of(), null, 0, null, null, false);
        }
        var intent = interpretation.intent();
        var titles = new LinkedHashMap<String, String>();
        for (int index = 0; index < intent.metrics().size(); index++) {
            var metric = intent.metrics().get(index);
            String label = intent.exploratory() ? metric.alias() + "（" + aggregationLabel(metric.aggregation()) + "）"
                    : metric.alias();
            titles.put("metric_" + (index + 1), label);
        }
        for (int index = 0; index < intent.dimensions().size(); index++) {
            titles.put("dimension_" + (index + 1), intent.dimensions().get(index).alias());
        }
        var columns = prepared.plan().query().columns().stream()
                .map(c -> new Column(c.alias(), c.role(), titles.get(c.alias()))).toList();
        var filters = intent.filters().stream().map(f -> List.of(f.alias(), f.operator(), f.value())).toList();
        var range = intent.timeRange();
        Object timeRange = range == null ? null : Map.of("from", String.valueOf(range.from()),
                "to", String.valueOf(range.to()), "relativeDays", String.valueOf(range.relativeDays()));
        return new Plan("READY", columns, List.of(), List.of(), filters, timeRange,
                Math.min(ROW_LIMIT, intent.limit()), prepared.plan().queryPath(), prepared.plan().routeReason(),
                "mysql".equals(prepared.databaseType()) && intent.metrics().size() == 1
                        && intent.dimensions().size() == 1 && "month".equals(intent.dimensions().getFirst().timeGrain()));
    }

    private String aggregationLabel(String aggregation) {
        return switch (aggregation) {
            case "sum" -> "总和";
            case "avg" -> "平均值";
            case "count" -> "计数";
            case "min" -> "最小值";
            case "max" -> "最大值";
            default -> aggregation;
        };
    }

    private void requireExport(Result result) {
        if (!policy(result.datasetId()).dataset().canExport()) throw new BusinessException(ResultCode.FORBIDDEN);
        if (result.validation() != null && BusinessStatus.MISMATCH.name().equals(result.validation().businessStatus())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "查询结果对账有差异，复核前不可导出");
        }
    }

    private SemanticQueryValidationVO validationView(QueryResultValidation validation) {
        if (validation == null) {
            return null;
        }
        return new SemanticQueryValidationVO(validation.technicalStatus().name(), validation.resultStatus().name(),
                validation.businessStatus().name(), validation.businessReason(), validation.checkedAt(),
                validation.baselineLabel(), validation.comparedRows(), validation.missingReferenceGroups(),
                validation.extraReferenceGroups(), validation.differingGroups(),
                validation.comparedWindow() == null ? null : new SemanticQueryValidationVO.QueryComparedWindow(
                        validation.comparedWindow().from(), validation.comparedWindow().to(),
                        validation.comparedWindow().timeZone()));
    }

    private void requireManage() {
        if (!authorization.hasPermission("database:source:manage")) throw new BusinessException(ResultCode.FORBIDDEN);
    }

    private void require(String permission) {
        if (!authorization.hasPermission(permission)) throw new BusinessException(ResultCode.FORBIDDEN);
    }

    private Object normalize(Object value) {
        return switch (value) {
            case null -> null;
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
            return json.readerFor(type).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法读取查询结果", ex);
        }
    }
}
