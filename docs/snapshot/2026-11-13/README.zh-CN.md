# 2026-11-13 续接快照

目标仍为 **active**：继续做可日常使用、尽可能还原 GoLand 2026.1.4 的独立 open Goland。
当前仍是 Kotlin / 开源 IntelliJ Platform 工作台，加 Go engine、gopls 和 Delve；没有改成 Go 重写整套界面。
IDE 无账号、计费、订阅和试用门槛，开发工具认证继续保留。

## 已核对的基线

- 已 fetch 和 fast-forward 核对远端 `main`，起点为 `9023bb7`，工作树当时干净。
- 读取了 [11 月 12 日快照](../2026-11-12/README.zh-CN.md)。旧的 11 月 11 日 Windows owner 未应用草案已经过期；生产 Job Object 实现早已进入 `.6`。
- `.10` 实际已公开，原 README 仍指 `.8`。本轮验证五资产、SHA256SUMS、两份 provenance、原始测试 XML 和 tag/source 绑定，中英文下载入口现指向 `.10`。
- `.10` 两平台各 **819 个测试 / 148 个报告，0 失败、错误或跳过**。只下载约 11 MB 校验与 provenance，没有重复下载约 1.9 GB 安装器。
- 下载说明与证据已在 `2baa81e` 提交并推送；[绑定版本的记录](../../verification/releases/0.1.0-preview.20261112.10.json)保留全部资产哈希。

## 新增 HTTP 实际工作流

提交 `fd02bd2` 加入了内置 `.http` 执行器，不再只有词法高亮：

- 请求行 gutter 和右键 **Run HTTP Request** 使用当前编辑器快照发请求。
- 原生 Run 面板显示状态码、耗时、响应头和文本响应体；Stop 与项目/应用退出取消请求。
- 支持基本 HTTP 方法、UTF-8 请求体和有限的文件变量展开；重定向不自动跟随，Cookie 不自动保存复用。
- 请求整体 30 秒上限，包含响应体；请求体和响应体各 1 MiB；URL 16 KiB，请求头合计 64 KiB / 200 个。
- 不支持的脚本、文件体和指令在发请求前报错。环境文件、动态变量、脚本、凭据库、二进制保存、WebSocket/gRPC、完整 HTTP Client 兼容仍未实现。
- [本地 Go HTTP 示例](../../../fixtures/http-client)不需要外部依赖，监听 loopback；[支持范围](../../verification/http-client.md)给出了操作方法。

本机最终限额候选的完整门禁为 **859 Kotlin/平台测试、159 个报告，0 失败、错误或跳过**；`buildPlugin`、`prepareSandbox`、`verifyPluginStructure` 成功。86 个 Python 检查也通过。

真实 macOS 桌面已观察 GET、gutter POST 中文/emoji 响应、Stop 后服务端连接断开、302 原始响应，以及活动请求下 Close Project → Terminate。最终限额候选另复验了 POST 和活动请求下退出应用 → Terminate，服务端在 25 秒自然结束前记录断开。两次验收 App 和示例服务均正常结束。

证据按构建分别绑定，不能混为同一产物：

- 最初完整桌面记录和 GIF：551 输入，`b0933abf…069286`，插件 JAR `565a11cc…89230`；早于最后 URL/请求头限额补充。
- 最终限额候选：551 输入，`fbfdeddb…0922a`，插件 JAR `433a6989…bd746`；859 项完整门禁及单独桌面复验。
- 后续 Windows-only 测试握手修改不改变运行时代码；仍需其实际 Windows 结果。
- [HTTP 原生记录](../../verification/http-client-native-20261113.json)、[REA 产物记录](../../verification/http-client-rea-20261113.json)、[GIF 录制记录](../../demo/http-client-recording.json)。

中英文 README 已加入 12 秒、3 张真实操作阶段画面的 GIF。原始截图由 REA 对确切 PID/window 获取；CUA 操作原生界面，未添加模拟界面。REA 的首次 AX 捕获有部分节点读取错误，已明确保留限制；截图与 CUA 的编辑器/响应观察分别记录。

