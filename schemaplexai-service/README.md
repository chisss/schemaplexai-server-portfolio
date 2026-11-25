# SchemaPlexAI Service

业务服务模块，承载数据库数据源、Agent 执行、工具治理和语义查询的应用编排与基础设施适配。

旧 PostgreSQL 库升级时将 Agent 记忆正文列扩展为 `TEXT`，迁移脚本由完整交付环境提供。`AgentMemoryContentMigrationPostgresTest` 使用 `DB_TEST_URL` 在临时表上验证长记忆和兼容性。

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

- `domain` 保存模型和版本聚合、仓储端口及纯状态转换；
- `application` 编排当前租户下的模型 CRUD 和草稿版本创建；
- `infrastructure.database` 适配 MyBatis，并通过 tenant + revision 条件执行读写；
- 草稿版本可绑定当前租户的 Schema 快照，后续自动构建本体时可追溯到确定的物理结构基线；
- PostgreSQL 语义目录结构由完整交付环境的数据库迁移初始化。

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

语义层的完整查询流程见[项目说明](../README.md#本体驱动的企业数据查询)。


## Agent 执行 Harness

执行 API 支持通过 `requiredOutputJson` 声明交付断言，执行详情以 `acceptanceStatus` 记录结果。根 Solo 执行通过租约和已确认的工具回执恢复，Team 成员共享父执行预算。

用 `bash scripts/agent-harness-gate.sh` 运行公开快照的固定轨迹和运行时测试。数据库迁移及跨进程故障注入使用完整交付环境的迁移资源。
