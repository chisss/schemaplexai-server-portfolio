-- 前端国际化补充文案（Spec 看板阶段）
-- 目标表: sf_i18n_message

CREATE UNIQUE INDEX IF NOT EXISTS uk_sf_i18n_message_locale_msg_key
  ON sf_i18n_message (locale, msg_key);

INSERT INTO sf_i18n_message (locale, msg_key, msg_value, description, created_at, updated_at)
VALUES
  ('zh-CN', 'spec.kanbanDraft', '草稿', 'Spec 看板阶段文案', NOW(), NOW()),
  ('en-US', 'spec.kanbanDraft', 'Draft', 'Spec kanban stage text', NOW(), NOW()),
  ('zh-CN', 'spec.kanbanReview', '评审中', 'Spec 看板阶段文案', NOW(), NOW()),
  ('en-US', 'spec.kanbanReview', 'In Review', 'Spec kanban stage text', NOW(), NOW()),
  ('zh-CN', 'spec.kanbanApproved', '已通过', 'Spec 看板阶段文案', NOW(), NOW()),
  ('en-US', 'spec.kanbanApproved', 'Approved', 'Spec kanban stage text', NOW(), NOW()),
  ('zh-CN', 'spec.kanbanExecution', '执行中', 'Spec 看板阶段文案', NOW(), NOW()),
  ('en-US', 'spec.kanbanExecution', 'Execution', 'Spec kanban stage text', NOW(), NOW()),
  ('zh-CN', 'spec.kanbanDone', '已完成', 'Spec 看板阶段文案', NOW(), NOW()),
  ('en-US', 'spec.kanbanDone', 'Done', 'Spec kanban stage text', NOW(), NOW())
ON CONFLICT (locale, msg_key)
DO UPDATE SET
  msg_value = EXCLUDED.msg_value,
  description = EXCLUDED.description,
  updated_at = NOW();
