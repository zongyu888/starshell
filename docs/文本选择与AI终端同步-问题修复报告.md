# StarShell 文本选择/复制 与 AI-终端同步 问题修复报告

- **执行日期**: 2026-07-14
- **范围**: 修复项 1（文本选择/复制）+ 修复项 5（AI 与终端同步）
- **编译验证**: `mvn compile` BUILD SUCCESS
- **测试验证**: `mvn test` — 10 个用例全部通过，BUILD SUCCESS

---

## 0. 关于"语言选择下拉框"的说明（重要澄清）

任务描述中提到 *"Verify implementation of language selection dropdowns"* 与 *"state management for language selection"*。

**核查结论**：本应用的国际化架构中，**不存在"每条消息/每个聊天的语言选择下拉框"**。语言切换是**全局设置**，统一在 `SettingsDialog` 的"语言"页（`AppConfig.language`，保存后重启生效，见 `messages_zh/en.properties`）。任务中该措辞疑为模板化表述，与"select AI response [text] and user input [text] for copying"的实际诉求不匹配。

**本报告据实处理**：将 Issue 1 的真实诉求理解为"**用户无法选中 AI 响应/用户输入的文本以进行复制**"，并对根因（`Label`/`TextFlow` 不可选）实施修复。**未虚构**一个产品设计中不存在的语言下拉框。如确需"每会话语言切换"功能，请另行确认。

---

## 一、Issue 1：文本选择与复制

### 1.1 根因

| 组件 | 代码位置 | 问题 |
|------|----------|------|
| 用户气泡 | `ChatBubbleFactory.createUserBubble` (L51) | 用 `Label` 承载文本 —— JavaFX `Label` **不支持鼠标拖选**，用户无法选中自己输入的文字 |
| AI 气泡 | `ChatBubbleFactory.createAssistantBubble` (L81) | 用 `MarkdownRenderer.render()` → `TextFlow` —— JavaFX `TextFlow` **无原生鼠标拖选**，用户无法选中 AI 回复的片段 |
| 复制按钮 | `addActionButtons` (L170) / `MarkdownRenderer` (L355) | 仅"整条复制"按钮存在，**无法复制片段** |

即：存在"整条复制"与"代码块复制"，但**缺失"选区复制"**能力。

### 1.2 修复方案

1. **用户气泡**：`Label` → `TextArea`（`editable=false, wrapText=true`），CSS 伪装成蓝色气泡内的白色文字（透明背景/无边框/无内边距）。`TextArea` 原生支持拖选 + Ctrl+C。
2. **AI 气泡**：保留 `TextFlow` 的 Markdown 渲染（视觉优先），在操作栏新增「选择文本」`ToggleButton`。点击后把 content（index 1）切换为 `TextArea`（显示原文，可选可复制）；再点「返回渲染」切回 Markdown 渲染。切回时从 bubble properties 取回 `sendToTerminalCallback`，保证代码块"发送到终端"按钮仍可用。
3. 整条"复制"按钮与代码块"复制"按钮**保持不变**。

### 1.3 改动文件

