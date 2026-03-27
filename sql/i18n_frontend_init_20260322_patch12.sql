-- 前端国际化补充文案（Dashboard / Workspace 导入向导）
-- 目标表: sf_i18n_message

CREATE UNIQUE INDEX IF NOT EXISTS uk_sf_i18n_message_locale_msg_key
  ON sf_i18n_message (locale, msg_key);

INSERT INTO sf_i18n_message (locale, msg_key, msg_value, description, created_at, updated_at)
VALUES
  ('zh-CN', 'dashboard.onboardTitle', '开始使用 SchemaPlexAI', 'Dashboard 引导文案', NOW(), NOW()),
  ('en-US', 'dashboard.onboardTitle', 'Get Started with SchemaPlexAI', 'Dashboard onboarding text', NOW(), NOW()),
  ('zh-CN', 'dashboard.onboardDesc', '创建第一个 Agent，让 AI 帮你自动化工作流程', 'Dashboard 引导文案', NOW(), NOW()),
  ('en-US', 'dashboard.onboardDesc', 'Create your first Agent and let AI automate your workflow', 'Dashboard onboarding text', NOW(), NOW()),
  ('zh-CN', 'dashboard.createFirstAgent', '创建第一个 Agent', 'Dashboard 引导文案', NOW(), NOW()),
  ('en-US', 'dashboard.createFirstAgent', 'Create First Agent', 'Dashboard onboarding text', NOW(), NOW()),

  ('zh-CN', 'workspace.credentialValid', '凭证验证成功，仓库可访问', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.credentialValid', 'Credentials validated, repository is accessible', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.nameRequired', '无法自动推导名称，请手动输入工作空间名称', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.nameRequired', 'Unable to derive name automatically, please enter workspace name manually', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.gitUrlRequired', '请输入仓库地址', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.gitUrlRequired', 'Please enter repository URL', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.localPathRequired', '请输入本地路径', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.localPathRequired', 'Please enter local path', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.chooseSource', '选择项目来源', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.chooseSource', 'Choose Project Source', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.chooseSourceDesc', '选择项目代码的托管方式，我们将根据来源类型提供对应的配置选项', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.chooseSourceDesc', 'Choose where the project code is hosted, and we will provide matching configuration options', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.nameAutoHint', '可选：留空则自动从仓库地址或本地路径推导名称', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.nameAutoHint', 'Optional: leave empty to derive name from repository URL or local path', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.nameAutoPlaceholder', '留空自动推导，或手动输入自定义名称', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.nameAutoPlaceholder', 'Leave empty to auto-derive, or enter a custom name', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.configDetail', '配置详情', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.configDetail', 'Configuration Details', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.validated', '已验证 ✓', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.validated', 'Validated ✓', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.validate', '验证连接', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.validate', 'Validate Connection', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.defaultBranchPlaceholder', '选择或输入分支名，默认 main', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.defaultBranchPlaceholder', 'Select or enter branch name, default is main', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.tokenHint', '仅用于当前导入，不会明文存储', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.tokenHint', 'Used only for this import and will not be stored in plain text', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.localPathAbsoluteRequired', '请输入服务器上的绝对路径，例如 /data/projects/my-app', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.localPathAbsoluteRequired', 'Please enter an absolute server path, for example /data/projects/my-app', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.localPathHint', '请输入项目在服务器上的绝对路径，例如 /data/projects/my-app', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.localPathHint', 'Enter the project absolute path on server, for example /data/projects/my-app', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.localPathPickerTip', '仅辅助填写目录名，请在输入框中补全完整的服务器绝对路径', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.localPathPickerTip', 'Only helps fill directory name. Please complete the full absolute server path in the input box', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.localPathPickerInfo', '已读取目录名「{{dirName}}」。浏览器无法获取服务器绝对路径，请在输入框中补全完整路径。', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.localPathPickerInfo', 'Directory name "{{dirName}}" is loaded. Browser cannot get server absolute path, please complete it manually.', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.confirmImport', '确认导入信息', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.confirmImport', 'Confirm Import Information', 'Workspace import text', NOW(), NOW()),
  ('zh-CN', 'workspace.readyToImport', '仓库连接已验证，点击提交后将开始同步代码结构', 'Workspace 导入文案', NOW(), NOW()),
  ('en-US', 'workspace.readyToImport', 'Repository connection is validated. Click submit to start syncing code structure', 'Workspace import text', NOW(), NOW())
ON CONFLICT (locale, msg_key)
DO UPDATE SET
  msg_value = EXCLUDED.msg_value,
  description = EXCLUDED.description,
  updated_at = NOW();
