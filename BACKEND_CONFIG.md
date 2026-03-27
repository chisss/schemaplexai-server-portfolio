# SchemaPlexAI 后端项目配置说明

> **版本**: V1.0.0 | **创建日期**: 2026-03-11
> **项目名称**: schemaplexai-server | **Spring Boot**: 3.3.6 | **Java**: 21

---

## 一、项目模块结构

```
schemaplexai-server/
├── pom.xml                        # 父POM，统一版本管理
├── schemaplexai-common/           # 公共模块：R<T>响应体、异常、枚举、常量、工具类
├── schemaplexai-model/            # 数据模型：Entity、DTO、VO、Converter
├── schemaplexai-dao/              # 数据访问：Mapper接口、类型处理器
├── schemaplexai-service/          # 业务逻辑：各模块Service、AI模型调用
├── schemaplexai-web/              # Web启动：Controller、配置、过滤器、WebSocket
└── schemaplexai-task/             # 定时任务：成本统计、Agent健康检查、审批超时
```

**模块依赖关系**: `web/task → service → dao → model → common`

---

## 二、中间件依赖与配置

### 2.1 PostgreSQL 16（主数据库）

| 配置项 | 开发环境 | 生产环境 |
|-------|---------|---------|
| 地址 | `localhost:5432` | `${DB_HOST}:${DB_PORT}` |
| 数据库名 | `schemaplexai` | `${DB_NAME}` |
| 用户名 | `schemaplexai` | `${DB_USERNAME}` |
| 密码 | `schemaplexai123` | `${DB_PASSWORD}` (环境变量) |
| 连接池最大 | 20 | 50 |

**配置文件位置**: `application-dev.yml` / `application-prod.yml`

**初始化**: 统一使用根目录 `sql/` 的基线脚本（`01~15`），推荐执行 `sql/init_database.sh`：
```bash
cd ../sql
./init_database.sh
```

### 2.2 Redis 7（缓存 + 会话）

| 配置项 | 开发环境 | 生产环境 |
|-------|---------|---------|
| 地址 | `localhost:6379` | `${REDIS_HOST}:${REDIS_PORT}` |
| 密码 | `redis123` | `${REDIS_PASSWORD}` |
| 连接池最大活跃 | 16 | 32 |

**Key命名规范**:
```
sf:{module}:{type}:{id}

示例:
sf:auth:token:refresh:{userId}     # RefreshToken
sf:auth:user:{userId}              # 用户信息缓存 (TTL: 30min)
sf:agent:config:{agentId}          # Agent配置 (TTL: 1h)
sf:i18n:messages:{locale}          # 翻译文案 (TTL: 1h)
sf:lock:spec:submit:{specId}       # 分布式锁
```

### 2.3 RabbitMQ 3.13（消息队列）

| 配置项 | 开发环境 | 生产环境 |
|-------|---------|---------|
| 地址 | `localhost:5672` | `${RABBITMQ_HOST}:${RABBITMQ_PORT}` |
| 用户名 | `schemaplexai` | `${RABBITMQ_USERNAME}` |
| 密码 | `schemaplexai123` | `${RABBITMQ_PASSWORD}` |
| VHost | `/schemaplexai` | `/schemaplexai` |
| 管理界面 | `http://localhost:15672` | — |

**Exchange/Queue 设计**:

| Exchange | Type | Queue | Routing Key | 用途 |
|----------|------|-------|-------------|------|
| `sf.agent` | topic | `sf.agent.execute` | `agent.execute.*` | Agent任务执行 |
| `sf.agent` | topic | `sf.agent.result` | `agent.result.*` | Agent执行结果 |
| `sf.workflow` | topic | `sf.workflow.trigger` | `workflow.trigger.*` | 工作流触发 |
| `sf.notification` | fanout | `sf.notification.email` | — | 邮件通知 |
| `sf.notification` | fanout | `sf.notification.im` | — | IM通知 |
| `sf.cost` | topic | `sf.cost.record` | `cost.record.*` | 成本记录 |
| `sf.quality` | topic | `sf.quality.check` | `quality.check.*` | 质量检测 |

### 2.4 MinIO（对象存储）

