-- 前端国际化补充文案（SystemMenu / SystemRole 收尾）
-- 目标表: sf_i18n_message

CREATE UNIQUE INDEX IF NOT EXISTS uk_sf_i18n_message_locale_msg_key
  ON sf_i18n_message (locale, msg_key);

INSERT INTO sf_i18n_message (locale, msg_key, msg_value, description, created_at, updated_at)
VALUES
  ('zh-CN', 'system.role.noData', '暂无角色', '角色管理空状态文案', NOW(), NOW()),
  ('en-US', 'system.role.noData', 'No roles', 'Role management empty state text', NOW(), NOW())
ON CONFLICT (locale, msg_key)
DO UPDATE SET
  msg_value = EXCLUDED.msg_value,
  description = EXCLUDED.description,
  updated_at = NOW();
