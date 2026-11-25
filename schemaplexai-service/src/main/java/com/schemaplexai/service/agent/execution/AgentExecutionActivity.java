package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 供执行者查看的活动快照，每次更新都可以独立显示和回放。 */
public record AgentExecutionActivity(String id, Kind kind, Status status, String title, String summary,
                                     String source, int round,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) Instant startedAt,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt,
                                     String detail, Map<String, String> fields) {
    public static final String EVENT_TYPE = "ACTIVITY_STEP";

    public enum Kind {
        ANALYSIS("分析任务"), TEAM("团队分工"), READ("读取文件"), SEARCH("搜索"),
        EDIT("修改文件"), COMMAND("执行命令"), MCP("调用 MCP 工具"), FUNCTION("执行函数");

        private final String title;

        Kind(String title) {
            this.title = title;
        }

        public String toolTitle() {
            return title;
        }
    }

    public enum Status { RUNNING, COMPLETED, FAILED }

    public static Kind toolKind(String name, boolean mcp) {
        if (mcp) {
            return Kind.MCP;
        }
        return switch (name) {
            case "sys_read", "sys_stat", "read_skill_resource" -> Kind.READ;
            case "sys_ls", "sys_glob", "sys_grep", "search_tools" -> Kind.SEARCH;
            case "sys_write", "sys_edit", "sys_mkdir", "sys_rm", "sys_cp", "sys_mv" -> Kind.EDIT;
            case "sys_bash", "sys_exec_dangerous" -> Kind.COMMAND;
            default -> Kind.FUNCTION;
        };
    }

    public static AgentExecutionActivity start(Kind kind, String title, String summary, String source,
                                               int round, Map<String, String> fields) {
        Instant now = Instant.now();
        return new AgentExecutionActivity(UUID.randomUUID().toString(), kind, Status.RUNNING, title, summary,
                source, round, now, now, null, fields);
    }

    public AgentExecutionActivity finish(boolean success, String summary, String detail, Map<String, String> fields) {
        return new AgentExecutionActivity(id, kind, success ? Status.COMPLETED : Status.FAILED, title, summary,
                source, round, startedAt, Instant.now(), detail, fields);
    }

    public AgentExecutionActivity progress(String summary) {
        return new AgentExecutionActivity(id, kind, status, title, summary, source, round, startedAt,
                Instant.now(), detail, fields);
    }
}