| 配置项 | 开发环境 | 生产环境 |
|-------|---------|---------|
| Endpoint | `http://localhost:9000` | `${MINIO_ENDPOINT}` |
| Access Key | `schemaplexai` | `${MINIO_ACCESS_KEY}` |
| Secret Key | `schemaplexai123` | `${MINIO_SECRET_KEY}` |
| Bucket | `schemaplexai` | `schemaplexai` |
| Console | `http://localhost:9001` | — |

**初始化Bucket**:
```bash
mc alias set sf-minio http://localhost:9000 schemaplexai schemaplexai123
mc mb sf-minio/schemaplexai
mc mb sf-minio/schemaplexai-logs
mc mb sf-minio/schemaplexai-artifacts
```

### 2.5 Milvus 2.4.x（向量数据库）

| 配置项 | 开发环境 | 生产环境 |
|-------|---------|---------|
| gRPC地址 | `localhost:19530` | `${MILVUS_HOST}:${MILVUS_PORT}` |

**用途**: 语义检索、知识图谱向量化存储

**注意**: Milvus Standalone 至少需要 4GB 内存，Docker Desktop 建议分配 8GB+

### 2.6 ClickHouse 24.x（分析数据库）

| 配置项 | 开发环境 | 生产环境 |
|-------|---------|---------|
| HTTP地址 | `localhost:8123` | `${CLICKHOUSE_HOST}:${CLICKHOUSE_PORT}` |
| 用户名 | `default` | `${CLICKHOUSE_USERNAME}` |
| 密码 | `clickhouse123` | `${CLICKHOUSE_PASSWORD}` |

**用途**: Token消耗统计、报表分析（OLAP场景）

**端口注意**: ClickHouse Native端口(9000)与MinIO冲突，docker-compose中映射到 `9009`

---

## 三、认证授权配置

### 3.1 JWT Token

| 配置项 | 值 | 说明 |
|-------|---|------|
| 签名算法 | HMAC256 | — |
| Secret | `${JWT_SECRET}` | 至少32字符，通过环境变量注入 |
| AccessToken有效期 | 86400秒(24h) | — |
| RefreshToken有效期 | 604800秒(7天) | 存储在Redis中 |

**Token Payload结构**:
```json
{
  "sub": "user-uuid",       // 用户ID
  "tid": "tenant-uuid",     // 租户ID
  "roles": ["ADMIN"],       // 角色列表
  "iat": 1709913600,
  "exp": 1710000000
}
```

### 3.2 安全白名单（不需要Token的路径）

```
/auth/login, /auth/register, /auth/refresh
/i18n/locales, /i18n/messages
/doc.html, /swagger-resources/**, /v3/api-docs/**, /webjars/**
/ws/**
```

---

## 四、多租户配置

### 4.1 隔离策略

采用 **共享数据库 + tenant_id字段** 方案，MyBatis-Plus TenantLineInnerInterceptor 自动追加租户条件。

### 4.2 租户识别

1. JWT Token 中的 `tid` 字段（优先）
2. 请求Header `X-Tenant-Id`（兜底）

### 4.3 不做租户隔离的表

```
sf_tenant        — 租户表本身
sf_audit_log     — 审计日志（需跨租户查询）
sf_i18n_locale   — 语言配置（全局共享）
sf_i18n_message  — 翻译文案（全局共享）
```

---

## 五、国际化(i18n)配置

### 5.1 设计模式

**后端集中管理 + 前端动态加载**

- 翻译文案存储在 `sf_i18n_message` 表，支持后台热更新
- 前端启动时通过 `/api/i18n/messages?locale=zh-CN` 拉取全量文案
- Redis缓存（TTL: 1h），后台修改后主动失效

### 5.2 API接口

| 方法 | 路径 | 认证 | 说明 |
|------|------|------|------|
| GET | `/api/i18n/locales` | 否 | 获取支持的语言列表 |
| GET | `/api/i18n/messages?locale=zh-CN` | 否 | 获取翻译文案（嵌套JSON） |

