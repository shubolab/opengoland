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
| `.10` / `43227109296` attempt 2 | 完整成功并公开；已独立核验 |
| `.11` / `43235043231` attempt 2 | 本轮重跑被取消的 macOS 作业后，双平台流程成功并公开；五资产、校验值、两份 provenance 与原始 XML 已核验，当前下载版本 |
| `.12` / `43244003072` attempt 2 | 重跑原先失联的 Windows runner 后，825 Kotlin 测试 / 150 报告全部通过，但 Go race 的一项 readiness 读取失败；未组装/公开 Windows 安装器，publish skipped |
| 旧日期 `.20261112.13` / `43248990104` | Mac 成功；Windows runner 失联，日志不可取，check annotation 说明连接丢失；具体失联原因未知，未重复运行旧源码 |
| 新日期 `.20261113.1` / `43363144976` | tag 绑定 `2dcbf6509f19fbd2480b0aaea155a95f2caf5873`；参考端对照发现请求体多发两个分隔换行，已在公开前取消 Windows/macOS/publish 作业；不移动 tag |
| 修正版 `.20261113.2` / `43369092135` | tag 绑定 `30288b0c50d36244378a2552c7131ed1e8484dac`；本机 861 测试及真实桌面 39 字节已通过，双平台正在构建，尚未公开 |

`.12` 的失败是 `TestKillingOwnerStopsAlreadyStartedChildrenAndPipes` 在读取 `sentinel.json` 时收到 Windows 文件共享锁错误。此处尚未调用 `startOwner`，没有执行到 owner 终止/管道/存活断言；锁的持有者未知，不能猜成某个杀毒程序。

本轮修复仅修改测试就绪读取：在原有 5 秒期限内重试文件尚未出现和 `ERROR_SHARING_VIOLATION`；其他读取错误和无效 JSON 仍失败，全部清理时限及无关哨兵存活断言保持原样。新增测试创建真实 Windows no-share 文件句柄，先证明读取确被拒绝，再释放并要求同一进程身份。

已用固定 Go 1.27.3 交叉编译 Windows 测试二进制。**这不是 Windows 执行通过。** 当前 GitHub OAuth 凭据缺少新增工作流需要的 `workflow` scope；新增辅助工作流的推送被服务端拒绝，未修改远端。该辅助文件已从尚未发布的本地提交中移除，继续使用仓库已有安装器工作流验证新源码上的 8 个 Windows owner 测试及完整回归。[失败证据和修复边界](../../verification/windows-readiness-sharing-20261113.json)绑定原始约 1 MB artifact 和 SHA-256。

`.20261113.1` 没有公开安装器，保留为取消的候选。后续修正使用 `.20261113.2`，不要移动任何旧 tag。

`.11` 的 Windows / macOS 各 819 个测试、148 报告全部通过；两端安装检查成功。本轮另下载约 10.7 MB `.11` 校验与 provenance，未重复下载完整安装器；Release 正文已补上中英文安装方法和边界，[发布验证记录](../../verification/releases/0.1.0-preview.20261112.11.json)保留精确哈希。README 当前指向 `.11`，并明确其不包含后来才加入的 HTTP/模板等能力。

## 续接顺序

