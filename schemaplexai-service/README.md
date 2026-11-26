# SchemaPlexAI Service

业务服务模块，承载数据库数据源、Agent 执行、工具治理和语义查询的应用编排与基础设施适配。

旧 PostgreSQL 库的 Agent 记忆正文列通过 `db/migration/agent-memory-content-text.sql` 升级。`AgentMemoryContentMigrationPostgresTest` 使用 `DB_TEST_URL` 在临时表上验证长记忆和兼容性。

## 企业 Desktop 执行桥

`com.schemaplexai.service.desktop` 将显式指定设备的 `sys.*` 调用路由到企业员工本机，保持 Server Agent 循环及沙箱、安全检查。设备登记、领取、执行前核验和结果回传绑定 JWT 租户与用户；本机执行的历史、会话和控制限定 owner。当前为单节点有界内存通道，不重发未知结果的写操作。协议与部署限制见 [设备桥设计](src/main/java/com/schemaplexai/service/desktop/DESIGN.md)。

## 语义数据查询

数据库接入能力位于 `com.schemaplexai.service.database`：

- 数据源读取显式绑定当前租户；
- 数据库连接凭据通过 `secretRef` 引用，运行时才物化到内存；
- 只读 SQL 通过 JSQLParser AST 门禁。

语义存储基础设施位于 `com.schemaplexai.service.semantic.infrastructure.jena`：

- Apache Jena 版本由父 POM 的 `jena-bom` 统一管理；
- TDB2 Dataset 由 `SemanticDatasetManager` 独占生命周期并统一事务；
- 图 IRI 由服务端生成，临时图使用 operationId 隔离并发操作；
- 租户 Dataset View 只暴露当前模型版本的业务图，并禁止写事务和底层解包。
- `OntologyStorePort` 只接受结构化三元组；`OntologyGraphApplicationService` 在写入前按当前租户查询版本，并拒绝已发布/归档版本。
- 邻域查询使用 focus、keyword、depth、page、size 参数，节点页大小受 `maxGraphNodes`（默认 500）限制，关系边只返回页内两端均可见的边。
- `SemanticPublishApplicationService` 使用 revision 乐观锁串联版本校验、SHACL、推理和活动版本切换；HTTP API 固定使用服务端基线 shapes，不接受客户端提交规则脚本、shape 三元组或原始 SPARQL。
- 发布只物化子类、子属性、domain、range、等价类和等价属性白名单规则；推理结果受 `maxInferredTriples` 硬配额约束。

语义控制面位于 `com.schemaplexai.service.semantic`：

业务本体支持按租户的系统和业务域归属。结构化草稿和已发布的 RDF 产物使用 PostgreSQL 版本行和事务，兼容查询与浏览已有 TDB2 图。当前结构化发布开放 Agent 上下文，查询指标与业务证据校验显示为待配置。配置助手从 SQL 初始化的固定身份读取提示，按授权和使用者挑选的 Schema 生成候选。Solo Agent 可引用已发布定义，实际版本在运行启动时固定。


- `domain` 保存模型和版本聚合、仓储端口及纯状态转换；
- `application` 编排当前租户下的模型 CRUD 和草稿版本创建；
- `infrastructure.database` 适配 MyBatis，并通过 tenant + revision 条件执行读写；
- 草稿版本可绑定当前租户的 Schema 快照，后续自动构建本体时可追溯到确定的物理结构基线；
- PostgreSQL 迁移见工作区 `sql/54_semantic_catalog.sql`。

Schema 快照链路复用租户数据库 MCP 数据源：

- PostgreSQL、MySQL 读取 `information_schema` 与约束、索引元数据；
- ClickHouse 读取 `system.tables`、`system.columns`、引擎与分区键；
- MongoDB 只调用集合、结构和索引白名单操作，受限采样仅保留字段类型分布；
- 规范化结构排序后计算 SHA-256 fingerprint，相同租户、数据源和 fingerprint 幂等复用；
- `SchemaScanApplicationService` 提供扫描和快照历史应用接口，并由 `DatabaseSchemaController` 暴露租户受控的 REST 契约。

语义模型 Controller 已接入模型、版本、Schema 扫描、本体图编辑、校验和发布 REST 契约。语义查询执行链路已经交付：

- `SemanticQueryInterpretationService` 将自然语言解释为结构化 `QueryIntent`，歧义返回澄清问题；
- `QueryPlanCompilationService` 为 PostgreSQL、MySQL、ClickHouse 编译参数化 SQL，为 MongoDB 编译受控 aggregation；
- `SignedQueryPlan`、HMAC 和 `planHash` 保证执行计划由服务端生成且在用户确认后未发生变化；
- `DatabaseSemanticQueryExecutorAdapter` 复用 SQL AST 只读门禁，`MongoAggregationSemanticQueryExecutorAdapter` 执行单集合、白名单阶段的 aggregation；
- Mongo MCP 工具支持受控别名、数据源配置覆盖和 `inputSchema` 参数协商，未知工具拒绝执行；
- 查询结果携带语义 IRI 到物理表字段的血缘，执行摘要由 `MybatisSemanticQueryAuditAdapter` 持久化到 `sf_audit_log`；
- 审计不保存问题原文、SQL、pipeline、参数值、结果行或凭据。

对外提供 `/semantic/query/interpret`、`/plan`、`/explain`、`/execute` 四阶段 REST 契约。客户端不能提交 graph IRI、原始 SPARQL、SQL、Mongo pipeline 或物理字段映射。

详细方案见工作区的 [后端语义查询技术文档](../../document/tech/backend/07-语义数据查询与本体引擎技术文档.md)。

## Agent 执行 Harness

执行 API 支持可选字段 `requiredOutputJson`。将 JSON Pointer 映射到要求的类型，例如 `{"/result/count":"integer","/result/ok":"boolean"}`。详情的 `acceptanceStatus` 可为 `accepted`、`partial`、`unverified` 或 `rejected`。配置了桌面验证命令时，设备回执仍是独立验收条件。

应用 `agent-execution-harness.sql` 后，新建根 Solo 执行可在租约过期时由创建者调用 `POST /agents/{id}/executions/{execId}/recover`。用原执行 ID 查状态和回放。已确认工具回执按调用 ID 恢复，派发后状态未知的工具进入人工核对。

PR 门禁使用 `scripts/agent-harness-gate.sh`。本地运行时为 `AGENT_HARNESS_DB_URL` 提供隔离 PostgreSQL 连接，按需设置 `AGENT_HARNESS_DB_USER` 和 `AGENT_HARNESS_DB_PASSWORD`。测试夹具会创建并清理独立 schema。

### 可靠性与任务验收

- 轨迹评估的协调器、评估条目、Solo 和 Team 分别使用独立线程池。评估队列饱和时任务标记为失败，可从评估页重试。
- 在应用 `agent-harness-reliability.sql` 后升级服务。根 Solo 每次模型派发前保存额度预占，终态事件可自动重投。SSE 客户端可用 `Last-Event-ID` 重连，终态事件使用稳定 ID。
- 支持 `requiredOutputValues`，例如 `{"/ok":true}`。期望交付特定版本时可提供 `requiredOutputSha256`，服务端对实际存入输出的 UTF-8 字节计算 SHA-256。详情的 `acceptanceOutputSha256` 可用来绑定后续消费。
