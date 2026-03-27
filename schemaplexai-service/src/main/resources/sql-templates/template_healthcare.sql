-- =====================================================
-- SchemaPlexAI 行业模板：医疗
-- 使用场景: 病历质控、临床文档、质量审查、规范合规
-- =====================================================

-- =====================
-- 1. 企业角色
-- =====================
INSERT INTO sf_role (id, tenant_id, name, code, description, is_system, status, created_at, updated_at) VALUES
    (gen_random_uuid(), :tenant_id, '医疗质量总监', 'QUALITY_DIRECTOR', '负责医疗质量管理体系建设和持续改进', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '病案质控员', 'MEDICAL_RECORD_QC', '负责病历质量审查、缺陷统计和规范化培训', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '临床文档员', 'CLINICAL_DOCUMENTER', '协助医生完成临床文档、手术记录和出院小结', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '合规审查员', 'COMPLIANCE_REVIEWER', '负责医保合规、药品合理使用和临床路径审核', false, 'active', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- =====================
-- 2. 上下文
-- =====================
INSERT INTO sf_context (id, tenant_id, name, context_level, description, status, created_at, updated_at, deleted) VALUES
    ('ctx-health-001-' || REPLACE(:tenant_id::text, '-', ''), :tenant_id,
     '病历质控标准库', 'global', '病历书写规范、质控评分标准和常见缺陷清单', 'active', NOW(), NOW(), 0),
    ('ctx-health-002-' || REPLACE(:tenant_id::text, '-', ''), :tenant_id,
     '临床规范指南库', 'global', '常见疾病诊疗规范、临床路径和用药指南', 'active', NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO sf_context_item (id, context_id, title, content, item_type, sort_order, status, created_at, updated_at) VALUES
    (gen_random_uuid(),
     'ctx-health-001-' || REPLACE(:tenant_id::text, '-', ''),
     '住院病历质控评分标准',
     E'# 住院病历质控评分标准（100分制）\n\n## 一、入院记录（25分）\n- 主诉准确简洁（5分）: 能概括就诊主要原因，含时间\n- 现病史完整（10分）: OPQRST要素齐全，诊疗经过详细\n- 既往史准确（5分）: 重要既往疾病、手术史、过敏史记录\n- 体格检查规范（5分）: 生命体征、专科检查记录\n\n## 二、病程记录（30分）\n- 首次病程记录（10分）: 24小时内完成，含初步诊断依据\n- 日常病程（10分）: 病情变化及时记录，用药调整有记录\n- 上级查房记录（5分）: 主治医师48小时内查房有记录\n- 危急值处理（5分）: 危急值通知后30分钟内记录处置\n\n## 三、知情同意（20分）\n- 入院知情告知（5分）: 患者/家属签字\n- 手术知情同意（10分）: 术前谈话充分，替代方案已告知\n- 特殊用药/检查（5分）: 自费项目知情同意\n\n## 四、出院记录（25分）\n- 出院诊断完整（10分）: 主诊断+并发症+合并症\n- 治疗经过简明（10分）: 主要治疗措施、药物使用\n- 出院医嘱规范（5分）: 随访要求、注意事项',
     'text', 1, 'active', NOW(), NOW()),
    (gen_random_uuid(),
     'ctx-health-002-' || REPLACE(:tenant_id::text, '-', ''),
     '2型糖尿病临床路径',
     E'# 2型糖尿病临床路径（住院版）\n\n## 适用对象\n符合2型糖尿病诊断标准，需住院行血糖调控的患者\n\n## 住院时间\n标准住院日: 7-14天\n\n## 诊断标准\n- 空腹血糖 ≥7.0 mmol/L\n- 餐后2小时血糖 ≥11.1 mmol/L\n- 糖化血红蛋白 ≥6.5%\n\n## 治疗方案路径\n\n### 第1-2天（评估期）\n- 完善血糖谱（空腹+三餐后+睡前）\n- 评估靶器官损害（眼底、肾功能、心电图、下肢血管）\n- 营养科会诊制定饮食方案\n\n### 第3-7天（调整期）\n- 根据血糖谱调整用药方案\n- 胰岛素起始剂量: 0.1-0.2 U/kg/天\n- 血糖目标: 空腹6-8 mmol/L，餐后<10 mmol/L\n\n### 第8-14天（稳定期）\n- 血糖达标后维持方案\n- 糖尿病教育（自我监测、低血糖处理）\n- 制定出院随访计划\n\n## 出院标准\n- 空腹血糖控制在7.0 mmol/L以下\n- 患者掌握胰岛素注射技术\n- 已建立门诊随访预约',
     'text', 1, 'active', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- =====================
-- 3. Agent
-- =====================
INSERT INTO sf_agent (id, tenant_id, name, agent_type, description, ai_model, ai_model_type, status, max_concurrency, trigger_type, skills, agent_tag, created_at, updated_at, deleted) VALUES
    (gen_random_uuid(), :tenant_id, '病历质控审查员', 'solo',
     '自动审查病历完整性和规范性，按质控评分标准输出缺陷清单和改进建议',
     'DEFAULT_MODEL', 'model', 'active', 5, 'manual',
     '["medical_record_review","quality_check","compliance_audit"]'::jsonb,
     'healthcare', NOW(), NOW(), 0),
    (gen_random_uuid(), :tenant_id, '临床文档辅助员', 'solo',
     '辅助医生完成结构化临床文档，包括出院小结、手术记录和会诊意见的规范化整理',
     'DEFAULT_MODEL', 'model', 'active', 3, 'manual',
     '["clinical_documentation","discharge_summary","structured_record"]'::jsonb,
     'healthcare', NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;

-- =====================
-- 4. 质量保障工作流
-- =====================
INSERT INTO sf_workflow (id, tenant_id, name, description, status, trigger_type, trigger_config, node_config, created_at, updated_at, deleted) VALUES
    (gen_random_uuid(), :tenant_id, '病历质量审查流程', '病历提交到质控完成的端到端审查流程',
     'active', 'manual', '{}'::jsonb,
     '{
       "nodes": [
         {"id": "start", "type": "start", "label": "病历提交", "position": {"x": 100, "y": 200}},
         {"id": "auto_check", "type": "agent", "label": "自动质控审查", "position": {"x": 350, "y": 200}, "config": {"agentRole": "病历质控审查员"}},
         {"id": "doc_assist", "type": "agent", "label": "文档规范化", "position": {"x": 600, "y": 200}, "config": {"agentRole": "临床文档辅助员"}},
         {"id": "end", "type": "end", "label": "质控归档", "position": {"x": 850, "y": 200}}
       ],
       "edges": [
         {"source": "start", "target": "auto_check"},
         {"source": "auto_check", "target": "doc_assist"},
         {"source": "doc_assist", "target": "end"}
       ]
     }'::jsonb,
     NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;
