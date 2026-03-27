-- 前端国际化补充文案（Workflow 节点元数据与展示）
-- 目标表: sf_i18n_message

CREATE UNIQUE INDEX IF NOT EXISTS uk_sf_i18n_message_locale_msg_key
  ON sf_i18n_message (locale, msg_key);

INSERT INTO sf_i18n_message (locale, msg_key, msg_value, description, created_at, updated_at)
VALUES
  ('zh-CN', 'workflow.designer.idLabel', 'ID', '工作流配置面板ID标签', NOW(), NOW()),
  ('en-US', 'workflow.designer.idLabel', 'ID', 'Workflow config panel ID label', NOW(), NOW()),

  ('zh-CN', 'workflow.designer.node.triggerManual', '手动触发', '节点名称-手动触发', NOW(), NOW()),
  ('en-US', 'workflow.designer.node.triggerManual', 'Manual Trigger', 'Node label manual trigger', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.node.triggerCron', '定时触发', '节点名称-定时触发', NOW(), NOW()),
  ('en-US', 'workflow.designer.node.triggerCron', 'Cron Trigger', 'Node label cron trigger', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.node.triggerEvent', '事件触发', '节点名称-事件触发', NOW(), NOW()),
  ('en-US', 'workflow.designer.node.triggerEvent', 'Event Trigger', 'Node label event trigger', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.node.agent', 'Agent 节点', '节点名称-Agent', NOW(), NOW()),
  ('en-US', 'workflow.designer.node.agent', 'Agent Node', 'Node label agent', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.node.script', '脚本节点', '节点名称-脚本', NOW(), NOW()),
  ('en-US', 'workflow.designer.node.script', 'Script Node', 'Node label script', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.node.apiCall', 'API 调用', '节点名称-API调用', NOW(), NOW()),
  ('en-US', 'workflow.designer.node.apiCall', 'API Call', 'Node label API call', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.node.notification', '通信渠道', '节点名称-通知', NOW(), NOW()),
  ('en-US', 'workflow.designer.node.notification', 'Notification', 'Node label notification', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.node.database', '数据库操作', '节点名称-数据库', NOW(), NOW()),
  ('en-US', 'workflow.designer.node.database', 'Database', 'Node label database', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.node.humanReview', '人工审核', '节点名称-人工审核', NOW(), NOW()),
  ('en-US', 'workflow.designer.node.humanReview', 'Human Review', 'Node label human review', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.node.condition', '条件分支', '节点名称-条件分支', NOW(), NOW()),
  ('en-US', 'workflow.designer.node.condition', 'Condition', 'Node label condition', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.node.parallel', '并行网关', '节点名称-并行网关', NOW(), NOW()),
  ('en-US', 'workflow.designer.node.parallel', 'Parallel Gateway', 'Node label parallel', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.node.loop', '循环', '节点名称-循环', NOW(), NOW()),
  ('en-US', 'workflow.designer.node.loop', 'Loop', 'Node label loop', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.node.end', '结束节点', '节点名称-结束', NOW(), NOW()),
  ('en-US', 'workflow.designer.node.end', 'End Node', 'Node label end', NOW(), NOW()),

  ('zh-CN', 'workflow.designer.nodeDesc.triggerManual', '用户手动启动工作流', '节点描述-手动触发', NOW(), NOW()),
  ('en-US', 'workflow.designer.nodeDesc.triggerManual', 'Manually start workflow', 'Node description manual trigger', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.nodeDesc.triggerCron', '按 Cron 表达式定时触发', '节点描述-定时触发', NOW(), NOW()),
  ('en-US', 'workflow.designer.nodeDesc.triggerCron', 'Trigger by cron schedule', 'Node description cron trigger', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.nodeDesc.triggerEvent', 'Spec变更/Git Push等事件触发', '节点描述-事件触发', NOW(), NOW()),
  ('en-US', 'workflow.designer.nodeDesc.triggerEvent', 'Trigger by Spec changes/Git push and other events', 'Node description event trigger', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.nodeDesc.agent', '调度 AI Agent 执行任务', '节点描述-Agent', NOW(), NOW()),
  ('en-US', 'workflow.designer.nodeDesc.agent', 'Schedule AI Agent to execute tasks', 'Node description agent', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.nodeDesc.script', '执行 Shell/Python 脚本', '节点描述-脚本', NOW(), NOW()),
  ('en-US', 'workflow.designer.nodeDesc.script', 'Execute Shell/Python scripts', 'Node description script', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.nodeDesc.apiCall', '调用外部 REST API', '节点描述-API调用', NOW(), NOW()),
  ('en-US', 'workflow.designer.nodeDesc.apiCall', 'Call external REST APIs', 'Node description API call', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.nodeDesc.notification', '发送通知消息', '节点描述-通知', NOW(), NOW()),
  ('en-US', 'workflow.designer.nodeDesc.notification', 'Send notifications', 'Node description notification', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.nodeDesc.database', '执行数据库操作', '节点描述-数据库', NOW(), NOW()),
  ('en-US', 'workflow.designer.nodeDesc.database', 'Execute database operations', 'Node description database', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.nodeDesc.humanReview', '等待人工审批或评审', '节点描述-人工审核', NOW(), NOW()),
  ('en-US', 'workflow.designer.nodeDesc.humanReview', 'Wait for human approval', 'Node description human review', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.nodeDesc.condition', 'If-Else 条件判断', '节点描述-条件分支', NOW(), NOW()),
  ('en-US', 'workflow.designer.nodeDesc.condition', 'If-Else branching', 'Node description condition', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.nodeDesc.parallel', 'Fork/Join 并行执行', '节点描述-并行网关', NOW(), NOW()),
  ('en-US', 'workflow.designer.nodeDesc.parallel', 'Fork/Join parallel', 'Node description parallel', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.nodeDesc.loop', '循环执行', '节点描述-循环', NOW(), NOW()),
  ('en-US', 'workflow.designer.nodeDesc.loop', 'Loop execution', 'Node description loop', NOW(), NOW()),
  ('zh-CN', 'workflow.designer.nodeDesc.end', '工作流结束', '节点描述-结束', NOW(), NOW()),
  ('en-US', 'workflow.designer.nodeDesc.end', 'Workflow end', 'Node description end', NOW(), NOW())
ON CONFLICT (locale, msg_key)
DO UPDATE SET
  msg_value = EXCLUDED.msg_value,
  description = EXCLUDED.description,
  updated_at = NOW();
