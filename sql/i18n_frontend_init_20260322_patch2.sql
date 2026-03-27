-- 前端国际化补充文案（ContextCreate / SpecCreate / AgentDetail）
-- 目标表: sf_i18n_message

CREATE UNIQUE INDEX IF NOT EXISTS uk_sf_i18n_message_locale_msg_key
  ON sf_i18n_message (locale, msg_key);

INSERT INTO sf_i18n_message (locale, msg_key, msg_value, description, created_at, updated_at)
VALUES
  ('zh-CN', 'context.agentAssist.title', 'Agent 辅助编写（可选）', '上下文-辅助编写标题', NOW(), NOW()),
  ('en-US', 'context.agentAssist.title', 'Agent Assisted Writing (Optional)', 'Context assist title', NOW(), NOW()),
  ('zh-CN', 'context.agentAssist.hint', '选择 Agent 并输入提示词，AI 将自动生成上下文内容', '上下文-辅助编写提示', NOW(), NOW()),
  ('en-US', 'context.agentAssist.hint', 'Select an Agent and enter a prompt. AI will generate context content automatically.', 'Context assist hint', NOW(), NOW()),
  ('zh-CN', 'context.agentAssist.selectAgentFirst', '请先选择一个 Agent', '上下文-辅助编写校验', NOW(), NOW()),
  ('en-US', 'context.agentAssist.selectAgentFirst', 'Please select an Agent first', 'Context assist validation', NOW(), NOW()),
  ('zh-CN', 'context.agentAssist.promptRequired', '请输入提示词', '上下文-辅助编写提示词必填', NOW(), NOW()),
  ('en-US', 'context.agentAssist.promptRequired', 'Please enter a prompt', 'Context assist prompt required', NOW(), NOW()),
  ('zh-CN', 'context.agentAssist.started', 'Agent 已开始执行（执行ID: {{id}}）', '上下文-辅助编写启动提示', NOW(), NOW()),
  ('en-US', 'context.agentAssist.started', 'Agent started (Execution ID: {{id}})', 'Context assist started message', NOW(), NOW()),
  ('zh-CN', 'context.agentAssist.generatedFilledContent', 'AI 已完成生成，已自动填充内容', '上下文-辅助编写完成提示1', NOW(), NOW()),
  ('en-US', 'context.agentAssist.generatedFilledContent', 'AI generation completed and content has been filled automatically', 'Context assist generated content message', NOW(), NOW()),
  ('zh-CN', 'context.agentAssist.generatedFilledForm', 'AI 已完成生成，已自动回填表单', '上下文-辅助编写完成提示2', NOW(), NOW()),
  ('en-US', 'context.agentAssist.generatedFilledForm', 'AI generation completed and form fields have been filled automatically', 'Context assist generated form message', NOW(), NOW()),
  ('zh-CN', 'context.agentAssist.selectAgentPlaceholder', '选择辅助 Agent（任意类型）', '上下文-辅助编写 Agent 选择占位', NOW(), NOW()),
  ('en-US', 'context.agentAssist.selectAgentPlaceholder', 'Select an assisting Agent (any type)', 'Context assist agent select placeholder', NOW(), NOW()),
  ('zh-CN', 'context.agentAssist.promptPlaceholder', '输入提示词，告诉 Agent 你需要什么内容...', '上下文-辅助编写提示词占位', NOW(), NOW()),
  ('en-US', 'context.agentAssist.promptPlaceholder', 'Enter a prompt to tell the Agent what content you need...', 'Context assist prompt placeholder', NOW(), NOW()),
  ('zh-CN', 'context.agentAssist.generate', '使用 Agent 生成内容', '上下文-辅助编写按钮', NOW(), NOW()),
  ('en-US', 'context.agentAssist.generate', 'Generate with Agent', 'Context assist button', NOW(), NOW()),

  ('zh-CN', 'spec.agentAssist.selectAgentFirst', '请选择辅助 Agent', 'Spec-辅助编写校验', NOW(), NOW()),
  ('en-US', 'spec.agentAssist.selectAgentFirst', 'Please select an assistant Agent', 'Spec assist validation', NOW(), NOW()),
  ('zh-CN', 'spec.agentAssist.requirementRequired', '请输入需求描述', 'Spec-辅助编写需求描述必填', NOW(), NOW()),
  ('en-US', 'spec.agentAssist.requirementRequired', 'Please enter requirement description', 'Spec assist requirement required', NOW(), NOW()),
  ('zh-CN', 'spec.agentAssist.started', 'Agent 已开始执行（执行ID: {{id}}）', 'Spec-辅助编写启动提示', NOW(), NOW()),
  ('en-US', 'spec.agentAssist.started', 'Agent started (Execution ID: {{id}})', 'Spec assist started message', NOW(), NOW()),
  ('zh-CN', 'spec.agentAssist.generateFailed', '生成失败，请重试', 'Spec-辅助编写失败提示', NOW(), NOW()),
  ('en-US', 'spec.agentAssist.generateFailed', 'Generation failed, please retry', 'Spec assist failed message', NOW(), NOW()),
  ('zh-CN', 'spec.agentAssist.generatedApplied', 'AI 已完成生成，内容已自动填入表单', 'Spec-辅助编写完成提示', NOW(), NOW()),
  ('en-US', 'spec.agentAssist.generatedApplied', 'AI generation completed and content has been filled into the form', 'Spec assist completed message', NOW(), NOW()),
  ('zh-CN', 'spec.agentAssist.selectAgent', '选择辅助 Agent', 'Spec-辅助编写选择Agent标签', NOW(), NOW()),
  ('en-US', 'spec.agentAssist.selectAgent', 'Select Assistant Agent', 'Spec assist select agent label', NOW(), NOW()),
  ('zh-CN', 'spec.agentAssist.selectAgentPlaceholder', '请选择用于辅助生成的 Agent', 'Spec-辅助编写选择Agent占位', NOW(), NOW()),
  ('en-US', 'spec.agentAssist.selectAgentPlaceholder', 'Select an Agent for assisted generation', 'Spec assist select agent placeholder', NOW(), NOW()),
  ('zh-CN', 'spec.agentAssist.requirementDesc', '需求描述', 'Spec-辅助编写需求描述标签', NOW(), NOW()),
  ('en-US', 'spec.agentAssist.requirementDesc', 'Requirement Description', 'Spec assist requirement label', NOW(), NOW()),
  ('zh-CN', 'spec.agentAssist.requirementPlaceholder', '请描述您的需求，Agent 将根据描述自动生成 Spec 的名称、简介、分类和标签，生成后可手动调整。', 'Spec-辅助编写需求描述占位', NOW(), NOW()),
  ('en-US', 'spec.agentAssist.requirementPlaceholder', 'Describe your requirement. The Agent will generate Spec name, summary, category, and tags based on it.', 'Spec assist requirement placeholder', NOW(), NOW()),
  ('zh-CN', 'spec.agentAssist.generate', '智能生成', 'Spec-辅助编写生成按钮', NOW(), NOW()),
  ('en-US', 'spec.agentAssist.generate', 'Smart Generate', 'Spec assist generate button', NOW(), NOW()),

  ('zh-CN', 'agent.stopExecutionConfirm', '确定停止该执行？', 'Agent详情-停止执行确认', NOW(), NOW()),
  ('en-US', 'agent.stopExecutionConfirm', 'Are you sure to stop this execution?', 'Agent detail stop execution confirm', NOW(), NOW()),
  ('zh-CN', 'agent.toolsAndCapabilities', '工具与能力', 'Agent详情-工具与能力标签', NOW(), NOW()),
  ('en-US', 'agent.toolsAndCapabilities', 'Tools & Capabilities', 'Agent detail tools and capabilities', NOW(), NOW()),
  ('zh-CN', 'agent.addBinding', '添加绑定', 'Agent详情-添加绑定按钮', NOW(), NOW()),
  ('en-US', 'agent.addBinding', 'Add Binding', 'Agent detail add binding button', NOW(), NOW()),
  ('zh-CN', 'agent.toolsetTip', '执行工具集 = 系统内置工具 + 当前 Agent 绑定工具', 'Agent详情-工具提示', NOW(), NOW()),
  ('en-US', 'agent.toolsetTip', 'Execution toolset = built-in system tools + tools bound to current Agent', 'Agent detail toolset tip', NOW(), NOW())
ON CONFLICT (locale, msg_key)
DO UPDATE SET
  msg_value = EXCLUDED.msg_value,
  description = EXCLUDED.description,
  updated_at = NOW();