1. 本机 `.20261113.2` 的完整回归和真实桌面 39 字节复验均已通过，源码和 tag 已推送。继续跟进 [run 43369092135](https://github.com/shubolab/opengoland/actions/runs/43369092135)，不要重复 dispatch 或移动 tag。已取消的 `.1` 只有不完整记录，没有可复用的完整 Kotlin/Go 测试证据，不要重跑它发布旧行为。
2. 读取新源码上全部 8 个 Windows owner 控制、完整回归、安装/启动检查及五资产哈希的实际结果；全部通过并公开后才更新下载版本。
3. `.11` 已完成公开与独立核验；保留其安装器字节不可变。修正版 `.20261113.2` 成功时单独建立对应发布证据，再更新 README 与 Release 正文。已取消的 `.1` 不再用于发布。
4. 从 36 × 2 矩阵继续做真实 GoLand 参考端 / 新产品对照，特别是 Windows 交互式终端、插件/中文包、Git、运行测试调试和大工程。HTTP、模板、SQL/Docker 等只是部分证据。
5. macOS 测试调试仍需要本机管理员开放 Developer Mode；本轮没有改系统权限，也没有完成该调试闭环。签名、公证与完整 Windows 原生验收仍开放。

完整矩阵目前 **36 条 reference_runtime 和 36 条 reconstruction 全部仍为 unverified**。本轮只给 TOOLS-04 添加有范围的 partial evidence，不把测试绿或按钮可用当作完整 GoLand 对齐。

## 本机资源

本轮原始工作记录位于 `.tools/continuation-20261113/`，其中包含两个 job retry 的 intent/accepted 记录、`.10` 下载验证、`.12` Windows 原始事件、完整本机测试输出及三张原图。没有运行重复安装器下载或另复制开发 App。

最近实测磁盘仍约 **108 GiB 可用**。保留既有固定 SDK/工具、公开 `.6` 安装副本与当前独立候选；下一轮继续维持至少 20 GiB 余量，并先核对进程占用和证据绑定再清理。

## 参考端对照后追加：请求体字节对齐

等待 CI 时，已在本机官方 GoLand **2026.1.4 / GO-261.26222.72** 中打开隔离的 HTTP 文件，使用同一 loopback Go 服务实际执行 POST。GoLand 发出 39 字节，初版重建发出 41 字节：正文后的两个分隔 LF 被初版误当正文。

随后使用真实 GoLand Run All 执行六组带 X-Probe 的请求。服务端回显为：

| 样例 | 实际正文 | 字节 |
| --- | --- | --- |
| trailing | `alpha` | 5 |
| spaces | `alpha ` 后一个 Tab | 7 |
| leading | `alpha` | 5 |
| leading-spaces | 空格、Tab、`alpha` | 7 |
| edge-space-lines | `alpha` | 5 |
| eof | `alpha` | 5 |

因此修正按“去掉两端整条空白行、保留内容行的前后水平空白”执行，没有使用整体 `trim()`。原始请求文档和参考端返回正文已保存到 `src/test/resources/http-body-reference-20261113.json`，真实 loopback 网络回归已通过，**HTTP 相关共 26 项 / 0 失败**。`.20261113.2` 的完整打包回归已通过：**861 个测试 / 159 报告，0 失败、错误、跳过**，日志在 `.tools/continuation-20261113/http-body-package.log`。

参考安装文件保持只读，启动器 SHA-256 前后相同，GoLand 与示例服务均正常退出。只保存了本次自有项目的响应和界面，没有复制商业实现。[参考端与差异记录](../../verification/http-client-reference-20261113.json)绑定 REA 截图 `ev_0dfd84a…`、启动器哈希和六份回显哈希。

还观察到 HTTP 的 UI 差距：GoLand 使用 Services 命名分组、格式化/着色响应和响应文件链接，并有 Run All、环境/历史/cURL 入口；本仓库这些完整工作流仍未完成。六个参考样例不等于整个 HTTP Client 或 TOOLS-04 对齐完成。

修正版独立 App 也已实测：PID30284/window859 下原生 gutter POST 发出 **39 字节正文**，返回 **104 字节**，与同一参考样例的原始字节一致；Stop 后服务端在 5 秒内记录断开，随后 302 请求继续成功。App 和示例服务均正常退出。README 的 HTTP GIF 已替换为这一版三张真实画面，旧候选的摘要保留在录制记录中。

修正版绑定 **552 个输入 / `81ecf5d1…6bcb37e`**，插件 JAR `c792abca…9ffd07a`，启动器 `666e52f5…338f68d`。本地桌面录制时为 `c7164f8` 加未提交修复，已随后提交为 `30288b0` 并推送 `.20261113.2` tag。真实 Windows/DMG 验证正在 [run 43369092135](https://github.com/shubolab/opengoland/actions/runs/43369092135) 执行；不要把此前取消的 `.1` 当作通过证明。

## 发布校验器范围补齐

等待 `.13.2` CI 时，独立校验工具 `scripts/publish_tested_installers.py` 的 Git 输入集合检查报错。逐项核对发现：打包清单已经正确包含 `product/file-associations.json`，但该辅助校验器的预期集合漏列了它，并非产物中的源码有误。

已补齐辅助工具，并用临时真实 Git 仓库证明完整清单通过、漏掉或篡改文件关联清单均被拒绝；7 个发布校验器测试通过。修正后的工具还逐项验证了本机 `.13.2` App 的全部 **552 个输入字节**与固定提交 `30288b0` 一致。工具修正位于 tag 之后，不改变 `.13.2` 的固定源码、App、已录制画面或正在构建的安装器。

## Git 局部提交的双端原生对照

已用两个隔离本地仓库，在官方 GoLand 2026.1.4 和 `.20261113.2` 独立 App 中分别实际执行：同一文件改两处 → 原生 Show Diff → 只勾第一处差异块 → 原生 Commit。两端都成功提交第一处中文/emoji 修改，并完整保留第二处中文修改。

随后通过 Git 读取提交对象、工作文件和剩余 diff，证明两端结果逐字节相同、暂存区为空；并非只根据成功通知判断。两应用正常退出，记录的已知 App/子进程 PID 均消失，启动器哈希未变。只有自建测试仓库被写入，没有设置远端或修改全局 Git 配置。

[对照记录](../../verification/git-partial-commit-native-20261113.json)包含原始样例、提交/工作区摘要和两张确切窗口截图。参考端 REA AX 因两个窗口位置相同而明确拒绝，另用确切 PID/window 的只读截图记录，交互与文本观察来自 CUA；没有把该失败当作 AX 成功。

该证据仅补充 macOS 的变更块级局部提交；同一块内按行拆分、显式 Git staging-area 模式、远端认证/同步、其他冲突和 Windows Git 交互仍待完成。TOOLS-02 与完整矩阵状态不变。

HTTP 和 Git 原生运行后的独立 App 另通过 `codesign --verify --deep --strict`，**15,139 个插件/内置工具文件摘要全部匹配**；552 个源码输入仍与 `.13.2` 固定提交一致。[运行后完整性记录](../../verification/product-20261113-2-post-native.json)不代替 CI 生成的 DMG 安装验收。本机只读查询仍显示 Developer Mode 禁用，没有修改系统权限，测试调试闭环继续开放。
