-- =====================================================
-- SchemaPlexAI 行业模板：制造业
-- 使用场景: 生产排程、质检报告、设备维护、工艺优化
-- =====================================================

-- =====================
-- 1. 企业角色
-- =====================
INSERT INTO sf_role (id, tenant_id, name, code, description, is_system, status, created_at, updated_at) VALUES
    (gen_random_uuid(), :tenant_id, '生产总监', 'PRODUCTION_DIRECTOR', '负责生产计划制定、产能管理和交付达成', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '质检工程师', 'QC_ENGINEER', '负责产品质量检验、不良品分析和质量报告输出', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '设备工程师', 'EQUIPMENT_ENGINEER', '负责设备维护保养、故障排查和预防性维护计划', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '工艺工程师', 'PROCESS_ENGINEER', '负责工艺参数优化、SOP制定和工艺改进', false, 'active', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- =====================
-- 2. 上下文
-- =====================
INSERT INTO sf_context (id, tenant_id, name, context_level, description, status, created_at, updated_at, deleted) VALUES
    ('ctx-mfg-001-' || REPLACE(:tenant_id::text, '-', ''), :tenant_id,
     '质检标准与规范库', 'global', '产品质量检验标准、不良品判定规则和报告模板', 'active', NOW(), NOW(), 0),
    ('ctx-mfg-002-' || REPLACE(:tenant_id::text, '-', ''), :tenant_id,
     '设备维护知识库', 'global', '设备维保手册、常见故障代码和维修指南', 'active', NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO sf_context_item (id, context_id, title, content, item_type, sort_order, status, created_at, updated_at) VALUES
    (gen_random_uuid(),
     'ctx-mfg-001-' || REPLACE(:tenant_id::text, '-', ''),
     '注塑件外观质量检验标准',
     E'# 注塑件外观质量检验标准 QS-001\n\n## 检验分级\n- A级面: 正面可视区域，最严格标准\n- B级面: 侧面/底面，次要标准\n- C级面: 内腔不可视区域，宽松标准\n\n## A级面允收标准\n| 缺陷类型 | 允收条件 |\n|---------|--------|\n| 缩水凹痕 | 不可见（手感无明显凹陷） |\n| 熔接痕 | 长度≤5mm，不可见 |\n| 流痕 | 轻微，距离≥50mm |\n| 气泡 | 不允许 |\n| 划伤 | 长度≤2mm，宽度≤0.1mm，数量≤2处 |\n| 颜色偏差 | ΔE≤1.5（对色灯箱检验） |\n\n## 检验工具\n- 放大镜: 10倍，检验划痕和毛刺\n- 卡尺: 精度0.01mm，检验尺寸公差\n- 色差仪: 检验颜色一致性\n- 模板: 与标准样品比对\n\n## 判定流程\n1. 来料全检（批量<200pcs）或抽检（批量≥200pcs，AQL 1.0）\n2. 发现超标缺陷 → 标识隔离 → 填写《不合格品处置单》\n3. 返工/报废决定由质检主管审批',
     'text', 1, 'active', NOW(), NOW()),
    (gen_random_uuid(),
     'ctx-mfg-002-' || REPLACE(:tenant_id::text, '-', ''),
     '注塑机常见故障处理指南',
     E'# 注塑机常见故障处理指南\n\n## 故障代码速查\n\n### E001 - 料筒温度异常\n**现象**: 实际温度偏离设定值±10℃以上\n**可能原因**:\n- 热电偶老化/接触不良 → 检查热电偶连接，更换热电偶\n- 加热圈烧断 → 用万用表测量电阻，阻值无穷大则更换\n- PID参数偏差 → 重新自整定PID参数\n\n### E002 - 锁模力不足\n**现象**: 产品有飞边，合模无法达到设定压力\n**可能原因**:\n- 锁模油缸漏油 → 检查密封圈，更换液压密封\n- 液压油污染 → 更换液压油，清洗过滤器\n- 曲肘磨损 → 检查曲肘间隙，必要时更换\n\n### E003 - 射出压力不稳\n**现象**: 产品重量波动>±3%\n**可能原因**:\n- 料筒磨损（射出间隙过大）→ 检测料筒/螺杆间隙，>0.15mm需更换\n- 止逆环磨损 → 更换止逆环\n- 液压压力波动 → 检查液压泵，调整溢流阀\n\n## 预防性维护计划\n| 频率 | 维护项目 |\n|------|--------|\n| 每日 | 检查润滑油位、液压油位、冷却水流量 |\n| 每周 | 清洁料筒外部、检查电气接线、紧固螺栓 |\n| 每月 | 更换润滑脂、检查皮带张力、校验传感器 |\n| 每季 | 更换液压油过滤器、全面精度检测 |',
     'text', 1, 'active', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- =====================
-- 3. Agent
-- =====================
INSERT INTO sf_agent (id, tenant_id, name, agent_type, description, ai_model, ai_model_type, status, max_concurrency, trigger_type, skills, agent_tag, created_at, updated_at, deleted) VALUES
    (gen_random_uuid(), :tenant_id, '质检报告分析师', 'solo',
     '分析质检数据，识别不良趋势，生成质量分析报告和改进建议',
     'DEFAULT_MODEL', 'model', 'active', 3, 'manual',
     '["quality_analysis","defect_trend","improvement_suggestion"]'::jsonb,
     'manufacturing', NOW(), NOW(), 0),
    (gen_random_uuid(), :tenant_id, '设备故障诊断助手', 'solo',
     '根据设备故障代码和现象描述，结合维护知识库，提供诊断分析和维修方案',
     'DEFAULT_MODEL', 'model', 'active', 5, 'manual',
     '["equipment_diagnosis","fault_analysis","maintenance_plan"]'::jsonb,
     'manufacturing', NOW(), NOW(), 0),
    (gen_random_uuid(), :tenant_id, '工艺参数优化师', 'solo',
     '分析生产数据，识别工艺参数与产品质量的关联，提出参数优化建议',
     'DEFAULT_MODEL', 'model', 'active', 2, 'manual',
     '["process_optimization","parameter_analysis","sop_writing"]'::jsonb,
     'manufacturing', NOW(), NOW(), 0),
    (gen_random_uuid(), :tenant_id, '工作流编排助手', 'solo',
     '结合生产质检、故障诊断和工艺优化场景，自动生成可直接落地的制造流程编排草图',
     'DEFAULT_MODEL', 'model', 'active', 2, 'manual',
     '["workflow_arrange","flow_planning","manufacturing_process_design"]'::jsonb,
     'workflow_arrange', NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;

-- =====================
-- 4. 生产运营工作流
-- =====================
INSERT INTO sf_workflow (id, tenant_id, name, description, status, trigger_type, trigger_config, node_config, created_at, updated_at, deleted) VALUES
    (gen_random_uuid(), :tenant_id, '生产质量闭环流程', '从质检发现到工艺改进的完整闭环管理流程',
     'active', 'manual', '{}'::jsonb,
     '{
       "nodes": [
         {"id": "start", "type": "start", "label": "质检发现", "position": {"x": 100, "y": 200}},
         {"id": "analysis", "type": "agent", "label": "质量数据分析", "position": {"x": 350, "y": 200}, "config": {"agentRole": "质检报告分析师"}},
         {"id": "diagnosis", "type": "agent", "label": "设备/工艺诊断", "position": {"x": 600, "y": 200}, "config": {"agentRole": "设备故障诊断助手"}},
         {"id": "optimize", "type": "agent", "label": "参数优化方案", "position": {"x": 850, "y": 200}, "config": {"agentRole": "工艺参数优化师"}},
         {"id": "end", "type": "end", "label": "方案实施", "position": {"x": 1100, "y": 200}}
       ],
       "edges": [
         {"source": "start", "target": "analysis"},
         {"source": "analysis", "target": "diagnosis"},
         {"source": "diagnosis", "target": "optimize"},
         {"source": "optimize", "target": "end"}
       ]
     }'::jsonb,
     NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;
