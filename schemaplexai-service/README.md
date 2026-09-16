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

SHACL、语义模型控制面和 Query IR 按后端设计文档继续接入。

详细方案见工作区的 [后端语义查询技术文档](../../document/tech/backend/07-语义数据查询与本体引擎技术文档.md)。
