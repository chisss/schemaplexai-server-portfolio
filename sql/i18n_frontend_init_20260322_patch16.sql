-- 前端国际化补充文案（Workflow 技术占位 / Agent & Skill 来源类型）
-- 目标表: sf_i18n_message

CREATE UNIQUE INDEX IF NOT EXISTS uk_sf_i18n_message_locale_msg_key
  ON sf_i18n_message (locale, msg_key);

INSERT INTO sf_i18n_message (locale, msg_key, msg_value, description, created_at, updated_at)
VALUES
  ('zh-CN', 'workflow.designer.cronPlaceholder', '0 2 * * *', '工作流设计器占位文案', NOW(), NOW()),
  ('en-US', 'workflow.designer.cronPlaceholder', '0 2 * * *', 'Workflow designer placeholder', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.eventTypeGitPush', 'Git Push', '工作流设计器事件类型文案', NOW(), NOW()),
  ('en-US', 'workflow.designer.eventTypeGitPush', 'Git Push', 'Workflow designer event type text', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.eventTypeWebhook', 'Webhook', '工作流设计器事件类型文案', NOW(), NOW()),
  ('en-US', 'workflow.designer.eventTypeWebhook', 'Webhook', 'Workflow designer event type text', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.scriptLanguageShell', 'Shell', '工作流设计器脚本语言文案', NOW(), NOW()),
  ('en-US', 'workflow.designer.scriptLanguageShell', 'Shell', 'Workflow designer script language text', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.scriptLanguagePython', 'Python', '工作流设计器脚本语言文案', NOW(), NOW()),
  ('en-US', 'workflow.designer.scriptLanguagePython', 'Python', 'Workflow designer script language text', NOW(), NOW()),

  ('zh-CN', 'agent.sourceSkill', 'Skill', 'Agent 工具来源类型文案', NOW(), NOW()),
  ('en-US', 'agent.sourceSkill', 'Skill', 'Agent source type text', NOW(), NOW()),
  ('zh-CN', 'agent.sourceMcp', 'MCP', 'Agent 工具来源类型文案', NOW(), NOW()),
  ('en-US', 'agent.sourceMcp', 'MCP', 'Agent source type text', NOW(), NOW()),
  ('zh-CN', 'agent.sourceRefPlaceholder', 'skillId / mcpServerId', 'Agent 来源引用占位文案', NOW(), NOW()),
  ('en-US', 'agent.sourceRefPlaceholder', 'skillId / mcpServerId', 'Agent source ref placeholder', NOW(), NOW()),

  ('zh-CN', 'skill.categoryMcp', 'MCP', 'Skill 分类文案', NOW(), NOW()),
  ('en-US', 'skill.categoryMcp', 'MCP', 'Skill category text', NOW(), NOW())
ON CONFLICT (locale, msg_key)
DO UPDATE SET
  msg_value = EXCLUDED.msg_value,
  description = EXCLUDED.description,
  updated_at = NOW();
