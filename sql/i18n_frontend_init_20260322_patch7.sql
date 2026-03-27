-- 前端国际化补充文案（RouteRuleFormModal）
-- 目标表: sf_i18n_message

CREATE UNIQUE INDEX IF NOT EXISTS uk_sf_i18n_message_locale_msg_key
  ON sf_i18n_message (locale, msg_key);

INSERT INTO sf_i18n_message (locale, msg_key, msg_value, description, created_at, updated_at)
VALUES
  ('zh-CN', 'system.route.name', '规则名称', '路由规则名称字段', NOW(), NOW()),
  ('en-US', 'system.route.name', 'Rule Name', 'Route rule name field', NOW(), NOW()),
  ('zh-CN', 'system.route.nameRequired', '请输入规则名称', '路由规则名称必填提示', NOW(), NOW()),
  ('en-US', 'system.route.nameRequired', 'Please enter rule name', 'Route rule name required hint', NOW(), NOW()),
  ('zh-CN', 'system.route.namePlaceholder', '例如：高优先级 Claude 路由', '路由规则名称占位', NOW(), NOW()),
  ('en-US', 'system.route.namePlaceholder', 'e.g. High-priority Claude route', 'Route rule name placeholder', NOW(), NOW()),
  ('zh-CN', 'system.route.matchDimensionPlaceholder', '选择匹配维度', '匹配维度占位', NOW(), NOW()),
  ('en-US', 'system.route.matchDimensionPlaceholder', 'Select match dimension', 'Match dimension placeholder', NOW(), NOW()),
  ('zh-CN', 'system.route.matchDimRequired', '请选择匹配维度', '匹配维度必填提示', NOW(), NOW()),
  ('en-US', 'system.route.matchDimRequired', 'Please select match dimension', 'Match dimension required hint', NOW(), NOW()),
  ('zh-CN', 'system.route.matchDimTaskType', '任务类型', '匹配维度-任务类型', NOW(), NOW()),
  ('en-US', 'system.route.matchDimTaskType', 'Task Type', 'Match dimension task type', NOW(), NOW()),
  ('zh-CN', 'system.route.matchDimModelCap', '模型能力', '匹配维度-模型能力', NOW(), NOW()),
  ('en-US', 'system.route.matchDimModelCap', 'Model Capability', 'Match dimension model capability', NOW(), NOW()),
  ('zh-CN', 'system.route.matchDimCostBudget', '成本预算', '匹配维度-成本预算', NOW(), NOW()),
  ('en-US', 'system.route.matchDimCostBudget', 'Cost Budget', 'Match dimension cost budget', NOW(), NOW()),
  ('zh-CN', 'system.route.primaryModel', '主模型', '主模型字段', NOW(), NOW()),
  ('en-US', 'system.route.primaryModel', 'Primary Model', 'Primary model field', NOW(), NOW()),
  ('zh-CN', 'system.route.primaryModelRequired', '请选择主模型', '主模型必填提示', NOW(), NOW()),
  ('en-US', 'system.route.primaryModelRequired', 'Please select primary model', 'Primary model required hint', NOW(), NOW()),
  ('zh-CN', 'system.route.primaryModelPlaceholder', '选择主模型', '主模型占位', NOW(), NOW()),
  ('en-US', 'system.route.primaryModelPlaceholder', 'Select primary model', 'Primary model placeholder', NOW(), NOW()),
  ('zh-CN', 'system.route.secondaryModel', '备用模型 (可选)', '备用模型字段', NOW(), NOW()),
  ('en-US', 'system.route.secondaryModel', 'Fallback Model (optional)', 'Fallback model field', NOW(), NOW()),
  ('zh-CN', 'system.route.secondaryModelPlaceholder', '选择备用模型', '备用模型占位', NOW(), NOW()),
  ('en-US', 'system.route.secondaryModelPlaceholder', 'Select fallback model', 'Fallback model placeholder', NOW(), NOW()),
  ('zh-CN', 'system.route.tertiaryModel', '三级兜底 (可选)', '三级兜底字段', NOW(), NOW()),
  ('en-US', 'system.route.tertiaryModel', 'Tertiary Fallback (optional)', 'Tertiary fallback field', NOW(), NOW()),
  ('zh-CN', 'system.route.tertiaryModelPlaceholder', '选择三级兜底模型', '三级兜底占位', NOW(), NOW()),
  ('en-US', 'system.route.tertiaryModelPlaceholder', 'Select tertiary fallback model', 'Tertiary fallback placeholder', NOW(), NOW()),
  ('zh-CN', 'system.route.timeoutThreshold', '超时阈值 (ms)', '超时阈值字段', NOW(), NOW()),
  ('en-US', 'system.route.timeoutThreshold', 'Timeout Threshold (ms)', 'Timeout threshold field', NOW(), NOW()),
  ('zh-CN', 'system.route.timeoutPlaceholder', '例如：5000', '超时阈值占位', NOW(), NOW()),
  ('en-US', 'system.route.timeoutPlaceholder', 'e.g. 5000', 'Timeout threshold placeholder', NOW(), NOW()),
  ('zh-CN', 'system.route.errorRateThreshold', '错误率阈值 (%)', '错误率阈值字段', NOW(), NOW()),
  ('en-US', 'system.route.errorRateThreshold', 'Error Rate Threshold (%)', 'Error rate threshold field', NOW(), NOW()),
  ('zh-CN', 'system.route.errorRatePlaceholder', '例如：20', '错误率阈值占位', NOW(), NOW()),
  ('en-US', 'system.route.errorRatePlaceholder', 'e.g. 20', 'Error rate threshold placeholder', NOW(), NOW()),
  ('zh-CN', 'system.route.triggerConditionTitle', '主模型降级触发条件（满足任一条件时切换到备用模型）', '主模型降级触发条件标题', NOW(), NOW()),
  ('en-US', 'system.route.triggerConditionTitle', 'Primary Model Fallback Trigger (switch when any condition met)', 'Primary model fallback trigger title', NOW(), NOW()),
  ('zh-CN', 'system.route.descriptionPlaceholder', '路由规则说明', '路由规则描述占位', NOW(), NOW()),
  ('en-US', 'system.route.descriptionPlaceholder', 'Route rule description', 'Route rule description placeholder', NOW(), NOW()),
  ('zh-CN', 'system.route.editTitle', '编辑路由规则', '编辑路由规则标题', NOW(), NOW()),
  ('en-US', 'system.route.editTitle', 'Edit Route Rule', 'Edit route rule title', NOW(), NOW()),
  ('zh-CN', 'system.route.createTitle', '新建路由规则', '新建路由规则标题', NOW(), NOW()),
  ('en-US', 'system.route.createTitle', 'New Route Rule', 'Create route rule title', NOW(), NOW()),
  ('zh-CN', 'system.route.updateSuccess', '路由规则更新成功', '路由规则更新成功提示', NOW(), NOW()),
  ('en-US', 'system.route.updateSuccess', 'Route rule updated', 'Route rule update success', NOW(), NOW()),
  ('zh-CN', 'system.route.createSuccess', '路由规则创建成功', '路由规则创建成功提示', NOW(), NOW()),
  ('en-US', 'system.route.createSuccess', 'Route rule created', 'Route rule create success', NOW(), NOW())
ON CONFLICT (locale, msg_key)
DO UPDATE SET
  msg_value = EXCLUDED.msg_value,
  description = EXCLUDED.description,
  updated_at = NOW();