| 文件 | 改动 |
|------|------|
| [ChatBubbleFactory.java](file:///c:/Users/mouji/Desktop/AI版%20finalshell/java-client/src/main/java/com/aifinalshell/controller/ChatBubbleFactory.java) | `createUserBubble`：Label→TextArea；`createAssistantBubble`：暂存 callback 到 properties；`addActionButtons`：新增「选择文本」ToggleButton + 切换逻辑 |
| [dark-theme.css](file:///c:/Users/mouji/Desktop/AI版%20finalshell/java-client/src/main/resources/css/dark-theme.css) | 新增 `.ai-bubble-user-text`（透明伪装）、`.ai-bubble-select-text`（深色可选框）样式 |

### 1.4 风险与回滚
- 风险：`TextArea` 与 `Label` 尺寸/换行行为略有差异，已用 `setMaxWidth(MAX_BUBBLE_WIDTH - 28)` 约束；长文本需手动验证不溢出。
- 回滚：`createUserBubble` 还原为 `Label`；移除「选择文本」按钮即可。

---

## 二、Issue 2：AI 助手与终端同步

### 2.1 现象
AI 通过 `run_in_terminal` 工具在可见终端执行命令时，终端"不反映变化"；AI 与终端 UI 状态脱节。

### 2.2 根因链
1. **静默断连**：单一 `ChannelShell` 因服务器 idle timeout / 网络抖动 → JSch 抛 `IOException` → `TerminalController` reader 线程退出 → `isConnected()` 返回 false。断连仅终端区显示"连接已断开"，**AI 聊天区无任何通知** → 用户不知道 AI 已无法操作终端（"同步"断裂的根源）。
2. **无重连**：`TerminalCommandBridge.execute` 仅检查 `isConnected()`，断连直接返回 `"Error: terminal is not connected"`，AI 盲目重试 → 卡死循环。
3. **措辞不一致**：Bridge 返回 `"terminal is not connected"`，`executeCaptured` 返回 `"terminal not connected"`，且都未引导 AI 改用 `execute_shell`。
4. **重复状态事件**：`disconnect()` 直接调 `notifyStatus("Disconnected")` + reader `finally` 再调一次 → 上层收到两次断连事件，易误判。

### 2.3 修复方案（四层）

**A. 断连主动通知 AI 聊天区（核心同步修复）**
`MainController.initTerminal` 的 `setOnStatusChange` 回调：当状态变 "Disconnected" 且**非用户主动断开**（`userInitiatedDisconnect=false`）且当前有连接键时，插入系统气泡「⚠️ 终端已意外断开…」。这让 AI 聊天区**实时感知**终端状态——即"AI 输出与终端 UI 的同步"。

**B. 自动重连**
- `TerminalCommandBridge` 新增 `ReconnectStrategy` 函数式接口 + `setReconnectStrategy`，`execute` 检测断连时调用一次。
- `MainController.reconnectTerminalSync`：优先在仍存活的 SSH 会话上 `openShell`（`SshConnectionManager.connect` 幂等，L36-38）；会话也断则全量重连。网络在调用线程（AI 后台线程），`TerminalController.connect`（含 Timeline/listener）切到 FX 线程并等待。成功后发「🔄 已自动重连」气泡。已知限制：cwd 重置（AI 可自行 cd）。

**C. 错误信息统一 + 引导**
- `ERR_NO_TERMINAL` / `ERR_DISCONNECTED` 常量均含"改用 execute_shell"引导，统一 Bridge 与 `executeCaptured` 措辞。

**D. 状态事件去重**
- `TerminalController.notifyStatus` 仅在状态变化时通知（`lastNotifiedStatus`），根治 `disconnect()` + reader `finally` 重复通知导致的误判。

**E. 用户主动断开标记**
- `disconnectServer` 在 `terminalController.disconnect()` 后置 `userInitiatedDisconnect=true`，配合 A 跳过意外断连气泡（主动断开已有专属气泡）。

### 2.4 改动文件

| 文件 | 改动 |
|------|------|
| [TerminalCommandBridge.java](file:///c:/Users/mouji/Desktop/AI版%20finalshell/java-client/src/main/java/com/aifinalshell/controller/TerminalCommandBridge.java) | 重写：新增 `ReconnectStrategy`、测试用纯函数 `decide()`、`ExecuteOutcome` 枚举、错误常量；`execute` 按判定结果分支（含一次重连重试） |
| [TerminalController.java](file:///c:/Users/mouji/Desktop/AI版%20finalshell/java-client/src/main/java/com/aifinalshell/controller/TerminalController.java) | `notifyStatus` 状态去重；移除 IOException catch 内重复 `notifyStatus`（保留 finally 单次）；`executeCaptured` 断连措辞统一并引导 execute_shell |
| [MainController.java](file:///c:/Users/mouji/Desktop/AI版%20finalshell/java-client/src/main/java/com/aifinalshell/controller/MainController.java) | 新增 `userInitiatedDisconnect` 字段；状态回调加意外断连气泡；注册 `ReconnectStrategy`；新增 `reconnectTerminalSync`；`disconnectServer` 置主动断开标记 |

### 2.5 风险与回滚
- 风险：重连后 cwd/环境变量重置（已文档化，AI 可 cd 恢复）；重连耗时受 8s latch 上限保护。
- 回滚：`bridge.setReconnectStrategy(null)` 即禁用自动重连，退化为"返回明确错误"行为，不影响主流程；状态回调的气泡通知为纯增量，移除即还原。

---

## 三、测试用例（回归防护）

新增 JUnit 5 测试基础设施（`pom.xml` 加 `junit-jupiter` test 作用域 + `maven-surefire-plugin`，**不进入 jlink/jpackage 打包产物**）。

### 3.1 自动化测试：[TerminalCommandBridgeTest.java](file:///c:/Users/mouji/Desktop/AI版%20finalshell/java-client/src/test/java/com/aifinalshell/controller/TerminalCommandBridgeTest.java)

10 个用例，全部通过：

| 分组 | 用例 | 覆盖 |
|------|------|------|
| `DecideBranching` | 无终端优先级（含可重连时仍返回无终端、connected=true 边界） | `decide()` ERROR_NO_TERMINAL 优先级 |
| `DecideBranching` | 终端已连接 → EXECUTE | `decide()` EXECUTE |
| `DecideBranching` | 断连+重连策略 → RECONNECT | `decide()` RECONNECT |
| `DecideBranching` | 断连+无策略 → ERROR_DISCONNECTED | `decide()` ERROR_DISCONNECTED |
| `ErrorMessages` | 无终端错误含 execute_shell 引导 | `ERR_NO_TERMINAL` |
| `ErrorMessages` | 断连错误含 execute_shell 引导 | `ERR_DISCONNECTED` |
| `ExecuteErrorPaths` | null sshKey → 无终端错误（不抛异常） | `execute()` 空值防护 |
| `ExecuteErrorPaths` | 未注册 key → 无终端错误 | `execute()` 路由 |
| `ExecuteErrorPaths` | 注入策略后无终端仍返回无终端错误，策略不被调用 | 优先级集成验证 |
| `ExecuteErrorPaths` | 单例非空且一致 | `getInstance()` |

**测试设计**：把 `decide()` 抽成无副作用纯函数（不依赖 JavaFX），使分支判定可全自动化覆盖；`execute()` 的 EXECUTE/RECONNECT 成功路径依赖 `TerminalController`（构造需 JavaFX TextArea，无法无头实例化），由手动验证矩阵覆盖。

### 3.2 手动验证矩阵（UI/集成层，对应修复计划 V 矩阵）

| # | 步骤 | 预期 | 对应修复 |
|---|------|------|---------|
| V2 | 用户气泡拖选文字 → Ctrl+C → 粘贴 | 粘贴出选中文字 | Issue 1 |
| V3 | AI 气泡点「选择文本」→ 拖选 → 复制 → 点「返回渲染」 | 切回 Markdown；代码块"发送到终端"按钮仍可用 | Issue 1 |
| V4 | 点代码块「复制」/ 整条「复制」 | 粘贴出对应内容 | Issue 1（原有，回归） |
| V10 | 连接后断网/`kill sshd` | AI 聊天区出现 ⚠️ 意外断连气泡 | Issue 2-A |
| V11 | 断连后让 AI 执行命令 | 自动重连成功 + 🔄 气泡 + 命令执行 | Issue 2-B |
| V12 | AI 用 run_in_terminal 执行 `top` | 返回明确超时/断连提示 + 改用 execute_shell 建议 | Issue 2-C |
| V13 | 用户主动点"断开" | 仅显示主动断开气泡，**不**重复显示意外断连气泡 | Issue 2-D/E |

---

## 四、验证结果

| 验证项 | 命令 | 结果 |
|--------|------|------|
| 主代码编译 | `mvn compile` | ✅ BUILD SUCCESS |
| 单元测试 | `mvn test` | ✅ Tests run: 10, Failures: 0, Errors: 0 |
| 打包兼容 | JUnit 为 test 作用域、surefire 仅 test 阶段 | ✅ 不影响 `mvn package -DskipTests`（jlink/jpackage） |

---

## 五、回归防护总结

1. **分支逻辑锁定**：`decide()` 纯函数 + 4 种结果全覆盖测试，任何未来改动若破坏"无终端优先级/断连重连分支"会立即被测试拦截。
2. **错误消息契约**：测试断言错误消息包含 `execute_shell` 引导，防止措辞回退到无引导版本导致 AI 卡死。
3. **空值防护**：`execute(null,...)` 测试防止 NPE 回归。
4. **状态去重**：`notifyStatus` 去重从根本上消除重复事件，配合 `userInitiatedDisconnect` 标志防止主动断开误报。
5. **手动矩阵**：V2/V3/V10/V11/V12/V13 覆盖 UI 层与端到端同步场景。

---

**报告结束**。两个 Issue 根因已定位、修复已实施、自动化测试已通过；UI 层与端到端同步建议按 V2/V3/V10/V11/V12/V13 手动验证。