### 5.3 后台管理接口（需认证）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/system/i18n/messages` | 分页查询文案 |
| POST | `/api/system/i18n/messages` | 新增文案 |
| PUT | `/api/system/i18n/messages/{id}` | 修改文案 |
| DELETE | `/api/system/i18n/messages/{id}` | 删除文案 |
| POST | `/api/system/i18n/messages/import` | 批量导入 |
| GET | `/api/system/i18n/messages/export` | 导出 |
| POST | `/api/system/i18n/cache/evict` | 手动刷新缓存 |

---

## 六、工作流引擎配置（Flowable 7.x）

| 配置项 | 开发环境 | 生产环境 |
|-------|---------|---------|
| 自动建表 | `true` | `false` |
| 异步执行器 | `true` | `true` |

**说明**: Flowable会在PostgreSQL中自动创建 `ACT_*` 系列表。生产环境通过Flyway/Liquibase管理DDL。

---

## 七、环境变量清单

开发环境启动前需配置以下环境变量（或使用默认值）：

| 变量名 | 必须 | 默认值 | 说明 |
|-------|------|--------|------|
| `DB_PASSWORD` | 否 | `schemaplexai123` | PostgreSQL密码 |
| `REDIS_PASSWORD` | 否 | `redis123` | Redis密码 |
| `RABBITMQ_PASSWORD` | 否 | `schemaplexai123` | RabbitMQ密码 |
| `MINIO_ACCESS_KEY` | 否 | `schemaplexai` | MinIO Access Key |
| `MINIO_SECRET_KEY` | 否 | `schemaplexai123` | MinIO Secret Key |
| `CLICKHOUSE_PASSWORD` | 否 | `clickhouse123` | ClickHouse密码 |
| `JWT_SECRET` | 否 | (内置默认) | JWT签名密钥 |
| `CLAUDE_API_KEY` | 否 | — | Claude API密钥 |
| `OPENAI_API_KEY` | 否 | — | OpenAI API密钥 |
| `GEMINI_API_KEY` | 否 | — | Gemini API密钥 |

**生产环境额外变量**:
`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `REDIS_HOST`, `REDIS_PORT`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `MINIO_ENDPOINT`, `MILVUS_HOST`, `MILVUS_PORT`, `CLICKHOUSE_HOST`, `CLICKHOUSE_PORT`, `CLICKHOUSE_DB`, `CLICKHOUSE_USERNAME`

---

## 八、快速启动

### 8.1 启动中间件

```bash
cd schemaplexai-server/..   # 项目根目录（docker-compose.yml所在位置）
docker compose up -d
```

### 8.2 编译后端

```bash
cd schemaplexai-server
mvn clean package -DskipTests
```

### 8.3 启动后端

```bash
# 方式一：直接运行jar
java -jar schemaplexai-web/target/schemaplexai-web-1.0.0-SNAPSHOT.jar

# 方式二：Maven开发模式（支持热重载）
cd schemaplexai-web
mvn spring-boot:run
```

### 8.4 验证

```bash
# 健康检查
curl http://localhost:8080/api/actuator/health

# API文档
open http://localhost:8080/api/doc.html
```

---

## 九、API路径总览

| 模块 | 路径前缀 | 说明 |
|------|---------|------|
| 认证 | `/api/auth` | 登录/注册/刷新Token |
| Agent管理 | `/api/agents` | Agent CRUD + 执行 |
| Spec管理 | `/api/specs` | Spec CRUD + 审批 |
| 上下文管理 | `/api/contexts` | 上下文 CRUD |
| 工作流 | `/api/workflows` | 工作流模板/实例 |
| 系统配置 | `/api/system` | 用户/角色/菜单/模型 |
| 质量保障 | `/api/quality` | 偏离检测 |
| 监控报表 | `/api/monitor` | 大屏/报表 |
| 成本管控 | `/api/costs` | 成本追踪/预算 |
| 集成扩展 | `/api/integrations` | Git/CI/IM集成 |
| 评审 | `/api/reviews` | 评审会话/意见 |
| 国际化 | `/api/i18n` | 语言/翻译文案 |

---

## 十、定时任务

| 任务 | 执行频率 | 说明 |
|------|---------|------|
| `CostStatisticsTask` | 每小时 | 成本数据聚合到ClickHouse |
| `AgentHealthCheckTask` | 每5分钟 | Agent实例健康状态检查 |
| `ApprovalTimeoutTask` | 每30分钟 | 超时审批请求处理 |
