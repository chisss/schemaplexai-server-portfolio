-- 前端国际化补充文案（Marketplace / Workspace 来源与凭证标签）
-- 目标表: sf_i18n_message

CREATE UNIQUE INDEX IF NOT EXISTS uk_sf_i18n_message_locale_msg_key
  ON sf_i18n_message (locale, msg_key);

INSERT INTO sf_i18n_message (locale, msg_key, msg_value, description, created_at, updated_at)
VALUES
  ('zh-CN', 'marketplace.sourceSkill', 'Skill', '插件市场来源标签', NOW(), NOW()),
  ('en-US', 'marketplace.sourceSkill', 'Skill', 'Marketplace source label', NOW(), NOW()),
  ('zh-CN', 'marketplace.sourceMcp', 'MCP', '插件市场来源标签', NOW(), NOW()),
  ('en-US', 'marketplace.sourceMcp', 'MCP', 'Marketplace source label', NOW(), NOW()),

  ('zh-CN', 'workspace.sourceGithubName', 'GitHub', '工作空间来源名称', NOW(), NOW()),
  ('en-US', 'workspace.sourceGithubName', 'GitHub', 'Workspace source name', NOW(), NOW()),
  ('zh-CN', 'workspace.sourceGitlabName', 'GitLab', '工作空间来源名称', NOW(), NOW()),
  ('en-US', 'workspace.sourceGitlabName', 'GitLab', 'Workspace source name', NOW(), NOW()),
  ('zh-CN', 'workspace.sourceGiteeName', 'Gitee', '工作空间来源名称', NOW(), NOW()),
  ('en-US', 'workspace.sourceGiteeName', 'Gitee', 'Workspace source name', NOW(), NOW()),

  ('zh-CN', 'workspace.credentialToken', '个人访问令牌', '工作空间凭证类型', NOW(), NOW()),
  ('en-US', 'workspace.credentialToken', 'Personal Access Token', 'Workspace credential type', NOW(), NOW()),
  ('zh-CN', 'workspace.credentialSshKey', 'SSH 密钥', '工作空间凭证类型', NOW(), NOW()),
  ('en-US', 'workspace.credentialSshKey', 'SSH Key', 'Workspace credential type', NOW(), NOW())
ON CONFLICT (locale, msg_key)
DO UPDATE SET
  msg_value = EXCLUDED.msg_value,
  description = EXCLUDED.description,
  updated_at = NOW();
