# SchemaPlexAI Service

业务服务模块，承载数据库数据源、Agent 执行、工具治理和语义查询的应用编排与基础设施适配。

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

语义控制面位于 `com.schemaplexai.service.semantic`：

- `domain` 保存模型和版本聚合、仓储端口及纯状态转换；
- `application` 编排当前租户下的模型 CRUD 和草稿版本创建；
- `infrastructure.database` 适配 MyBatis，并通过 tenant + revision 条件执行读写；
- PostgreSQL 迁移见工作区 `sql/54_semantic_catalog.sql`。

Schema 快照链路复用租户数据库 MCP 数据源：

- PostgreSQL、MySQL 读取 `information_schema` 与约束、索引元数据；
- ClickHouse 读取 `system.tables`、`system.columns`、引擎与分区键；
- MongoDB 只调用集合、结构和索引白名单操作，受限采样仅保留字段类型分布；
- 规范化结构排序后计算 SHA-256 fingerprint，相同租户、数据源和 fingerprint 幂等复用；
- `SchemaScanApplicationService` 提供扫描和快照历史应用接口，HTTP 映射由后续接口切片接入。

语义模型 Controller、SHACL、发布编排和 Query IR 按后端设计文档继续接入。

详细方案见工作区的 [后端语义查询技术文档](../../document/tech/backend/07-语义数据查询与本体引擎技术文档.md)。
