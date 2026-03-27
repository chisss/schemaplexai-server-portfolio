-- 前端国际化补充文案（Spec 动态审批文案）
-- 目标表: sf_i18n_message

CREATE UNIQUE INDEX IF NOT EXISTS uk_sf_i18n_message_locale_msg_key
  ON sf_i18n_message (locale, msg_key);

INSERT INTO sf_i18n_message (locale, msg_key, msg_value, description, created_at, updated_at)
VALUES
  ('zh-CN', 'spec.submitConfirm', '确认提交 {{docType}} 进行审批？', 'Spec 审批动态文案', NOW(), NOW()),
  ('en-US', 'spec.submitConfirm', 'Submit {{docType}} for review?', 'Spec approval dynamic text', NOW(), NOW()),
  ('zh-CN', 'spec.submitDocReview', '提交 {{docType}} 审批', 'Spec 审批动态文案', NOW(), NOW()),
  ('en-US', 'spec.submitDocReview', 'Submit {{docType}} Review', 'Spec approval dynamic text', NOW(), NOW())
ON CONFLICT (locale, msg_key)
DO UPDATE SET
  msg_value = EXCLUDED.msg_value,
  description = EXCLUDED.description,
  updated_at = NOW();
