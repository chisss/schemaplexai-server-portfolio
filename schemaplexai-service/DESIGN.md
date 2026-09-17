# Service 设计决策

## 语义数据查询安全边界

数据库语义查询采用“结构化意图 → 服务端计划 → 方言查询”的链路。业务数据库不接受前端任意 SQL；当前兼容接口只允许单条只读语句，并由 `SqlReadOnlyGuard` 使用 JSQLParser AST 检查语句类型、`SELECT INTO` 和行锁。

数据库数据源和凭据始终按租户校验。连接配置只保存 `secretRef`，密文由 AES-GCM 凭据仓库存储，MCP 客户端创建前生成短生命周期内存副本。

Jena/TDB2 只存在于 `semantic.infrastructure.jena`。共享 Dataset 由 `SemanticDatasetManager` 持有，业务读取只能通过不可解包的只读门面访问服务端生成的图白名单。门面拒绝写事务，`close()` 不向下传递，避免调用方关闭共享存储。临时图包含 operationId，同一模型版本的并发校验互不覆盖。

语义模型与版本是独立聚合。聚合自身执行状态转换和 revision 校验，application 只编排当前租户上下文与仓储端口。数据库适配器的查询必须显式带 `tenant_id`；更新必须同时匹配 `tenant_id + id + revision`。数据库组合外键保证版本、映射和活动版本不能跨租户关联。

本体图编辑采用 `OntologyStorePort` 的结构化契约：调用方提供模型和版本标识及三元组，application 从 `SecurityUtil` 取得租户并读取版本聚合，只有 `DRAFT`/`INVALID` 可写；Jena 适配器根据服务端 `SemanticGraphIriFactory` 生成 asserted 图。读取同样先解析租户版本，再以受限 `GraphQuery` 返回分页邻域，默认不返回页外资源边，避免前端拿到未加载节点。

发布由 `SemanticPublishPort` 隔离 Jena 类型。适配器把 asserted 和 shapes 写入 operationId 隔离的临时图，执行 SHACL 后仅物化子类、子属性、domain、range、等价类和等价属性规则；每条推导写入前检查配额。checksum 对 asserted、shapes、inferred 三类排序后的 N-Triples 表示计算 SHA-256。application 先用 version revision 进入 `VALIDATING`，校验失败写 `INVALID`；校验成功后在同一数据库事务中发布版本并激活模型，再提交临时 shapes/inferred 图。revision 冲突会丢弃临时图，活动版本不变。

Schema 扫描通过 `SchemaMetadataSessionFactory` 打开租户限定的短生命周期会话。关系型和 ClickHouse 适配器只执行内置只读元数据 SQL，并再次经过 `SqlReadOnlyGuard`；MongoDB 只允许集合、结构、索引三类白名单操作。适配器不会把样例值写入领域对象、快照或 API 响应。

`SchemaIntrospectorPort` 接收纯领域值对象 `SchemaScanScope`，数据库差异留在 infrastructure 策略中。快照先规范化排序再计算 SHA-256 fingerprint；`tenantId + sourceId + fingerprint` 唯一约束和重复键回读共同保证并发幂等。

## 已知限制

- 当前 AST 门禁尚未包含表列白名单、函数白名单和成本估算，后续 QueryPlan 编译阶段补齐；
- 旧数据源中可能存在历史明文凭据，编辑保存时迁移，正式上线前需执行数据盘点；
- 当前已完成 Jena/TDB2 基础设施与语义控制面，SHACL 校验、受限推理和查询模板守卫将在后续切片实现；
- 当前控制面提供 application CRUD，HTTP Controller 与权限码契约由后续接口切片交付；
- Schema 扫描 HTTP 端点与语义目录细粒度权限码由后续接口切片统一补齐；
- 当前 Schema 扫描为同步调用，超大库的异步任务、超时和进度查询在容量测试后增加；
- `SemanticDatasetManager` 当前按单 JVM 单写入者部署，不能让多个 Pod 共享同一 TDB2 目录。
- 图查询当前为受限邻域读取，适配器会将 asserted 图 materialize 到 JVM 后筛选；生产大图需在 Query IR 切片改成服务端 SPARQL 分页和查询超时。
- `maxAssertedTriples`、`maxInferredTriples`、`maxGraphNodes` 是硬配额，超过配额会中止当前 TDB2 写事务。
- SHACL 已在发布链路启用，但 Jena 校验当前没有独立墙钟超时；上线前需结合容量测试增加可中断的执行器隔离。
- 推理采用明确的 RDFS/OWL 等价关系白名单，不是完整 OWL 2 DL，也不执行租户自定义规则。

## 变更历史

### 2026-09-15 - 语义查询 Phase 0

增加数据库数据源租户归属校验、`secretRef` 凭据引用和 SQL AST 只读门禁，为后续本体与 Query IR 开发建立安全边界。

### 2026-09-16 - 语义目录 Phase 1 基础设施

引入 Jena 6.2、TDB2 生命周期与事务管理、服务端图 IRI，以及不可解包的租户只读 Dataset View。集成测试覆盖异常回滚、关闭重开、跨租户隔离和写保护。

### 2026-09-16 - 语义模型控制面

增加模型与版本聚合、状态机、租户限定仓储、revision 乐观锁、application CRUD 和 Phase 1 四表迁移。模型有活动版本时禁止删除，已发布版本不能退回可编辑状态。

### 2026-09-16 - 多数据库 Schema 快照

增加 PostgreSQL、MySQL、ClickHouse 和 MongoDB 扫描策略、规范化 fingerprint、租户限定快照仓储及扫描历史应用接口。MongoDB 快照只保存类型分布，不保存采样值。

### 2026-09-17 - 本体图编辑与邻域读取

增加结构化本体图领域模型、租户限定的 Jena asserted 图替换、focus/keyword/depth 邻域查询、分页节点配额，以及 application 层版本状态和租户校验。补充真实 TDB2 临时目录下的跨租户、标签、深度和页内边测试。

### 2026-09-17 - SHACL 校验与版本发布

增加 operationId 临时图、SHACL 报告、白名单规则物化、推理配额、稳定 checksum，以及 version/model revision 驱动的发布与活动版本切换。失败路径清理临时图，且不会改变活动版本。
