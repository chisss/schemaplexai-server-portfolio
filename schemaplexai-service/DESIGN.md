# Service 设计决策

## 语义数据查询安全边界

数据库语义查询采用“结构化意图 → 服务端计划 → 方言查询”的链路。业务数据库不接受前端任意 SQL；当前兼容接口只允许单条只读语句，并由 `SqlReadOnlyGuard` 使用 JSQLParser AST 检查语句类型、`SELECT INTO` 和行锁。

数据库数据源和凭据始终按租户校验。连接配置只保存 `secretRef`，密文由 AES-GCM 凭据仓库存储，MCP 客户端创建前生成短生命周期内存副本。

Jena/TDB2 只存在于 `semantic.infrastructure.jena`。共享 Dataset 由 `SemanticDatasetManager` 持有，业务读取只能通过不可解包的只读门面访问服务端生成的图白名单。门面拒绝写事务，`close()` 不向下传递，避免调用方关闭共享存储。临时图包含 operationId，同一模型版本的并发校验互不覆盖。

## 已知限制

- 当前 AST 门禁尚未包含表列白名单、函数白名单和成本估算，后续 QueryPlan 编译阶段补齐；
- 旧数据源中可能存在历史明文凭据，编辑保存时迁移，正式上线前需执行数据盘点；
- 当前只完成 Jena/TDB2 基础设施，SHACL 校验、受限推理、语义模型控制面和查询模板守卫将在后续切片实现；
- `SemanticDatasetManager` 当前按单 JVM 单写入者部署，不能让多个 Pod 共享同一 TDB2 目录。

## 变更历史

### 2026-09-15 - 语义查询 Phase 0

增加数据库数据源租户归属校验、`secretRef` 凭据引用和 SQL AST 只读门禁，为后续本体与 Query IR 开发建立安全边界。

### 2026-09-16 - 语义目录 Phase 1 基础设施

引入 Jena 6.2、TDB2 生命周期与事务管理、服务端图 IRI，以及不可解包的租户只读 Dataset View。集成测试覆盖异常回滚、关闭重开、跨租户隔离和写保护。
