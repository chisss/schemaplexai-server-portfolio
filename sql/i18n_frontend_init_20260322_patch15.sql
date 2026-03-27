-- 前端国际化补充文案（Context 条目类型 / CICD 类型）
-- 目标表: sf_i18n_message

CREATE UNIQUE INDEX IF NOT EXISTS uk_sf_i18n_message_locale_msg_key
  ON sf_i18n_message (locale, msg_key);

INSERT INTO sf_i18n_message (locale, msg_key, msg_value, description, created_at, updated_at)
VALUES
  ('zh-CN', 'context.itemTypeKnowledge', '知识', '上下文条目类型文案', NOW(), NOW()),
  ('en-US', 'context.itemTypeKnowledge', 'Knowledge', 'Context item type text', NOW(), NOW()),
  ('zh-CN', 'cicd.typeJenkins', 'Jenkins', 'CICD 类型文案', NOW(), NOW()),
  ('en-US', 'cicd.typeJenkins', 'Jenkins', 'CICD type text', NOW(), NOW()),
  ('zh-CN', 'cicd.typeGitlabCi', 'GitLab CI', 'CICD 类型文案', NOW(), NOW()),
  ('en-US', 'cicd.typeGitlabCi', 'GitLab CI', 'CICD type text', NOW(), NOW()),
  ('zh-CN', 'cicd.typeGithubActions', 'GitHub Actions', 'CICD 类型文案', NOW(), NOW()),
  ('en-US', 'cicd.typeGithubActions', 'GitHub Actions', 'CICD type text', NOW(), NOW())
ON CONFLICT (locale, msg_key)
DO UPDATE SET
  msg_value = EXCLUDED.msg_value,
  description = EXCLUDED.description,
  updated_at = NOW();