## 发布与 Windows 新失败

| 版本 / run | 当前核对结果 |
| --- | --- |
| `.10` / `43227109296` attempt 2 | 完整成功并公开；当前下载版本 |
| `.11` / `43235043231` attempt 2 | 本轮重跑被取消的 macOS 作业后，双平台流程已成功；公开资产仍需独立核验 |
| `.12` / `43244003072` attempt 2 | 重跑原先失联的 Windows runner 后，825 Kotlin 测试 / 150 报告全部通过，但 Go race 的一项 readiness 读取失败；未组装/公开 Windows 安装器，publish skipped |

`.12` 的失败是 `TestKillingOwnerStopsAlreadyStartedChildrenAndPipes` 在读取 `sentinel.json` 时收到 Windows 文件共享锁错误。此处尚未调用 `startOwner`，没有执行到 owner 终止/管道/存活断言；锁的持有者未知，不能猜成某个杀毒程序。

本轮修复仅修改测试就绪读取：在原有 5 秒期限内重试文件尚未出现和 `ERROR_SHARING_VIOLATION`；其他读取错误和无效 JSON 仍失败，全部清理时限及无关哨兵存活断言保持原样。新增测试创建真实 Windows no-share 文件句柄，先证明读取确被拒绝，再释放并要求同一进程身份。

已用固定 Go 1.27.3 交叉编译 Windows 测试二进制。**这不是 Windows 执行通过。** 当前 GitHub OAuth 凭据缺少新增工作流需要的 `workflow` scope；新增辅助工作流的推送被服务端拒绝，未修改远端。该辅助文件已从尚未发布的本地提交中移除，继续使用仓库已有安装器工作流验证新源码上的 8 个 Windows owner 测试及完整回归。[失败证据和修复边界](../../verification/windows-readiness-sharing-20261113.json)绑定原始约 1 MB artifact 和 SHA-256。

计划的新版本为 `0.1.0-preview.20261113.1`；此时尚未切 tag / 公开安装器。不要把源码候选写成已经可下载的新 Release，也不要移动 `.12` 的旧 tag。

## 续接顺序

1. 提交并推送新源码，在确切提交上创建新的 `.13.1` tag，使用已有双平台 EXE / DMG 流程做真实 Windows 验证。不能通过重跑旧 `.12` 修复源码问题。
2. 读取新源码上全部 8 个 Windows owner 控制、完整回归、安装/启动检查及五资产哈希的实际结果；全部通过并公开后才更新下载版本。
3. 继续核对 `.11` macOS 作业的结果；成功产物若满足现有发布条件可按原流程发布，不能覆盖公开版本的资产。
4. 从 36 × 2 矩阵继续做真实 GoLand 参考端 / 新产品对照，特别是 Windows 交互式终端、插件/中文包、Git、运行测试调试和大工程。HTTP、模板、SQL/Docker 等只是部分证据。
5. macOS 测试调试仍需要本机管理员开放 Developer Mode；本轮没有改系统权限，也没有完成该调试闭环。签名、公证与完整 Windows 原生验收仍开放。

完整矩阵目前 **36 条 reference_runtime 和 36 条 reconstruction 全部仍为 unverified**。本轮只给 TOOLS-04 添加有范围的 partial evidence，不把测试绿或按钮可用当作完整 GoLand 对齐。

## 本机资源

本轮原始工作记录位于 `.tools/continuation-20261113/`，其中包含两个 job retry 的 intent/accepted 记录、`.10` 下载验证、`.12` Windows 原始事件、完整本机测试输出及三张原图。没有运行重复安装器下载或另复制开发 App。

最近实测磁盘仍约 **109 GiB 可用**。保留既有固定 SDK/工具、公开 `.6` 安装副本与当前独立候选；下一轮继续维持至少 20 GiB 余量，并先核对进程占用和证据绑定再清理。
