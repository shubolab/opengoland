# 2026-11-13 续接快照

目标仍为 **active**：继续做可日常使用、尽可能还原 GoLand 2026.1.4 的独立 open Goland。
当前仍是 Kotlin / 开源 IntelliJ Platform 工作台，加 Go engine、gopls 和 Delve；没有改成 Go 重写整套界面。
IDE 无账号、计费、订阅和试用门槛，开发工具认证继续保留。

最近已公开并独立核验的安装版为 **[0.1.0-preview.20261113.3](https://github.com/shubolab/opengoland/releases/tag/v0.1.0-preview.20261113.3)**，包含 HTTP 批量、Services、Stop All 与原始响应保存。两端各 **911 项 / 166 报告**通过，HTTP 各 72 项、Windows 8 项 owner 测试通过。下载入口、中文/英文说明与真实 GIF 已更新；[发布记录](../../verification/releases/0.1.0-preview.20261113.3.json)绑定五资产和固定源码。此前阶段记录保留供追溯。

## 本轮续接：HTTP 批量与 Services

再次 fetch 后，以远端 `main` 与本机一致的 `4c8c96f` 为起点，已完成 `.20261113.3`：编辑器顶部 Run All、按名称排列的原生 Services 结果、格式化/高亮响应、响应头、单项错误后继续、Stop All、原始响应保存和有界内存保留。旧 `.2` 的单请求控制台作为历史范围保留，新功能已进入公开 `.3` 安装包。

官方 GoLand 的七份隔离样例、16 个请求条目已保留为回归向量。实测确认文件变量可向前引用、同名重复定义导致引用失败、首个 `# @name` 优先。两个 GoLand Stop 探针虽然在 UI 中显示终止，后续请求仍发出，慢连接也未取消；本产品明确实现 Stop All，不复制这个行为。[参考记录](../../verification/http-batch-reference-20261113.json)说明原始服务端事件、确切窗口截图和取证范围。

首个本机候选通过 **909 项测试 / 166 份报告**，但随后的原生验收发现 Services 数据快照中创建 `OpenFileDescriptor` 会触发 UI 线程锁限制，另有工具栏动作实例反复创建的警告。该候选未提交发布，修复后须重新打包并复验。已观察到的 503/变量错误继续、Stop All 断开且排队未发送、八字节二进制保存、退出 Terminate 断开，仅绑定该候选；[首轮记录](../../verification/http-batch-first-native-20261113.json)保留失败原因，不能作为修正版已通过的证明。

生产修复已改成延迟导航并缓存各节点的工具栏动作。随后完整回归中，实际 911 项均通过（含 Session 的 8 项）；但 Kotlin 生成的无参匿名方法因名称以 `test` 开头，被 JUnit 3 额外当成非公开测试，产生第 912 项失败。没有组装或发布该包；[发现机制记录](../../verification/http-batch-junit-discovery-20261113.json)保留原始错误。测试正控移入普通 helper 后仍需重新确认完整门禁。

最终修正版已通过 **911 项 / 166 报告，零失败、错误、跳过**，HTTP 共 72 项；插件构建、结构检查和独立 App 组装成功。输入为 **566 个 / `86585480…bc5d095`**，插件 JAR `17b78990…70056a`，启动器 `e133559a…d28a51`。本机复用了 engine，VCS 元数据仍为 `c7164f8 + modified=true`，其 Go 源码树未变；不能把这写成 clean head 重新编译的 engine。

PID84677/window2269 的最终桌面复验通过：三条命名结果 200 → 503 → 200、中文 JSON 与 Headers、单项变量错误后继续、双击 Services 返回 `batch.http:9`、只重跑所选请求、Stop All（8 秒断开且排队未发）、活动 Remove Batch（5 秒断开，Services 条目及 Run 内容一起清理）、八字节二进制保存、Close Project → Terminate（9 秒断开并回到欢迎页）。随后 App 正常退出，已知 PID 消失，示例服务正常退出 0 且端口释放。

[修正版记录](../../verification/http-batch-native-20261113.json)绑定实际日志和构建。运行后 `codesign --verify --deep --strict` 成功，15,139 个封存文件及全部输入摘要仍匹配；本次进程日志没有 SEVERE、导航禁锁或工具栏实例不稳定消息。三张 REA 确切窗口截图已生成 12 秒真实 [批量 GIF](../../demo/native-http-batch.gif)，中英文 README 已更新。首帧 AX 读取有 22 个缺口，截图和 CUA 交互观察分别记录；不宣称完整 AX 捕获。

当前 HTTP 交付已完成双平台安装器、公开发布及独立核验；固定 tag 与资产字节保持不变。显示配额提示仍把 UTF-16 单元称为 MiB/KiB，记录为后续小文案修正；原始正文按字节限额正确。完整 36 × 2 矩阵仍未完成，Windows 交互和 macOS Developer Mode 调试继续开放。

源码固定为 **`32fd60a43e68a9b879fae0d04ea96bd5ba41547c`**，tag 为 **`v0.1.0-preview.20261113.3`**；[run 43393231491](https://github.com/shubolab/opengoland/actions/runs/43393231491) attempt 1 的 version、Windows、macOS、publish 四作业均成功，安装器于 **09:21:03Z** 公开。[源码绑定](../../verification/http-batch-source-binding-20261113.json)逐个验证本机候选的 566 个输入和六个示例文件与固定提交一致；两端 provenance 也逐项匹配同一源码集合。只下载 **11,075,123 字节**校验与 provenance，另读取小体积作业日志，没有重复下载完整 EXE / DMG。

公开原始 XML 确认两端均为 911 项、166 报告，测试身份集合完全相同、零失败/错误/跳过；HTTP 各 72 项，Session 各 8 项，新增禁锁快照和源码导航用例均实际 pass。Windows 的 8 项 owner 测试具有真实 pass 事件，Go race 未使用缓存测试结果，vet 和安装检查成功。五资产的 API digest、SHA256SUMS、生产者大小/摘要已相互核对。中英文 Release 正文发布后，再次确认五资产 ID、大小和摘要未变。

等待安装器期间，已把后续推进收敛为[五个具体工作包](next-goal-probes.zh-CN.md)：工程设置与分析一致性、真实工程与 gopls 恢复、实际中文输入法、HTTP 环境文件、Windows 首日使用。逐项区分缺实现、缺证据和机器/权限条件，并列出实际完成标准。

## 已核对的基线

以下保留此前阶段按当时版本记录的结果与限制；当前下载版本和 HTTP 范围以上面的 `.13.3` 最终交付为准。

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
- 后续 Windows-only 测试握手修改不改变运行时代码；现已在 `.13.2` 的真实 Windows runner 上通过 8 项 owner 测试。
- [HTTP 原生记录](../../verification/http-client-native-20261113.json)、[REA 产物记录](../../verification/http-client-rea-20261113.json)、[GIF 录制记录](../../demo/http-client-recording.json)。

中英文 README 已加入 12 秒、3 张真实操作阶段画面的 GIF。原始截图由 REA 对确切 PID/window 获取；CUA 操作原生界面，未添加模拟界面。REA 的首次 AX 捕获有部分节点读取错误，已明确保留限制；截图与 CUA 的编辑器/响应观察分别记录。

## 发布与 Windows 新失败

| 版本 / run | 当前核对结果 |
| --- | --- |
| `.10` / `43227109296` attempt 2 | 完整成功并公开；已独立核验 |
| `.11` / `43235043231` attempt 2 | 本轮重跑被取消的 macOS 作业后，双平台流程成功并公开；五资产、校验值、两份 provenance 与原始 XML 已核验，此前下载版本，已由 `.13.2` 接替 |
| `.12` / `43244003072` attempt 2 | 重跑原先失联的 Windows runner 后，825 Kotlin 测试 / 150 报告全部通过，但 Go race 的一项 readiness 读取失败；未组装/公开 Windows 安装器，publish skipped |
| 旧日期 `.20261112.13` / `43248990104` | Mac 成功；Windows runner 失联，日志不可取，check annotation 说明连接丢失；具体失联原因未知，未重复运行旧源码 |
| 新日期 `.20261113.1` / `43363144976` | tag 绑定 `2dcbf6509f19fbd2480b0aaea155a95f2caf5873`；参考端对照发现请求体多发两个分隔换行，已在公开前取消 Windows/macOS/publish 作业；不移动 tag |
| 修正版 `.20261113.2` / `43369092135` | tag 绑定 `30288b0c50d36244378a2552c7131ed1e8484dac`；双平台安装器、发布四作业均成功；五资产、552 输入与原始测试明细已核验；两端各 861/159 全通过，此前下载版本，现由 `.13.3` 接替 |
| 批量版 `.20261113.3` / `43393231491` | tag 绑定 `32fd60a43e68a9b879fae0d04ea96bd5ba41547c`；四作业成功并公开；五资产、566 输入和两端各 911 项 / 166 报告已独立核验；当前下载版本 |

`.12` 的失败是 `TestKillingOwnerStopsAlreadyStartedChildrenAndPipes` 在读取 `sentinel.json` 时收到 Windows 文件共享锁错误。此处尚未调用 `startOwner`，没有执行到 owner 终止/管道/存活断言；锁的持有者未知，不能猜成某个杀毒程序。

本轮修复仅修改测试就绪读取：在原有 5 秒期限内重试文件尚未出现和 `ERROR_SHARING_VIOLATION`；其他读取错误和无效 JSON 仍失败，全部清理时限及无关哨兵存活断言保持原样。新增测试创建真实 Windows no-share 文件句柄，先证明读取确被拒绝，再释放并要求同一进程身份。

已用固定 Go 1.27.3 交叉编译 Windows 测试二进制。**这不是 Windows 执行通过。** 当前 GitHub OAuth 凭据缺少新增工作流需要的 `workflow` scope；新增辅助工作流的推送被服务端拒绝，未修改远端。该辅助文件已从尚未发布的本地提交中移除，继续使用仓库已有安装器工作流验证新源码上的 8 个 Windows owner 测试及完整回归；该流程已实际通过。[失败证据和修复边界](../../verification/windows-readiness-sharing-20261113.json)绑定原始约 1 MB artifact 和 SHA-256。

`.20261113.1` 没有公开安装器，保留为取消的候选。后续修正使用 `.20261113.2`，不要移动任何旧 tag。

`.11` 的 Windows / macOS 各 819 个测试、148 报告全部通过；两端安装检查成功。本轮另下载约 10.7 MB `.11` 校验与 provenance，未重复下载完整安装器；Release 正文已补上中英文安装方法和边界，[发布验证记录](../../verification/releases/0.1.0-preview.20261112.11.json)保留精确哈希。此后 `.13.2` 已公开并通过独立核验，README 下载入口现指向 `.13.2`；`.11` 的能力与资产记录继续保留。

## 续接顺序

1. `.20261113.3` 已完成 [run 43393231491](https://github.com/shubolab/opengoland/actions/runs/43393231491) 和独立五资产/源码/原始测试核验。保留固定 tag 与公开字节；只有后续产品变更才开新版本。已取消的 `.1` 不再重跑发布。
2. Windows readiness 原失败已在新源码上通过真实共享锁回归和全部 8 个 owner 测试；不再把这项列为待验证。后续重点仍是 Windows 交互式终端、插件/中文包、Git、运行测试调试及大工程，而不是把 CI 启动烟测等同于这些交互。
3. 中英文 README 下载入口已切到 `.13.3`，[对应发布记录](../../verification/releases/0.1.0-preview.20261113.3.json)绑定五资产、566 输入、两平台各 911 测试 / 166 报告。旧 `.2`、`.11` 字节保持不可变；不重复下载完整安装器。
4. 从 36 × 2 矩阵继续做真实 GoLand 参考端 / 新产品对照，特别是 Windows 交互式终端、插件/中文包、Git、运行测试调试和大工程。HTTP、模板、SQL/Docker 等只是部分证据。
5. macOS 测试调试仍需要本机管理员开放 Developer Mode；本轮没有改系统权限，也没有完成该调试闭环。签名、公证与完整 Windows 原生验收仍开放。

完整矩阵目前 **36 条 reference_runtime 和 36 条 reconstruction 全部仍为 unverified**。本轮只给 TOOLS-04 添加有范围的 partial evidence，不把测试绿或按钮可用当作完整 GoLand 对齐。

## 本机资源

本轮原始工作记录位于 `.tools/continuation-20261113/`，其中包含两个 job retry 的 intent/accepted 记录、`.10` 下载验证、`.12` Windows 原始事件、完整本机测试输出及三张原图。没有运行重复安装器下载或另复制开发 App。

本轮结束时实测磁盘约 **98 GiB 可用**。保留既有固定 SDK/工具、公开 `.6` 安装副本与当前独立候选；下一轮继续维持至少 20 GiB 余量，并先核对进程占用和证据绑定再清理。

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

修正版绑定 **552 个输入 / `81ecf5d1…6bcb37e`**，插件 JAR `c792abca…9ffd07a`，启动器 `666e52f5…338f68d`。本地桌面录制时为 `c7164f8` 加未提交修复，已随后提交为 `30288b0` 并推送 `.20261113.2` tag。真实 Windows/DMG 验证及发布已在 [run 43369092135](https://github.com/shubolab/opengoland/actions/runs/43369092135) 成功；不要把此前取消的 `.1` 当作通过证明。

## 发布校验器范围补齐

等待 `.13.2` CI 时，独立校验工具 `scripts/publish_tested_installers.py` 的 Git 输入集合检查报错。逐项核对发现：打包清单已经正确包含 `product/file-associations.json`，但该辅助校验器的预期集合漏列了它，并非产物中的源码有误。

已补齐辅助工具，并用临时真实 Git 仓库证明完整清单通过、漏掉或篡改文件关联清单均被拒绝；7 个发布校验器测试通过。修正后的工具还逐项验证了本机 `.13.2` App 的全部 **552 个输入字节**与固定提交 `30288b0` 一致。工具修正位于 tag 之后，不改变 `.13.2` 的固定源码、App、已录制画面或随后由 CI 生成的安装器。

## Git 局部提交的双端原生对照

已用两个隔离本地仓库，在官方 GoLand 2026.1.4 和 `.20261113.2` 独立 App 中分别实际执行：同一文件改两处 → 原生 Show Diff → 只勾第一处差异块 → 原生 Commit。两端都成功提交第一处中文/emoji 修改，并完整保留第二处中文修改。

随后通过 Git 读取提交对象、工作文件和剩余 diff，证明两端结果逐字节相同、暂存区为空；并非只根据成功通知判断。两应用正常退出，记录的已知 App/子进程 PID 均消失，启动器哈希未变。只有自建测试仓库被写入，没有设置远端或修改全局 Git 配置。

[对照记录](../../verification/git-partial-commit-native-20261113.json)包含原始样例、提交/工作区摘要和两张确切窗口截图。参考端 REA AX 因两个窗口位置相同而明确拒绝，另用确切 PID/window 的只读截图记录，交互与文本观察来自 CUA；没有把该失败当作 AX 成功。

该证据仅补充 macOS 的变更块级局部提交；同一块内按行拆分、显式 Git staging-area 模式、远端认证/同步、其他冲突和 Windows Git 交互仍待完成。TOOLS-02 与完整矩阵状态不变。

HTTP 和 Git 原生运行后的独立 App 另通过 `codesign --verify --deep --strict`，**15,139 个插件/内置工具文件摘要全部匹配**；552 个源码输入仍与 `.13.2` 固定提交一致。[运行后完整性记录](../../verification/product-20261113-2-post-native.json)不代替 CI 生成的 DMG 安装验收。本机只读查询仍显示 Developer Mode 禁用，没有修改系统权限，测试调试闭环继续开放。

## `.13.2` 最终发布核验

四个作业全部成功，安装器于 **2026-11-13T05:52:15Z** 公开。本轮只下载约 **12.3 MB** 的 SHA256SUMS 与两份 provenance；逐项核对 GitHub API digest、校验文件、producer 的安装器大小/哈希、固定 tag/source、两端 552 个输入和原始 XML 的全部测试身份。两端各 861 项 / 159 报告全部通过，Windows owner 包 8 项均有真实 pass 事件，Go race 未使用缓存结果，vet 及安装 smoke 成功。

实际已交付内置 HTTP 及其正文边界修复、模板/SQL/Dockerfile 高亮；中英文下载和 Release 说明已更新。[版本证据](../../verification/releases/0.1.0-preview.20261113.2.json)明确保留没有重复下载完整安装器、本地 GIF 录自同源码候选、平台交互与调试/签名边界。完整 36 × 2 矩阵仍未完成，goal 保持 active。
