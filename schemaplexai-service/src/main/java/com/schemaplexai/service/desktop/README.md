# Desktop 设备执行桥

此模块把 Server Agent 的本地工具请求路由到当前员工已登记的桌面设备。Agent 模型调用、企业知识、安全策略、预算和执行账本继续由 Server 管理。

- `DesktopExecutionTarget` 从执行 context 解析设备与工作区，缺少字段即拒绝，避免执行位置混淆。
- `DesktopToolExecutor` 作为真实 Agent 工具链的执行器，保持沙箱与安全 PRE/POST 检查。
- `DesktopToolBroker` 负责身份关联、单次投递、调用等待、结果回传、过期及取消。
- REST 入口为 Web 模块的 `DesktopDeviceController`，使用 JWT 身份和 `agent:execute` 权限。

调用方在 `/agents/{id}/execute` 的 context 中同时传入 `desktopDeviceId` 与 `desktopWorkspaceId`。设备先注册，再轮询领取调用；在本机真正执行前核验调用仍有效，执行后只回传原调用结果。协议均位于 `/api/desktop/devices`。

需要交付前验证的 Agent 可在管理配置中设置 `desktop_required_verification_command`。例如将 `mvn clean package` 放在其他写操作和 Shell 调用之后，在授权工作区根目录执行。配置值会进入模型上下文，命令参数应避免携带凭据。

验证入口：`DesktopToolBrokerTest`、`DesktopExecutionTargetTest`、`DesktopToolRoutingTest` 和 `AgentDesktopVisibilityTest`。设计与限制见 [DESIGN.md](DESIGN.md)。
