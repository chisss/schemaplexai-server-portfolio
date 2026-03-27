-- =====================================================
-- SchemaPlexAI 行业模板：科技/研发
-- 使用场景: 代码审查、需求分析、技术文档生成、测试规划
-- 占位符: :tenant_id 在服务层替换为真实租户UUID
-- 模型占位符: DEFAULT_MODEL 需替换为真实模型名称
-- =====================================================

-- =====================
-- 1. 企业角色初始化
-- =====================
INSERT INTO sf_role (id, tenant_id, name, code, description, is_system, status, created_at, updated_at) VALUES
    (gen_random_uuid(), :tenant_id, '技术架构师', 'TECH_ARCHITECT', '负责系统架构设计和技术选型，把控技术方向', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '后端工程师', 'BACKEND_ENGINEER', '负责服务端开发、API设计和数据库优化', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '前端工程师', 'FRONTEND_ENGINEER', '负责用户界面开发和交互体验优化', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '测试工程师', 'QA_ENGINEER', '负责测试计划制定、用例编写和质量保障', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '产品经理', 'PRODUCT_MANAGER', '负责需求分析、产品规划和用户故事梳理', false, 'active', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- =====================
-- 2. 上下文初始化（Mock真实内容）
-- =====================
INSERT INTO sf_context (id, tenant_id, name, context_level, description, status, created_at, updated_at, deleted) VALUES
    ('ctx-tech-001-' || REPLACE(:tenant_id::text, '-', ''), :tenant_id,
     '技术规范与编码标准', 'global',
     '公司统一的技术规范、编码标准和最佳实践文档', 'active', NOW(), NOW(), 0),
    ('ctx-tech-002-' || REPLACE(:tenant_id::text, '-', ''), :tenant_id,
     '项目架构说明', 'global',
     '当前项目的整体架构说明、模块划分和技术栈介绍', 'active', NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;

-- 上下文详细内容（Mock真实可读文本）
INSERT INTO sf_context_item (id, context_id, title, content, item_type, sort_order, status, created_at, updated_at) VALUES
    (gen_random_uuid(),
     'ctx-tech-001-' || REPLACE(:tenant_id::text, '-', ''),
     'Java编码规范',
     E'# Java编码规范 v2.0\n\n## 命名约定\n- 类名: 大驼峰（UserService, OrderController）\n- 方法名: 小驼峰（getUserById, createOrder）\n- 常量: 全大写+下划线（MAX_RETRY_COUNT）\n- 包名: 全小写（com.company.module）\n\n## 代码结构\n- 控制层 Controller 只做参数校验和结果包装\n- 业务逻辑放在 Service 层\n- 数据访问放在 Mapper 层\n- 禁止在 Controller 中直接调用 Mapper\n\n## 注释规范\n- 类级别必须有 Javadoc\n- 复杂业务逻辑必须注释说明\n- 禁止无意义注释（如 // 获取用户 User user = getUser()）\n\n## 异常处理\n- 使用全局 @ControllerAdvice 统一处理\n- 业务异常继承 BusinessException\n- 禁止捕获异常后只打 e.printStackTrace()',
     'text', 1, 'active', NOW(), NOW()),
    (gen_random_uuid(),
     'ctx-tech-001-' || REPLACE(:tenant_id::text, '-', ''),
     'API设计规范',
     E'# RESTful API设计规范\n\n## URL设计\n- 资源用复数名词: /api/users, /api/orders\n- 层级关系: /api/users/{id}/orders\n- 操作用HTTP Method区分，不用动词\n\n## 请求/响应规范\n- 统一返回结构: {code, message, data}\n- 成功: code=200, message="success"\n- 分页: {records, total, current, size}\n- 时间格式: ISO 8601（2026-03-24T10:30:00)\n\n## 版本管理\n- 通过URL路径: /api/v1/users\n- 大版本变更才升级版本号\n- 旧版本保留至少6个月\n\n## 安全规范\n- 所有接口需要JWT认证（除登录注册）\n- 敏感数据（手机号、邮箱）脱敏返回\n- 密码字段绝不返回',
     'text', 2, 'active', NOW(), NOW()),
    (gen_random_uuid(),
     'ctx-tech-002-' || REPLACE(:tenant_id::text, '-', ''),
     '系统架构概览',
     E'# 系统架构说明\n\n## 整体架构\n采用微服务架构，分为以下核心服务：\n- 用户服务 (user-service): 认证、授权、用户管理\n- 业务服务 (business-service): 核心业务逻辑\n- 网关服务 (gateway-service): 路由、限流、鉴权\n- 消息服务 (message-service): 异步消息处理\n\n## 技术栈\n- 后端: Java 21 + Spring Boot 3.3 + MyBatis-Plus\n- 数据库: PostgreSQL 16 + Redis 7\n- 消息队列: RabbitMQ\n- 容器化: Docker + Kubernetes\n- CI/CD: GitLab CI\n\n## 部署架构\n- 生产环境: 3节点集群\n- 数据库: 主从复制\n- 缓存: Redis Cluster（3主3从）\n- 负载均衡: Nginx',
     'text', 1, 'active', NOW(), NOW()),
    (gen_random_uuid(),
     'ctx-tech-002-' || REPLACE(:tenant_id::text, '-', ''),
     '数据库设计规范',
     E'# 数据库设计规范\n\n## 表命名规范\n- 统一前缀: sf_\n- 下划线分词: sf_user_role\n- 关联表命名: sf_user_role（主表1_主表2）\n\n## 字段规范\n- 主键: id UUID PRIMARY KEY DEFAULT gen_random_uuid()\n- 租户字段: tenant_id UUID NOT NULL\n- 时间字段: created_at / updated_at TIMESTAMP NOT NULL DEFAULT NOW()\n- 逻辑删除: deleted INTEGER NOT NULL DEFAULT 0\n- 状态字段: status VARCHAR(20)（active/inactive）\n\n## 索引规范\n- 外键字段必须建索引\n- 高频查询字段建复合索引\n- 命名: idx_{表名}_{字段名}',
     'text', 2, 'active', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- =====================
-- 3. Agent初始化
-- =====================
INSERT INTO sf_agent (id, tenant_id, name, agent_type, description, ai_model, ai_model_type, status, max_concurrency, trigger_type, skills, work_type, agent_tag, created_at, updated_at, deleted) VALUES
    (gen_random_uuid(), :tenant_id, '代码审查专家', 'solo',
     '专注于代码质量审查，检测潜在的bug、安全漏洞、性能问题和不符合规范的代码',
     'DEFAULT_MODEL', 'model', 'active', 5, 'manual',
     '["code_review","security_check","performance_analysis"]'::jsonb,
     'code_review', 'dev', NOW(), NOW(), 0),
    (gen_random_uuid(), :tenant_id, '需求分析师', 'solo',
     '将模糊的业务需求转化为清晰的用户故事和验收标准，输出结构化需求文档',
     'DEFAULT_MODEL', 'model', 'active', 3, 'manual',
     '["requirement_analysis","user_story","acceptance_criteria"]'::jsonb,
     'requirement_analysis', 'dev', NOW(), NOW(), 0),
    (gen_random_uuid(), :tenant_id, '技术文档生成器', 'solo',
     '根据代码和接口定义自动生成API文档、README和技术说明文档',
     'DEFAULT_MODEL', 'model', 'active', 3, 'manual',
     '["doc_generation","api_doc","readme"]'::jsonb,
     'doc_generation', 'dev', NOW(), NOW(), 0),
    (gen_random_uuid(), :tenant_id, '测试规划师', 'solo',
     '根据功能描述和代码变更自动生成测试用例、测试计划和测试报告模板',
     'DEFAULT_MODEL', 'model', 'active', 3, 'manual',
     '["test_planning","test_case","test_report"]'::jsonb,
     'test_planning', 'dev', NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;

-- =====================
-- 4. 研发工作流初始化
-- =====================
INSERT INTO sf_workflow (id, tenant_id, name, description, status, trigger_type, trigger_config, node_config, created_at, updated_at, deleted) VALUES
    (gen_random_uuid(), :tenant_id, '标准研发工作流', '从需求分析到代码审查的完整研发流程',
     'active', 'manual', '{}'::jsonb,
     '{
       "nodes": [
         {"id": "start", "type": "start", "label": "开始", "position": {"x": 100, "y": 200}},
         {"id": "req_analysis", "type": "agent", "label": "需求分析", "position": {"x": 300, "y": 200}, "config": {"agentRole": "需求分析师"}},
         {"id": "code_review", "type": "agent", "label": "代码审查", "position": {"x": 500, "y": 200}, "config": {"agentRole": "代码审查专家"}},
         {"id": "test_plan", "type": "agent", "label": "测试规划", "position": {"x": 700, "y": 200}, "config": {"agentRole": "测试规划师"}},
         {"id": "doc_gen", "type": "agent", "label": "文档生成", "position": {"x": 900, "y": 200}, "config": {"agentRole": "技术文档生成器"}},
         {"id": "end", "type": "end", "label": "完成", "position": {"x": 1100, "y": 200}}
       ],
       "edges": [
         {"source": "start", "target": "req_analysis"},
         {"source": "req_analysis", "target": "code_review"},
         {"source": "code_review", "target": "test_plan"},
         {"source": "test_plan", "target": "doc_gen"},
         {"source": "doc_gen", "target": "end"}
       ]
     }'::jsonb,
     NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;
