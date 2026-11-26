# 业务本体服务

本模块管理企业数据源的 Schema 快照、业务本体草稿与发布版本。业务团队可在没有源库连接时建立术语、属性和关系。已发布定义可由获授权的 Agent 作为业务上下文读取。

- `application` 负责编排租户、目录模型、快照、草稿修订、校验和发布。
- `domain` 保存版本规则和确定性本体编译，依赖快照及存储端口。
- `infrastructure` 对接 PostgreSQL、Jena、历史 TDB2 图和现有数据库 MCP 连接。业务查询通过源端执行。

工作台先选择业务本体模型，通过现有版本接口提交 `{"structured":true}` 创建草稿。有源时追加已授权的 `sourceSnapshotId`。随后使用版本的 `configuration` 保存表单，用 `validate` 检查并持发布权限调用 `publish`。保存、校验和发布都提交界面当前的 `expectedRevision`。RDF 和 Shapes 导出使用版本的 `configuration/artifacts`。

查询就绪版本还需在草稿配置 `queryProfile`，并向校验和发布接口提交 `"purpose":"QUERY"`。当前支持 PostgreSQL 或 MySQL 单表或视图。MySQL 首次验收覆盖整数与精确小数聚合、普通维度和命名字符等值筛选。发布后先登记数据集范围和成员授权，再从统一查询工作台使用。

探索查询使用已获授权、绑定完整 PostgreSQL 或 MySQL Schema 快照的数据集。租户文本模型生成候选，服务端将已获准字段上的聚合、分组和等值条件编译为待确认的只读计划。聚合支持计数、求和、均值、最小值和最大值，其中计数统计指定字段的非空值。等值筛选按源列类型接受文字、数字、布尔值和 ISO 日期。混合数据集须另行授权可以探索的独立字段。

数据源管理员可用 `GET/PUT/DELETE /semantic/query/datasets/{datasetId}/reconciliation` 登记独立参考，选择已发布指标、固定 PostgreSQL 快照中的参考视图及其精确数值列。可选配置为绝对容差、最多三个已发布维度和事件时间列。系统在源端比较，结果提供缺失、多余和数值不同的组数以及有效日期范围。

数据库初始化、授权和已有库升级见 [SQL 指南](../../../../../../../../../document/sql/README.md)。架构与事务说明见 [本体服务设计](DESIGN.md)。
