-- 前端国际化补充文案（System Model / Workflow Designer）
-- 目标表: sf_i18n_message

CREATE UNIQUE INDEX IF NOT EXISTS uk_sf_i18n_message_locale_msg_key
  ON sf_i18n_message (locale, msg_key);

INSERT INTO sf_i18n_message (locale, msg_key, msg_value, description, created_at, updated_at)
VALUES
  ('zh-CN', 'system.model.modelId', 'Model ID', '系统模型表单文案', NOW(), NOW()),
  ('en-US', 'system.model.modelId', 'Model ID', 'System model form text', NOW(), NOW()),
  ('zh-CN', 'system.model.apiKey', 'API Key', '系统模型表单文案', NOW(), NOW()),
  ('en-US', 'system.model.apiKey', 'API Key', 'System model form text', NOW(), NOW()),
  ('zh-CN', 'system.model.pricePlaceholder', '0.000', '系统模型价格占位文案', NOW(), NOW()),
  ('en-US', 'system.model.pricePlaceholder', '0.000', 'System model price placeholder', NOW(), NOW()),

  ('zh-CN', 'workflow.designer.url', 'URL', '工作流设计器文案', NOW(), NOW()),
  ('en-US', 'workflow.designer.url', 'URL', 'Workflow designer text', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.urlPlaceholder', 'https://api.example.com/endpoint', '工作流设计器文案', NOW(), NOW()),
  ('en-US', 'workflow.designer.urlPlaceholder', 'https://api.example.com/endpoint', 'Workflow designer text', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.requestBodyPlaceholder', 'JSON', '工作流设计器文案', NOW(), NOW()),
  ('en-US', 'workflow.designer.requestBodyPlaceholder', 'JSON', 'Workflow designer text', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.channelSlack', 'Slack', '工作流设计器文案', NOW(), NOW()),
  ('en-US', 'workflow.designer.channelSlack', 'Slack', 'Workflow designer text', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.sql', 'SQL', '工作流设计器文案', NOW(), NOW()),
  ('en-US', 'workflow.designer.sql', 'SQL', 'Workflow designer text', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.sqlPlaceholder', 'SELECT * FROM ...', '工作流设计器文案', NOW(), NOW()),
  ('en-US', 'workflow.designer.sqlPlaceholder', 'SELECT * FROM ...', 'Workflow designer text', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.conditionExpressionPlaceholder', '${variable} == true', '工作流设计器文案', NOW(), NOW()),
  ('en-US', 'workflow.designer.conditionExpressionPlaceholder', '${variable} == true', 'Workflow designer text', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.loopConditionPlaceholder', '${variable} != done', '工作流设计器文案', NOW(), NOW()),
  ('en-US', 'workflow.designer.loopConditionPlaceholder', '${variable} != done', 'Workflow designer text', NOW(), NOW())
ON CONFLICT (locale, msg_key)
DO UPDATE SET
  msg_value = EXCLUDED.msg_value,
  description = EXCLUDED.description,
  updated_at = NOW();
