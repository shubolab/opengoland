# 2026-11-12 续接快照：open Goland

**完整目标仍是 active，尚未完成。** 本文件是下一轮 goal 的唯一续接入口：先读完“当前权威状态”和“下一次会话起手式”，再以实时 Git、GitHub Actions、Release、安装包与桌面验收结果为准。

本快照于 **2026-11-12 22:40 CST** 冻结。冻结前已正常终止用于支持文件验收的本地 App，未留下 `dlv`、`debugserver` 或测试进程。

## 当前权威状态

- 本次快照生成时，`main` 已推送基线为 `e6bc6d2 Refresh resumed goal snapshot`；快照自身的后续提交以远端 `main` HEAD 为准。
- `.8` tag：`v0.1.0-preview.20261112.8`，指向 `ce0e579`。发布 workflow attempt 2 已成功，五个资产已公开；这是当前 README 的正式下载版本。
- `.9` tag：`v0.1.0-preview.20261112.9`，精确指向 `f23bc48`。冻结时 workflow `43223334622` 仍在运行：version 与 macOS 成功，Windows attempt 2 进行中。
- `.10` tag：`v0.1.0-preview.20261112.10`，精确指向 `e7da5d6`。冻结时 workflow `43227109296` 仍在运行：version 与 Windows 成功，macOS attempt 2 进行中。
- `.11` tag：`v0.1.0-preview.20261112.11`，精确指向 `09ef68c`。冻结时 workflow `43235043231` 仍在运行：version 成功、Windows 进行中、macOS 已取消。workflow 结束后必须重跑 macOS。
- 当前公开版本：[open Goland 0.1.0-preview.20261112.8](https://github.com/shubolab/opengoland/releases/tag/v0.1.0-preview.20261112.8)。五个资产齐全，安装包/GitHub API/SHA256SUMS 摘要一致，Windows 安装卸载退出码均为 0，macOS DMG 已验证挂载复制与安装后 engine smoke。
- `.9` 修复了 Windows 终端补全错误假设 `Ctrl-U` 普遍可用的问题：Unix 保留 `0x15` 清行，Windows 按命令长度发送 Backspace；真实 Windows PowerShell/cmd/Git Bash 验收仍未完成。
- `.10` 将歧义前缀改成原生选择列表；真实 macOS zsh 中 `go t` 显示 `go test / go telemetry / go tool`，选择后写入真实 PTY；同时阻止把 `go test -run te` 这类参数误当子命令。
- `.11` 修复了生产启动只改 app.jar 无法生效的问题：同时打开 Gradle PlatformExtensions 与 `plugins/plugin-classpath.txt!core` 的精确 service 注册。完整打包候选 fresh profile 中原 `InstanceNotOverridableException` 已消失，启动日志无 ERROR/SEVERE。
- 最新本地完整门禁为 **819 Kotlin tests + 86 Python tests**；Kotlin 为 148 reports、0 failures/errors/skips，Python 也无失败。`test`、`buildPlugin`、`prepareSandbox`、`verifyPluginStructure` 均通过。
- 原生格式化验收：真实 App 中一个未格式化 Go 文件经 Shift+Command+L 变为规范 gofmt 布局，一次 Command+Z 恢复原始 malformed 字节；探针未保存，fixture 已恢复。证据见 `docs/verification/go-formatting-native-20261112.json`。
- 上下文补全：真实 gopls 0.24.0 已验证普通包成员、接口方法 `Total`、泛型函数 `Map` 和未保存不完整文档中的 `catalog.Cart` 候选；证据见 `docs/verification/lsp-context-completion-native-20261112.json`。
- 冻结前工作树只新增了 `fixtures/supporting-files/` 支持文件验收夹具；没有修改已有源码。夹具已在本快照同一提交中保留，不应丢弃。
- 本轮重新实测：Computer Use 可读取桌面应用清单；REA 6.5.0 通过 Ghidra 12.1.4 打开 `/bin/echo` 的 arm64e 薄切片，读取 12 个 segment/19 个 procedure 的 overview，完整分析 `entry`（724 bytes、pseudocode/assembly/CFG），随后关闭目标并清理临时文件。

## 冻结时的支持文件验收缺口

已在真实 macOS App 打开 `fixtures/supporting-files/`，观察到以下边界。它们只能记为 partial evidence，`TOOLS-04` 仍必须保持 `unverified`：

| 文件 | 已观察 | 仍欠缺/当前行为 |
| --- | --- | --- |
| `config.yaml` | 识别为 YAML | 补全、校验和 schema 支持尚未验收 |
| `payload.json` | 识别为 JSON | 格式化、路径补全和 schema 校验尚未验收 |
| `Dockerfile` | 可以作为文件打开 | 未确认语法高亮、括号匹配、注释或 Docker 运行能力 |
| `query.sql` | 显示为“自定义类型的文件” | 没有 SQL 高亮；没有数据库连接、补全、导航和运行能力 |
| `api.http` | 作为普通文本打开 | 没有 HTTP 请求 gutter 或执行入口；必须用兼容插件补足 |
| `view.gotmpl` | 作为普通文本打开 | 当前没有 Go Template 文件类型、动作高亮、模板执行或 inspection |

验收夹具包含 Dockerfile、SQL、YAML、JSON、Go Template 和 HTTP 请求文档，均不包含凭据。其 SHA-256 为：

| 文件 | SHA-256 |
| --- | --- |
| `Dockerfile` | `01d3807b919812bb509017eaac31986a0abc7d844a0437d8dd3b7029ee2e9126` |
| `query.sql` | `90292ebdd1a671c6cdb01172e57cfd9c592211be5e6b2d6f488378b5fff54d91` |
| `config.yaml` | `6b1d64bac5e0a22361d076174c2767946383fc9af070b574005bb32730e47dae` |
| `payload.json` | `e864a7f29de8da82fff232ba9885632a50344e809e22ab85c5df5c27b258c715` |
| `view.gotmpl` | `06e2aef13ecae4e7dcc0a465b4ddc7e3fd4fb1c7d6aadbd9d2e8866d8df6541b` |
| `api.http` | `8ac9a6ff5215a7930cbbf04e49b3b88822d27486612a6afba6608743ebf08f17` |

下一次优先实现可靠的 `.gotmpl` / `.go.html` 文件类型与 Go Template 动作高亮，再判断平台 TextMate SQL grammar 是否真的启用。不要在没有数据库模块时宣称 SQL 数据源、补全或导航；也不要增加伪造的 Docker/HTTP 执行按钮。

## 恢复后的剩余工作与工期估算

**严格完成口径：** 当前 36 条矩阵的 `reference_runtime` 与 `reconstruction` 仍全部是 `unverified`。已有大量 partial evidence，但这不等于 GoLand 运行时等价，不能宣称“完美版已经完成”。

预计剩余工期按当前单机、无专用 Windows 真机的条件估算：

| 阶段 | 剩余工作 | 预计 |
| --- | --- | --- |
| 发布收尾 | 等 `.9/.10/.11` 双平台构建、重跑失败作业、验证五资产/SHA-256/provenance、更新 README 与 Release 正文 | 0.5-1 个工作日，主要受 GitHub runner 排队影响 |
| 双平台稳定版 | 真实 Windows 交互安装与启动、插件/中文包、终端、Git、测试、调试；macOS 对应复验与性能/IME/多光标 | 3-5 个工作日 |
| 核心 GoLand 对齐 | 36×2 矩阵：项目/SDK/多模块、语言服务、重构、生成、运行/测试/覆盖/fuzz、调试/goroutine/watch、profile、lint、支持文件、生命周期与性能 | 10-20 个工作日 |
| 高保真收口 | 边界缺陷、真实大型项目回归、故障恢复、发行签名与 macOS 公证、最终 GIF/README/验收证据 | 3-8 个工作日，取决于签名凭据和 Windows 真机 |

因此：

- **“可日常使用且接近 GoLand 核心体验”** 的目标，预计还需要约 **10-20 个工作日**。
- **严格完成当前 36×2 全矩阵、双平台真实工作流和可发布质量**，预计还需要约 **15-30 个工作日**。
- **“每个专有插件、每个商业 GoLand 行为绝对完全一致”** 无法给出可信有限工期；它依赖 JetBrains 专有模块、插件边界、签名证书、公证账号和目标硬件。若坚持这个口径，必须先把它定义为可执行的兼容矩阵，而不是时间承诺。

不建议把工作台整体改成 Go。当前方案是 Kotlin/IntelliJ Platform 原生 UI + Go engine/toolchain；整体 Go 重写会失去 IntelliJ 编辑器、插件、调试器和 GoLand 视觉行为的基础，反而降低还原度。继续沿当前混合架构补齐矩阵是更可信的路线。

## 下一次会话起手式

先执行只读核对：

```sh
cd /Users/wangshubo/develop/openidea-go
git status --short --branch
git log -5 --oneline
for run in 43223334622 43227109296 43235043231; do
  gh run view "$run" --json status,conclusion,url,headSha,jobs \
    --jq '{status,conclusion,url,headSha,jobs:[.jobs[]|{name,status,conclusion}]}'
done
for tag in v0.1.0-preview.20261112.9 v0.1.0-preview.20261112.10 v0.1.0-preview.20261112.11; do
  gh release view "$tag" --json tagName,isPrerelease,isDraft,assets \
    --jq '{tagName,isPrerelease,isDraft,assets:[.assets[]|{name,size,state}]}' 2>&1 || true
done
```

- 每个成功 workflow 都应生成一个不可变 Release，且恰有 Windows EXE、macOS DMG、`windows-provenance.tar.gz`、`macos-provenance.tar.gz`、`SHA256SUMS` 五个资产。核对大小、SHA-256、公开/预发布状态与 tag 指向。
- 若任一 workflow 失败：先运行 `gh run view <id> --log-failed`。远端瞬时失败可原 tag rerun；需要源码变化时发布后续版本，不要移动已推送 tag 或覆盖已公开资产。
- `.9/.10/.11` 发布闭环后，优先在真实 Windows Terminal 中验收 `.10+` 的 cmd.exe、PowerShell/PSReadLine、Windows Git Bash 歧义候选，再继续 36×2 验收矩阵；CI 变绿不等于 GoLand 行为对齐完成。

## 本轮新增与保留边界（2026-11-12）

- 前序终端补全修复：`ce0e579 Fix native Go terminal completion`；后续跨平台清行修复：`f23bc48 Fix Windows terminal completion line clearing`。
- 已创建并推送 tag：`.8` 指向 `ce0e579`，`.9` 指向 `f23bc48`，`.10` 指向 `e7da5d6`，`.11` 指向 `09ef68c`。
- 双平台安装包 workflow 已启动：[GitHub Actions 43213216834](https://github.com/shubolab/opengoland/actions/runs/43213216834)。收尾时它仍在运行；下次先检查最终结论。成功时 Release 地址为 `https://github.com/shubolab/opengoland/releases/tag/v0.1.0-preview.20261112.8`。
- 若 workflow 失败，不要移动已推送 tag 或覆盖已公开资产。先读取 `gh run view 43213216834 --log-failed`；若只是远端瞬时失败可 rerun，若需要源码修复则发布 `.9`。
- 本轮已经删除三份关闭的临时 macOS 候选 App，释放约 8.1 GB；固定 SDK、构建缓存和公开 Release 资产未删除。
- 下一轮先检查 `.8/.9/.10/.11` 的五个发布资产、SHA-256、公开状态和 provenance，再在真实 Windows Terminal 中验收 `.10+`，然后继续 36×2 验收矩阵。

## 本轮调试缺口进展

此前 Go Test Debug 停在 `Launching program` 的直接原因已经确认：当前 Mac 的 `DevToolsSecurity -status` 返回 `Developer mode is currently disabled`。Delve 会完成测试编译并启动 LLDB `debugserver`，但 macOS 拒绝附加，随后界面一直等待 Delve 的启动握手。不是测试代码或 Go SDK 本身卡住。

已经落地的修复：

- 非 headless macOS 在启动 Delve 前检查系统调试权限；失败时明确提示执行 `sudo DevToolsSecurity -enable`，不会再次卡在启动进度条。
- 对 Launch/Test Debug，持续记录 Delve 后代的 PID、启动时间和祖先深度；Stop 超时后只清理仍与这些身份匹配的后代，避免 `debugserver` 和停止态测试进程遗留。
- Attach 模式关闭后代清理，继续保证断开时不会错误杀死用户的被调试进程。
- 新真实进程测试验证了 adapter 的 child/grandchild 被回收，而同 executable 下的无关进程保持运行。

最新验证结果：Kotlin **819/819，148 个报告，0 失败、0 错误、0 跳过**，Python **86/86**；`buildPlugin`、`prepareSandbox`、`verifyPluginStructure`、Go `test -race ./...` 和 `go vet ./...` 均通过。独立 macOS App 中实际点击 Debug 后，应用在启动 Delve 前显示了上述权限说明，进程检查未发现 `dlv`、`debugserver` 或 `__debug_bin` 遗留。

仍差最后一步运行时证据：本机管理员未开放 Developer Mode，而当前自动化会话不能输入管理员密码，因此尚未在同一个候选包中完成“启用权限 → 真实测试运行 → 断点 → PASS → Stop 清理”闭环。机器可读记录：[debug-hang-20261112.json](evidence/debug-hang-20261112.json)。

## 外部 SDK 原生闭环

当前 macOS 候选包已经完成一个真实外部 SDK 的完整桌面流程：从 Settings > Go 点击 **Add local…**，在原生 macOS 目录选择器中选取 Go 1.26.10 GOROOT，等待版本校验后 Apply/OK；随后 Go Test 实际使用外部 `.../bin/go test -json` 运行，结果为 2/2 passed、退出码 0。正常退出并重启 IDE 后，外部 SDK 仍被保留；再次打开设置后成功恢复 **Bundled / automatic**。

这一步同时暴露并修复了一个真实布局缺陷：长 SDK 路径会把下拉框的箭头和右侧操作区挤出可视宽度。现在 SDK 下拉框和详情宽度有上限，仍可随父容器压缩，完整路径保留在 tooltip 中；新增长路径回归测试。机器可读记录：[sdk-local-chooser-native-20261112.json](../../reference/sdk-local-chooser-native-20261112.json)。

## 原生 Terminal

候选 App 中实际打开项目 Terminal 后，`pwd` 指向项目目录，`go version` 为项目内置 **go1.26.10**，`go env GOROOT` 指向安装包内的 SDK；修复前同一终端被用户 zsh 的 mise 配置改回全局 Go1.24.3。现在通过 Terminal 平台公开的 shell-integration force-set/force-prepend 机制，在用户 shell 配置执行后恢复项目 SDK，并保留用户 PATH 与自定义变量。

真实关闭测试启动前台 `sleep 300`，确认进程树后关闭 Terminal 并选择 Terminate；shell 与子进程均退出。参考 GoLand 的已安装 JAR 仅用于确认其公开 Terminal customizer 扩展边界，记录绑定到 artifact SHA-256 `198efc87…55f0`，没有复制商业实现。机器可读记录：[terminal-native-20261112.json](../../reference/terminal-native-20261112.json)。

原生终端补全已经修通。根因是当前用户的 Powerlevel10k zsh 会话中，JetBrains `TerminalView.shellIntegrationDeferred` 始终未完成，平台内置的 Go command-spec 服务因此没有被调用；提高补全设置或关闭 instant prompt 都不能解决。现在的产品回退直接读取活动 `TerminalOutputModel`，解析光标所在命令，并通过 `TerminalInput` 向真实 PTY 发送行清空与完成后的命令。独立 App 中已经实测 `go te → go test` 和 `go test -r → go test -run`，zsh 与 Bash 均通过。当前限制是不会显示多候选弹窗，而是确定性地选择排序最高的候选；Windows、WSL 和其他自定义提示符仍需单独验收。[机器可读记录](../../reference/terminal-command-completion-20261112.json)。

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

1. 先读取本文件和当前最近一个完整 Release 记录，再核对当前 Git、GitHub Actions、Release、进程和磁盘；完成 `.9/.10/.11` 的失败作业重跑、五资产/SHA-256/provenance/公开状态验证，只有五项齐全后才更新 README 的正式下载版本。
2. 从 `fixtures/supporting-files/` 继续：先实现可靠的 `.gotmpl` / `.go.html` Go Template 文件类型与动作高亮，并为 SQL、Dockerfile、HTTP 补真实验收证据；边界能力必须明确标为未实现或依赖插件。
3. 在本机管理员终端执行 `sudo DevToolsSecurity -enable`，之后用当前源码重新打包并完成真实 Go Test Debug、断点、PASS 和 Stop 进程树验收。
4. 若需要 Hopper provider，再确认 Demo 或许可证选择并在重连后验证；REA 本身已通过 Ghidra 完成 native open、分析、关闭闭环。
5. 按 36 × 2 矩阵继续找真实缺口，每一版仍执行双平台测试、安装完成后再发布，并保持已公开版本不可变。
