# SchemaPlexAI Service

业务服务模块，承载数据库数据源、Agent 执行、工具治理和语义查询的应用编排与基础设施适配。

## 语义数据查询

数据库语义能力位于 `com.schemaplexai.service.database`：

- 数据源读取显式绑定当前租户；
- 数据库连接凭据通过 `secretRef` 引用，运行时才物化到内存；
- 只读 SQL 通过 JSQLParser AST 门禁；
- 后续 Jena、SHACL 和 Query IR 按后端设计文档逐步接入。

详细方案见工作区的 [后端语义查询技术文档](../../document/tech/backend/07-语义数据查询与本体引擎技术文档.md)。
