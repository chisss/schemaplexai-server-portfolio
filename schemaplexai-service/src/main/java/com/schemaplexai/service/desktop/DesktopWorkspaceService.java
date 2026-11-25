package com.schemaplexai.service.desktop;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.service.desktop.DesktopEmployeeAccess.Employee;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DesktopWorkspaceService {
    private final JdbcTemplate jdbc;
    private final DesktopCapabilityService capabilities;

    public record Project(String id, String name, String description, boolean pinned, boolean archived,
                          long revision, OffsetDateTime updatedAt) {}
    public record Workbench(String id, String name, String agentId, String templateId, int templateVersion,
                            boolean pinned, boolean archived, long revision, DesktopWorkbenchTemplate template) {}
    public record Conversation(String id, String type, String title, String agentId, String projectId,
                               String workbenchId, boolean pinned, boolean archived, long revision,
                               OffsetDateTime lastActivityAt) {}

    public record CreateProject(@NotBlank @Size(max = 160) String name, @Size(max = 4000) String description,
                                @Size(max = 128) String clientRequestId) {}
    public record UpdateProject(@Size(max = 160) String name, @Size(max = 4000) String description,
                               Boolean pinned, Boolean archived, @NotNull Long expectedRevision) {}
    public record CreateWorkbench(@NotBlank String templateId, int templateVersion, @Size(max = 160) String name,
                                  boolean reuseExisting, @NotBlank @Size(max = 128) String clientRequestId) {}
    public record UpdateWorkbench(@Size(max = 160) String name, Boolean pinned, Boolean archived,
                                  @NotNull Long expectedRevision) {}
    public record CreateConversation(@NotBlank String type, @Size(max = 64) String agentId,
                                     @Size(max = 64) String projectId, @Size(max = 64) String workbenchId,
                                     @NotBlank @Size(max = 128) String clientRequestId) {}
    public record UpdateConversation(@Size(max = 160) String title, Boolean pinned, Boolean archived,
                                     @Size(max = 64) String projectId, @NotNull Long expectedRevision) {}
    public record ProjectResource(String attachmentId, String name, String mimeType, long byteSize) {}

    private static final RowMapper<Project> PROJECT = (rs, index) -> new Project(
            rs.getString("id"), rs.getString("name"), rs.getString("description"), rs.getBoolean("pinned"),
            rs.getBoolean("archived"), rs.getLong("revision"), rs.getObject("updated_at", OffsetDateTime.class));
    private static final RowMapper<Workbench> WORKBENCH = (rs, index) -> new Workbench(
            rs.getString("id"), rs.getString("name"), rs.getString("agent_id"), rs.getString("template_id"),
            rs.getInt("template_version"), rs.getBoolean("pinned"), rs.getBoolean("archived"),
            rs.getLong("revision"), template(rs.getString("template_id")).withVersion(rs.getInt("template_version"), true));
    private static final RowMapper<Conversation> CONVERSATION = (rs, index) -> new Conversation(
            rs.getString("id"), rs.getString("type"), rs.getString("title"), rs.getString("agent_id"),
            rs.getString("project_id"), rs.getString("workbench_id"), rs.getBoolean("pinned"),
            rs.getBoolean("archived"), rs.getLong("revision"),
            rs.getObject("last_activity_at", OffsetDateTime.class));

    public PageResult<Project> projects(int page, int size) {
        Employee employee = Employee.current();
        String scope = " FROM sf_employee_project WHERE tenant_id = ? AND owner_id = ? AND archived = false";
        long total = jdbc.queryForObject("SELECT count(*)" + scope, Long.class, employee.tenantId(), employee.userId());
        var items = jdbc.query("SELECT *" + scope + " ORDER BY pinned DESC, updated_at DESC, id LIMIT ? OFFSET ?",
                PROJECT, employee.tenantId(), employee.userId(), size, offset(page, size));
        return new PageResult<>(items, total, page, size);
    }

    public Project project(String id) {
        Employee employee = Employee.current();
        return single(jdbc.query("SELECT * FROM sf_employee_project WHERE tenant_id = ? AND owner_id = ? AND id = ?",
                PROJECT, employee.tenantId(), employee.userId(), id));
    }

    public Project createProject(CreateProject request) {
        Employee employee = Employee.current();
        String id = request.clientRequestId() == null ? UUID.randomUUID().toString()
                : stableId(employee, "project", request.clientRequestId());
        String normalized = name(request.name(), null);
        int inserted = jdbc.update("""
                INSERT INTO sf_employee_project(id,tenant_id,owner_id,name,description)
                VALUES(?,?,?,?,?) ON CONFLICT(id) DO NOTHING
                """, id, employee.tenantId(), employee.userId(), normalized, request.description());
        Project project = project(id);
        if (inserted == 0 && project.revision() == 1
                && (!normalized.equals(project.name()) || !Objects.equals(request.description(), project.description()))) {
            throw new BusinessException(409, "请求编号已用于其他项目");
        }
        return project;
    }

    public Project updateProject(String id, UpdateProject request) {
        Employee employee = Employee.current();
        Project existing = project(id);
        revision(jdbc.update("""
                UPDATE sf_employee_project SET name = ?, description = coalesce(?,description),
                    pinned = coalesce(?,pinned), archived = coalesce(?,archived),
                    revision = revision + 1, updated_at = now()
                WHERE id = ? AND tenant_id = ? AND owner_id = ? AND revision = ?
                """, name(request.name(), existing.name()), request.description(), request.pinned(), request.archived(),
                id, employee.tenantId(), employee.userId(), request.expectedRevision()));
        return project(id);
    }

    public List<DesktopWorkbenchTemplate> templates() {
        return DesktopWorkbenchTemplate.all().stream().map(t -> templateDetails(t.id())).toList();
    }

    public DesktopWorkbenchTemplate templateDetails(String id) {
        var template = template(id);
        var binding = binding(id);
        try {
            if (binding == null) {
                return template.availability(capabilities.bootstrap().defaultAgent() != null);
            }
            capabilities.requireExpert(binding.agentId(), true);
            return template.withVersion(binding.version(), true);
        } catch (BusinessException ex) {
            return template.withVersion(binding.version(), false);
        }
    }

    public record WorkbenchBinding(String agentId, int version) {}

    @Transactional
    public DesktopWorkbenchTemplate bindTemplate(String id, String agentId) {
        template(id);
        var employee = Employee.current();
        capabilities.requireExpert(agentId, false);
        jdbc.update("""
                INSERT INTO sf_desktop_workbench_binding(tenant_id,template_id,agent_id,version) VALUES(?,?,?,2)
                ON CONFLICT(tenant_id,template_id) DO UPDATE SET
                    agent_id=EXCLUDED.agent_id, version=sf_desktop_workbench_binding.version+1
                WHERE sf_desktop_workbench_binding.agent_id<>EXCLUDED.agent_id
                """, employee.tenantId(), id, agentId);
        return templateDetails(id);
    }

    private WorkbenchBinding binding(String id) {
        return jdbc.query("""
                SELECT agent_id,version FROM sf_desktop_workbench_binding WHERE tenant_id=? AND template_id=?
                """, (rs, index) -> new WorkbenchBinding(rs.getString(1), rs.getInt(2)),
                Employee.current().tenantId(), id).stream().findFirst().orElse(null);
    }


    public PageResult<Workbench> workbenches(int page, int size) {
        Employee employee = Employee.current();
        String scope = " FROM sf_desktop_workbench WHERE tenant_id = ? AND owner_id = ? AND archived = false";
        long total = jdbc.queryForObject("SELECT count(*)" + scope, Long.class, employee.tenantId(), employee.userId());
        var items = jdbc.query("SELECT *" + scope + " ORDER BY pinned DESC, updated_at DESC, id LIMIT ? OFFSET ?",
                WORKBENCH, employee.tenantId(), employee.userId(), size, offset(page, size));
        return new PageResult<>(items, total, page, size);
    }

    public Workbench workbench(String id) {
        Employee employee = Employee.current();
        return single(jdbc.query("SELECT * FROM sf_desktop_workbench WHERE tenant_id = ? AND owner_id = ? AND id = ?",
                WORKBENCH, employee.tenantId(), employee.userId(), id));
    }

    @Transactional
    public Workbench createWorkbench(CreateWorkbench request) {
        Employee employee = Employee.current();
        var template = template(request.templateId());
        String key = request.reuseExisting() ? "template:" + template.id() : "request:" + request.clientRequestId();
        String id = stableId(employee, "workbench", key);
        var previous = jdbc.query("SELECT * FROM sf_desktop_workbench WHERE id=? AND tenant_id=? AND owner_id=?",
                WORKBENCH, id, employee.tenantId(), employee.userId()).stream().findFirst().orElse(null);
        if (previous != null) {
            if (!template.id().equals(previous.templateId())) {
                throw new BusinessException(409, "请求编号已用于其他工作台");
            }
            capabilities.requireExpert(previous.agentId(), true);
            return reopen(previous, request.reuseExisting(), employee);
        }
        var binding = binding(template.id());
        int version = binding == null ? template.version() : binding.version();
        if (version != request.templateVersion()) {
            throw new BusinessException(409, "工作台模板版本已更新，请重新选择");
        }
        var assistant = binding == null ? capabilities.bootstrap().defaultAgent() : null;
        String agentId = binding == null ? (assistant == null ? null : assistant.id())
                : capabilities.requireExpert(binding.agentId(), true).getId();
        if (agentId == null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "企业默认助手尚未启用或未向当前用户开放");
        }
        jdbc.update("""
                INSERT INTO sf_desktop_workbench (id,tenant_id,owner_id,template_id,template_version,agent_id,name)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING
                """, id, employee.tenantId(), employee.userId(), template.id(), version, agentId,
                name(request.name(), template.name() + "工作台"));
        var existing = workbench(id);
        if (!template.id().equals(existing.templateId())) {
            throw new BusinessException(409, "请求编号已用于其他工作台");
        }
        return reopen(existing, request.reuseExisting(), employee);
    }

    private Workbench reopen(Workbench existing, boolean reuse, Employee employee) {
        String id = existing.id();
        if (reuse && existing.archived()) {
            jdbc.update("""
                    UPDATE sf_desktop_workbench SET archived=false, revision=revision+1, updated_at=now()
                    WHERE id=? AND tenant_id=? AND owner_id=? AND archived=true
                    """, id, employee.tenantId(), employee.userId());
        }
        return workbench(id);
    }

    public Workbench updateWorkbench(String id, UpdateWorkbench request) {
        Employee employee = Employee.current();
        Workbench existing = workbench(id);
        revision(jdbc.update("""
                UPDATE sf_desktop_workbench SET name = ?, pinned = coalesce(?,pinned), archived = coalesce(?,archived),
                    revision = revision + 1, updated_at = now()
                WHERE id = ? AND tenant_id = ? AND owner_id = ? AND revision = ?
                """, name(request.name(), existing.name()), request.pinned(), request.archived(),
                id, employee.tenantId(), employee.userId(), request.expectedRevision()));
        return workbench(id);
    }

    public PageResult<Conversation> conversations(String keyword, String projectId, String workbenchId,
                                                   boolean archived, int page, int size) {
        Employee employee = Employee.current();
        if (StringUtils.hasText(projectId)) project(projectId);
        if (StringUtils.hasText(workbenchId)) workbench(workbenchId);
        String scope = """
                 FROM sf_desktop_conversation WHERE tenant_id=? AND owner_id=? AND archived=?
                   AND last_activity_at IS NOT NULL AND (?='' OR project_id=?) AND (?='' OR workbench_id=?)
                   AND (?='' OR strpos(lower(title),lower(?)) > 0)
                """;
        String project = projectId == null ? "" : projectId;
        String bench = workbenchId == null ? "" : workbenchId;
        String filter = keyword == null ? "" : keyword.trim();
        Object[] args = {employee.tenantId(), employee.userId(), archived, project, project, bench, bench, filter, filter};
        long total = jdbc.queryForObject("SELECT count(*)" + scope, Long.class, args);
        var items = jdbc.query("SELECT *" + scope + " ORDER BY pinned DESC,last_activity_at DESC,id LIMIT ? OFFSET ?",
                CONVERSATION, employee.tenantId(), employee.userId(), archived, project, project, bench, bench, filter,
                filter, size, offset(page, size));
        return new PageResult<>(items, total, page, size);
    }

    public Conversation conversation(String id) {
        Employee employee = Employee.current();
        return single(jdbc.query("""
                SELECT * FROM sf_desktop_conversation WHERE tenant_id=? AND owner_id=? AND id=?
                """, CONVERSATION, employee.tenantId(), employee.userId(), id));
    }

    public boolean exists(String tenantId, String conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM sf_desktop_conversation WHERE tenant_id=? AND id=?)
                """, Boolean.class, tenantId, conversationId));
    }

    public Conversation createConversation(CreateConversation request) {
        Employee employee = Employee.current();
        if (!Set.of("general", "expert", "workbench", "data").contains(request.type())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "会话类型无效");
        }
        String agentId = resolveAgent(request);
        if (StringUtils.hasText(request.projectId()) && project(request.projectId()).archived()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "项目已归档");
        }
        String id = DesktopConversationService.CONVERSATION_PREFIX + stableId(employee, "conversation", request.clientRequestId());
        jdbc.update("""
                INSERT INTO sf_desktop_conversation(id,tenant_id,owner_id,type,agent_id,project_id,workbench_id,title)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING
                """, id, employee.tenantId(), employee.userId(), request.type(), agentId, request.projectId(),
                request.workbenchId(), "新工作任务");
        var existing = conversation(id);
        if (!request.type().equals(existing.type()) || !Objects.equals(agentId, existing.agentId())
                || !Objects.equals(request.workbenchId(), existing.workbenchId())) {
            throw new BusinessException(409, "请求编号已用于其他会话");
        }
        return existing;
    }

    public Conversation updateConversation(String id, UpdateConversation request) {
        Employee employee = Employee.current();
        var existing = conversation(id);
        if (StringUtils.hasText(request.projectId()) && project(request.projectId()).archived()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "项目已归档");
        }
        String projectId = request.projectId() == null ? existing.projectId()
                : (request.projectId().isEmpty() ? null : request.projectId());
        revision(jdbc.update("""
                UPDATE sf_desktop_conversation SET title=?,pinned=coalesce(?,pinned),archived=coalesce(?,archived),
                   project_id=?,revision=revision+1,updated_at=now()
                WHERE id=? AND tenant_id=? AND owner_id=? AND revision=?
                """, name(request.title(), existing.title()), request.pinned(), request.archived(),
                projectId, id, employee.tenantId(), employee.userId(), request.expectedRevision()));
        return conversation(id);
    }

    public void activateConversation(String id, String firstTitle) {
        Employee employee = Employee.current();
        String title = firstTitle.strip();
        if (title.length() > 100) title = title.substring(0, 100);
        jdbc.update("""
                UPDATE sf_desktop_conversation SET last_activity_at=now(),updated_at=now(),
                  title=CASE WHEN last_activity_at IS NULL AND title='新工作任务' THEN ? ELSE title END
                WHERE tenant_id=? AND owner_id=? AND id=?
                """, title, employee.tenantId(), employee.userId(), id);
    }

    public List<ProjectResource> resources(String id) {
        Project project = project(id);
        return jdbc.query("""
                SELECT a.id,a.name,a.mime_type,a.byte_size FROM sf_employee_project_resource r
                JOIN sf_desktop_attachment a ON a.id=r.attachment_id
                WHERE r.project_id=? AND a.tenant_id=? AND a.owner_id=? AND a.expires_at>now()
                ORDER BY a.id LIMIT 100
                """, (rs, i) -> new ProjectResource(rs.getString("id"), rs.getString("name"),
                rs.getString("mime_type"), rs.getLong("byte_size")), project.id(),
                Employee.current().tenantId(), Employee.current().userId());
    }

    public void addResource(String id, String attachmentId) {
        Employee employee = Employee.current();
        var project = project(id);
        if (project.archived()) throw new BusinessException(ResultCode.BAD_REQUEST, "项目已归档");
        int count = jdbc.update("""
                INSERT INTO sf_employee_project_resource(project_id,attachment_id)
                SELECT ?,id FROM sf_desktop_attachment WHERE id=? AND tenant_id=? AND owner_id=? AND expires_at>now()
                ON CONFLICT DO NOTHING
                """, id, attachmentId, employee.tenantId(), employee.userId());
        if (count == 0 && resources(id).stream().noneMatch(r -> r.attachmentId().equals(attachmentId))) {
            throw new BusinessException(ResultCode.NOT_FOUND, "资料不存在或已过期");
        }
    }

    public void removeResource(String id, String attachmentId) {
        project(id);
        jdbc.update("DELETE FROM sf_employee_project_resource WHERE project_id=? AND attachment_id=?", id, attachmentId);
    }

    private String resolveAgent(CreateConversation request) {
        if ("data".equals(request.type())) {
            if (request.agentId() != null || request.workbenchId() != null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "数据会话不能绑定专家或工作台");
            }
            return null;
        }
        if ("workbench".equals(request.type())) {
            var bench = workbench(request.workbenchId());
            if (bench.archived()) throw new BusinessException(ResultCode.BAD_REQUEST, "工作台已归档");
            return capabilities.requireExpert(bench.agentId(), true).getId();
        }
        if (request.workbenchId() != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请在工作台内创建专属会话");
        }
        if ("expert".equals(request.type())) {
            return capabilities.requireExpert(request.agentId(), true).getId();
        }
        var assistant = capabilities.bootstrap().defaultAgent();
        if (assistant == null) throw new BusinessException(ResultCode.FORBIDDEN, "企业默认助手不可用");
        return assistant.id();
    }

    private static DesktopWorkbenchTemplate template(String id) {
        return DesktopWorkbenchTemplate.all().stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "工作台模板不存在"));
    }

    static String stableId(Employee employee, String kind, String key) {
        return UUID.nameUUIDFromBytes(String.join("\u0000", employee.tenantId(), employee.userId(), kind, key)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    static <T> T single(List<T> items) {
        return items.stream().findFirst().orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND));
    }

    static void revision(int count) {
        if (count != 1) throw new BusinessException(409, "记录已更新，请刷新后重试");
    }

    static long offset(int page, int size) {
        return (long) (page - 1) * size;
    }

    private static String name(String value, String fallback) {
        if (value == null) return fallback;
        if (!StringUtils.hasText(value)) throw new BusinessException(ResultCode.BAD_REQUEST, "名称不能为空");
        return value.trim();
    }
}
