-- =====================================================
-- SchemaPlexAI 行业模板：金融
-- 使用场景: 合规审计、风险评估、报告生成、监管报送
-- 占位符: :tenant_id 在服务层替换为真实租户UUID
-- =====================================================

-- =====================
-- 1. 企业角色
-- =====================
INSERT INTO sf_role (id, tenant_id, name, code, description, is_system, status, created_at, updated_at) VALUES
    (gen_random_uuid(), :tenant_id, '合规总监', 'COMPLIANCE_DIRECTOR', '负责整体合规策略、监管关系和合规风险管理', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '风险分析师', 'RISK_ANALYST', '负责信用风险、市场风险、操作风险的量化分析', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '审计专员', 'AUDIT_SPECIALIST', '负责内部审计执行、审计底稿整理和发现跟踪', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '报告撰写员', 'REPORT_WRITER', '负责监管报告、董事会报告和年报的起草与润色', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '数据分析员', 'DATA_ANALYST', '负责财务数据挖掘、报表分析和异常监控', false, 'active', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- =====================
-- 2. 上下文（合规知识库）
-- =====================
INSERT INTO sf_context (id, tenant_id, name, context_level, description, status, created_at, updated_at, deleted) VALUES
    ('ctx-fin-001-' || REPLACE(:tenant_id::text, '-', ''), :tenant_id,
     '合规法规知识库', 'global', '银行业和证券业核心监管法规和合规要点', 'active', NOW(), NOW(), 0),
    ('ctx-fin-002-' || REPLACE(:tenant_id::text, '-', ''), :tenant_id,
     '风控评估模板库', 'global', '标准化风险评估模板、评分卡和评估框架', 'active', NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO sf_context_item (id, context_id, title, content, item_type, sort_order, status, created_at, updated_at) VALUES
    (gen_random_uuid(),
     'ctx-fin-001-' || REPLACE(:tenant_id::text, '-', ''),
     '反洗钱合规要点（AML）',
     E'# 反洗钱合规核心要点\n\n## KYC（了解你的客户）\n- 客户身份识别: 自然人需提供有效身份证件，法人需提供营业执照、法人身份证\n- 受益所有人识别: 持股25%以上的自然人需进行实名核查\n- 持续尽职调查: 高风险客户每年至少复核一次\n\n## 可疑交易报告（SAR）\n- 单笔现金交易超5万人民币必须申报\n- 发现可疑交易需在3个工作日内向金融情报机构报告\n- 禁止"打草惊蛇"，不得通知被调查方\n\n## 客户风险分级\n- 低风险: 本地居民、稳定职业、交易规律\n- 中风险: 跨境交易频繁、新开户客户\n- 高风险: 政治敏感人物（PEP）、高风险国家/地区客户\n\n## 记录保存\n- 客户身份信息保存期: 最长业务关系终止后5年\n- 交易记录保存期: 交易完成后5年',
     'text', 1, 'active', NOW(), NOW()),
    (gen_random_uuid(),
     'ctx-fin-001-' || REPLACE(:tenant_id::text, '-', ''),
     '数据隐私合规（GDPR/个保法）',
     E'# 数据隐私合规要点\n\n## 个人金融数据分类\n- 一般个人信息: 姓名、联系方式、职业\n- 敏感个人信息: 银行账号、征信报告、资产信息\n- 生物特征信息: 人脸、指纹（需单独同意）\n\n## 数据处理原则\n- 合法性: 必须有明确法律依据（合同履行/同意/法律义务）\n- 最小必要: 只收集业务必需的数据\n- 目的限制: 不得超出收集时声明的用途\n\n## 数据主体权利\n- 访问权: 客户可申请查看自己的数据\n- 更正权: 错误数据需在15个工作日内更正\n- 删除权: 账户注销后按规定期限删除数据\n- 可携带权: 应客户要求提供结构化数据副本',
     'text', 2, 'active', NOW(), NOW()),
    (gen_random_uuid(),
     'ctx-fin-002-' || REPLACE(:tenant_id::text, '-', ''),
     '信用风险评估模板',
     E'# 信用风险评估标准模板\n\n## 企业客户评估维度\n\n### 财务指标（权重40%）\n- 资产负债率: <60%（良好），60-80%（关注），>80%（高风险）\n- 流动比率: >2（良好），1-2（一般），<1（预警）\n- 净利润率: 正向增长趋势优先\n\n### 经营状况（权重30%）\n- 成立年限: >5年加分\n- 行业地位: 行业Top10优先\n- 管理层稳定性: 核心管理层任期>3年\n\n### 征信记录（权重20%）\n- 逾期记录: 24个月内无逾期\n- 诉讼记录: 无重大未决诉讼\n- 对外担保: 担保比例<净资产50%\n\n### 外部环境（权重10%）\n- 宏观政策支持行业\n- 区域经济发展良好',
     'text', 1, 'active', NOW(), NOW()),
    (gen_random_uuid(),
     'ctx-fin-002-' || REPLACE(:tenant_id::text, '-', ''),
     '内部审计工作底稿模板',
     E'# 内部审计工作底稿标准模板\n\n## 底稿基本要素\n| 要素 | 说明 |\n|------|------|\n| 审计项目 | 填写具体审计事项名称 |\n| 审计期间 | YYYY年MM月DD日 至 YYYY年MM月DD日 |\n| 审计人员 | 主审/复核人签字 |\n| 编制日期 | 实际编制日期 |\n\n## 审计程序执行记录\n1. 目的: [描述本程序的审计目标]\n2. 程序: [具体操作步骤]\n3. 抽样: [抽样方法和样本量]\n4. 执行结果: [发现的事实和数据]\n5. 结论: [是否符合预期/存在的问题]\n\n## 审计发现记录\n- 发现事项: [问题描述]\n- 风险等级: 高/中/低\n- 相关条款: [违反的制度或法规]\n- 管理层意见: [被审计方回应]\n- 整改建议: [具体改进措施]',
     'text', 2, 'active', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- =====================
-- 3. Agent
-- =====================
INSERT INTO sf_agent (id, tenant_id, name, agent_type, description, ai_model, ai_model_type, status, max_concurrency, trigger_type, skills, agent_tag, created_at, updated_at, deleted) VALUES
    (gen_random_uuid(), :tenant_id, '合规审查专家', 'solo',
     '基于合规法规知识库，对业务流程、合同条款和操作记录进行合规性检查，输出合规风险报告',
     'DEFAULT_MODEL', 'model', 'active', 3, 'manual',
     '["compliance_check","regulation_analysis","risk_report"]'::jsonb,
     'finance', NOW(), NOW(), 0),
    (gen_random_uuid(), :tenant_id, '风险评估分析师', 'solo',
     '对客户信用风险、市场风险进行量化分析，使用标准化评估模型输出风险评级和建议',
     'DEFAULT_MODEL', 'model', 'active', 3, 'manual',
     '["risk_assessment","credit_scoring","market_risk"]'::jsonb,
     'finance', NOW(), NOW(), 0),
    (gen_random_uuid(), :tenant_id, '监管报告撰写员', 'solo',
     '根据原始数据和审计底稿，自动生成符合监管要求格式的报告，包括季报、年报和专项报告',
     'DEFAULT_MODEL', 'model', 'active', 2, 'manual',
     '["report_writing","regulatory_report","data_summary"]'::jsonb,
     'finance', NOW(), NOW(), 0),
    (gen_random_uuid(), :tenant_id, '工作流编排助手', 'solo',
     '结合金融合规、风控和报告场景，自动规划多节点工作流草图，输出可直接编辑的流程结构',
     'DEFAULT_MODEL', 'model', 'active', 2, 'manual',
     '["workflow_arrange","flow_planning","risk_process_design"]'::jsonb,
     'workflow_arrange', NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;

-- =====================
-- 4. 合规审计工作流
-- =====================
INSERT INTO sf_workflow (id, tenant_id, name, description, status, trigger_type, trigger_config, node_config, created_at, updated_at, deleted) VALUES
    (gen_random_uuid(), :tenant_id, '合规审计标准流程', '端到端合规审查：从数据收集到报告输出的完整流程',
     'active', 'manual', '{}'::jsonb,
     '{
       "nodes": [
         {"id": "start", "type": "start", "label": "启动审计", "position": {"x": 100, "y": 200}},
         {"id": "compliance_check", "type": "agent", "label": "合规性检查", "position": {"x": 350, "y": 200}, "config": {"agentRole": "合规审查专家"}},
         {"id": "risk_assess", "type": "agent", "label": "风险量化评估", "position": {"x": 600, "y": 200}, "config": {"agentRole": "风险评估分析师"}},
         {"id": "report_gen", "type": "agent", "label": "生成审计报告", "position": {"x": 850, "y": 200}, "config": {"agentRole": "监管报告撰写员"}},
         {"id": "end", "type": "end", "label": "报告归档", "position": {"x": 1100, "y": 200}}
       ],
       "edges": [
         {"source": "start", "target": "compliance_check"},
         {"source": "compliance_check", "target": "risk_assess"},
         {"source": "risk_assess", "target": "report_gen"},
         {"source": "report_gen", "target": "end"}
       ]
     }'::jsonb,
     NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;
