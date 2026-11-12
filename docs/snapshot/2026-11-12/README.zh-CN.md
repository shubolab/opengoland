# 2026-11-12 续接快照：open Goland

**完整目标仍是 active，尚未完成。** 本快照记录本轮已经落地的 Windows 进程所有权修复、`.6` 双平台安装包发布、当前工具状态和下一步工作。

## 当前权威状态

- 已发布源码提交：`585964bb7e53ab2066c6ea079c3f78ba5469e602`
- 当前已推送源码：`e47e9d1`，包含调试权限/进程清理、SDK 设置布局、项目终端 SDK 环境、Go 终端命令规范和原生 Git 冲突流程证据
- 当前候选源码：520 个输入文件；最终摘要会在文档证据提交后重新生成
- 公开版本：[open Goland 0.1.0-preview.20261112.7](https://github.com/shubolab/opengoland/releases/tag/v0.1.0-preview.20261112.7)
- 构建运行：[GitHub Actions 43175443788](https://github.com/shubolab/opengoland/actions/runs/43175443788)

## 本轮调试缺口进展

此前 Go Test Debug 停在 `Launching program` 的直接原因已经确认：当前 Mac 的 `DevToolsSecurity -status` 返回 `Developer mode is currently disabled`。Delve 会完成测试编译并启动 LLDB `debugserver`，但 macOS 拒绝附加，随后界面一直等待 Delve 的启动握手。不是测试代码或 Go SDK 本身卡住。

已经落地的修复：

- 非 headless macOS 在启动 Delve 前检查系统调试权限；失败时明确提示执行 `sudo DevToolsSecurity -enable`，不会再次卡在启动进度条。
- 对 Launch/Test Debug，持续记录 Delve 后代的 PID、启动时间和祖先深度；Stop 超时后只清理仍与这些身份匹配的后代，避免 `debugserver` 和停止态测试进程遗留。
- Attach 模式关闭后代清理，继续保证断开时不会错误杀死用户的被调试进程。
- 新真实进程测试验证了 adapter 的 child/grandchild 被回收，而同 executable 下的无关进程保持运行。

最新验证结果：Kotlin **807/807，147 个报告，0 失败、0 错误、0 跳过**；`buildPlugin`、`prepareSandbox`、`verifyPluginStructure`、Go `test -race ./...` 和 `go vet ./...` 均通过。独立 macOS App 中实际点击 Debug 后，应用在启动 Delve 前显示了上述权限说明，进程检查未发现 `dlv`、`debugserver` 或 `__debug_bin` 遗留。

仍差最后一步运行时证据：本机管理员未开放 Developer Mode，而当前自动化会话不能输入管理员密码，因此尚未在同一个候选包中完成“启用权限 → 真实测试运行 → 断点 → PASS → Stop 清理”闭环。机器可读记录：[debug-hang-20261112.json](evidence/debug-hang-20261112.json)。

## 外部 SDK 原生闭环

当前 macOS 候选包已经完成一个真实外部 SDK 的完整桌面流程：从 Settings > Go 点击 **Add local…**，在原生 macOS 目录选择器中选取 Go 1.26.10 GOROOT，等待版本校验后 Apply/OK；随后 Go Test 实际使用外部 `.../bin/go test -json` 运行，结果为 2/2 passed、退出码 0。正常退出并重启 IDE 后，外部 SDK 仍被保留；再次打开设置后成功恢复 **Bundled / automatic**。

这一步同时暴露并修复了一个真实布局缺陷：长 SDK 路径会把下拉框的箭头和右侧操作区挤出可视宽度。现在 SDK 下拉框和详情宽度有上限，仍可随父容器压缩，完整路径保留在 tooltip 中；新增长路径回归测试。机器可读记录：[sdk-local-chooser-native-20261112.json](../../reference/sdk-local-chooser-native-20261112.json)。

## 原生 Terminal

候选 App 中实际打开项目 Terminal 后，`pwd` 指向项目目录，`go version` 为项目内置 **go1.26.10**，`go env GOROOT` 指向安装包内的 SDK；修复前同一终端被用户 zsh 的 mise 配置改回全局 Go1.24.3。现在通过 Terminal 平台公开的 shell-integration force-set/force-prepend 机制，在用户 shell 配置执行后恢复项目 SDK，并保留用户 PATH 与自定义变量。

真实关闭测试启动前台 `sleep 300`，确认进程树后关闭 Terminal 并选择 Terminate；shell 与子进程均退出。参考 GoLand 的已安装 JAR 仅用于确认其公开 Terminal customizer 扩展边界，记录绑定到 artifact SHA-256 `198efc87…55f0`，没有复制商业实现。机器可读记录：[terminal-native-20261112.json](../../reference/terminal-native-20261112.json)。

当前源码还注册了覆盖 `go build/test/run/mod/work` 等命令与常用选项的终端命令规范。平台 `ShellCompletionTestFixture` 已经走完整条文本、zsh token、provider 和候选生成链路，能够返回 `go test` 与常用测试参数。但独立 App 的重制终端和经典终端中，自动弹出、Tab 和临时快捷键均未显示 Go 候选，Tab 仍回退到 zsh 文件名补全；因此原生终端命令补全仍列为未完成项，不能由自动化测试替代。[机器可读记录](../../reference/terminal-command-completion-20261112.json)。

## 当前源码插件与中文包复核

当前终端候选再次通过原生流程安装 JetBrains 官方 `com.intellij.zh` 261.26222.65：Installed 显示已启用，语言选择 `zh_CN` 后使用原生 Restart，菜单、项目树、设置、搜索与通知均显示简体中文；Terminal、自定义 Go 工具窗口和插件名仍有英文，属于后续本地化范围。

普通 Marketplace 搜索仍返回当前宿主兼容候选，免费与第三方 Paid 插件都保留原生安装入口。官方简体中文包是 IDEA 发行包的一部分，使用本产品的专用官方分发入口，而不是普通 Marketplace 卡片。机器可读记录：[plugin-compatibility-current-source-native-20261112.json](../../verification/plugin-compatibility-current-source-native-20261112.json)。

本轮同时清理了 7 个已被最新候选取代、无进程或文件占用且不属于公开 Release 的开发 App 副本，逻辑大小约 19.9 GB；APFS 实际新增可用空间约 7.73 GB。保留了公开 `.6` 安装副本、固定平台和最新 `open Goland terminal force env.app`。完整路径、manifest、插件 JAR 与 engine 哈希记录在 [artifact-cleanup-terminal-20261112.json](evidence/artifact-cleanup-terminal-20261112.json)。

## 本轮完成的代码修复

Windows 上有限时长的 Go module/SDK 命令现在通过本仓库 Go 引擎的私有 owner 模式启动。目标进程在启动前被原子加入一个不继承的 Windows Job Object，并设置 `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`。因此取消、超时、IDE 退出，甚至启动器正常结束后仍留下后代进程时，整个命令树都会随 owner 关闭而结束。Unix 继续使用原有的独占 PTY 进程组。

这不是只改一个测试超时：

- 新增 `engine/internal/processowner/`，包含 Windows Job Object 实现和平台边界测试。
- Windows 真机 7 个 `processowner` 测试全部 PASS。
- 原 30 秒 helper 自然退出不再可能伪装成及时清理；取消控制要求在 5 秒门限内结束。
- 参数、环境、工作目录、UTF-8 标准输出/错误流和原始退出码均有真机控制。

机器可读记录：[windows-owner-release.json](evidence/windows-owner-release.json)。

## 测试与发布结果

| 门禁 | 结果 |
| --- | --- |
| 本机 Kotlin | 798/798，143 个报告，0 失败、0 错误、0 跳过 |
| 本机 Go race | 349 个测试事件，12 个有测试包通过，无失败、无缓存 |
| 本机 Go vet | 通过 |
| Windows 发布运行 | Kotlin 798/798，0 失败/错误/跳过；7 个新 Windows 所有权测试 PASS |
| macOS 发布运行 | Kotlin 798/798，0 失败/错误/跳过；2 个可移植 owner 测试 PASS |
| 安装验证 | Windows 实际安装、启动、测试、卸载；macOS 实际 DMG 挂载与安装包验证 |
| 发布 | Windows EXE、macOS DMG、SHA256SUMS 和两平台 provenance 均已上传 |

安装包摘要：

- Windows：`50602b5a7c63d526270feea1e4947704404d02d51ba012a819f7fc2df3475408`
- macOS：`b999d41b339bf72b129f33deb922a24af2577cf0960e9d451738e89f98d0bdc5`

## 工具状态

Computer Use 已实际读取 macOS 应用清单和 Hopper 窗口，工具可用。

REA MCP 注册、技能版本 42 和 207 个工具目录已对齐。本轮实际打开 `/bin/echo`，完成 38 项 Mach-O 结构分析并关闭目标。随后用 Ghidra 12.1.4 打开一个 sha256 为 `f024cb2b…b2b97ed` 的 arm64e 薄切片，识别 19 个 procedure，完整分析 `entry` 函数（724 字节，pseudocode、assembly、CFG 均可用），关闭目标并清理临时文件。**REA 的完整 native 分析链路已经可用。**

Hopper 也已安装到：

`/Users/wangshubo/Applications/Hopper Disassembler.app`

REA 配置已写入对应 `HOPPER_LAUNCHER_PATH`。Hopper 第一次启动仍停在官方“Demo 或许可证”窗口；这是可选的第二条 provider，不影响上面的 Ghidra 链路。若以后要用 Hopper，需要用户明确选择 Demo 或许可证，并重连客户端让当前 REA 进程读取新路径。

机器可读工具记录：[tool-readiness.json](evidence/tool-readiness.json)。

## 仍未完成

- 36 × 2 GoLand 对比矩阵绝大多数仍是 `unverified`，不能因自动化测试变绿而视为对齐完成。
- 原生桌面工作流仍需继续：未缓存测试、测试调试准备 Cancel、反向面板刷新、最窄窗口布局和不同 SDK 版本矩阵。
- 官方中文语言包安装、通用插件兼容、所有专有 JetBrains 插件边界仍需持续实测。
- Windows 和 macOS 的真实大型项目、输入法、多光标、重构、调试、性能、Git、终端和插件工作流仍开放。
- 当前 Windows EXE 未做发行者签名，macOS App 使用临时签名且未公证。
- Hopper Demo/license 一次性选择尚未确认；它只影响可选的 Hopper provider，当前 native REA 已由 Ghidra 验证。

## 续接顺序

1. 先读取本文件和 `docs/verification/releases/0.1.0-preview.20261112.7.json`，再核对当前 Git、GitHub Release、进程和磁盘。
2. 在本机管理员终端执行 `sudo DevToolsSecurity -enable`，之后用当前源码重新打包并完成真实 Go Test Debug、断点、PASS 和 Stop 进程树验收。
3. 若需要 Hopper provider，再确认 Demo 或许可证选择并在重连后验证；REA 本身已通过 Ghidra 完成 native open、分析、关闭闭环。
4. 按 36 × 2 矩阵继续找真实缺口，每一版仍执行双平台测试、安装完成后再发布，并保持已公开版本不可变。
