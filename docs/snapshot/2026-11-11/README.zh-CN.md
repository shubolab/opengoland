# 2026-11-11 工作快照：open Goland

这是按用户“尽快收尾，后续从 snapshot 继续”的要求保存的交接记录。**完整目标没有完成，本轮停止继续扩展开发和桌面验收。** 此快照只增加文档和紧凑证据，不发布新的产品版本。

记录时间为北京时间 2026-11-11，机器状态记录见 [handoff-state.json](evidence/handoff-state.json)。

## 先看这几件事

1. 已经有独立原生应用和公开安装包，但还不能宣称是完整、完美的 GoLand 替代品。
2. 公开版本仍是 **0.1.0-preview.20261110.5**；**.6 没有发布，也没有创建 tag**。下一轮优先解决 Windows 的 7 项失败，再验证并发布两平台安装包。
3. 本轮真正验证的产品源码是 **`e6b7d7816f0d68395ec2a0b77ccf205e10554917`**，下文简称 **C**。本 snapshot 的提交在它之后，属于文档提交，不能把旧产物说成由新提交重新构建。
4. 测试 IDE 已正常退出。退出后重新检查，先前记录的 16 个进程身份均已消失；六个示例源码文件恢复为原始字节。
5. 历史测试产物此前累计清理约 95.97 GB，按文件大小计算；本次退出后又清理了约 292.54 MB 的索引和缓存。快照落盘时磁盘可用约 122.7 GB。不要重新制造每个用例一套 SDK/App 的副本。

## 最终要做的产品，没有缩小目标

产品叫 **open Goland**。目标是一个完整、可日常使用、尽可能还原本机 GoLand 2026.1.4 的独立 Go 桌面 IDE，不是网页壳，也不是只会打开文件的编辑器。

- **Kotlin + 开源 IntelliJ Platform**：桌面窗口、原生编辑器、项目、动作、撤销、工具窗口与平台插件系统。
- **本仓库的 Go 引擎、gopls、Delve**：工程任务、类型分析及变更规划、语言服务和调试。
- **LSP4IJ**：将语言服务与调试协议接到 IntelliJ 原生界面。
- 已安装 GoLand/PyCharm 仅作只读参考；不改其安装、配置、许可或项目，不复制其专有实现。
- IDE 本身无需账号，无计费、订阅、试用或付费推广；Git、GitHub、私有仓库等开发服务的认证保留。
- 每个真正交付的产品版本必须有实际 Windows `.exe`、macOS `.dmg`、校验值和安装说明。
- 保持准确的中英文 README，并使用真实原生运行画面制作示例 GIF。

完整约束见根目录 [AGENTS.md](../../../AGENTS.md)。位置统一用 UTF-16，必须正确处理未保存文档、版本、Undo、取消和项目释放；文件系统与子进程工作不得放在 EDT，只清理由本项目启动的进程。

## 已经有了什么

下面是已有实现的范围，不表示所有语言细节和所有桌面流程都验收通过。

| 部分 | 当前已有内容 | 仍需怎样看待 |
| --- | --- | --- |
| 独立工作台 | 原生窗口、编辑器、项目/SDK、工具窗口、终端和 Git 接入 | 已有独立分发，完整产品对齐仍开放 |
| Go 编辑 | 语法着色、补全、诊断、导航、提示、构建环境联动 | 不等于完整 Go PSI、所有语法/大项目/输入法行为已覆盖 |
| 导航与改代码 | 定义/类型/实现/引用、类型搜索、结构/层次；Rename、Change Signature、Extract/Inline 及预览/Undo | 各有支持边界，完整原生矩阵未结项 |
| 生成代码 | 测试、接口方法、构造函数、getter/setter、struct tag、JSON 类型等入口 | 骨架仍需要业务实现/断言；新 typed edit 的桌面闭环待验 |
| 运行与工程 | Go Build/Test/Debug 配置、原生输出与测试树、模块/工作区任务、覆盖率和性能结果入口 | 不能把工具协议测试当作所有原生交互已通过 |
| 调试 | 已有断点、栈帧、变量、求值、Watches、步进、Attach/Remote 等实现及历史验证 | Pause、取消、Stop/Detach、goroutine 和不同平台边界仍需继续 |
| 插件与中文 | 恢复原生 Marketplace/磁盘安装，保留版本、依赖、签名检查；提供匹配中文资源获取入口 | 不承诺所有 JetBrains 专有插件兼容，不能强装旧 232.* 包到 261 平台 |
| 文档与演示 | 中英文 README、真实原生 GIF、逐项验证记录与 REA 台账 | 当前 GIF 标注的是各自录制版本，不是本次 C 新流程的完整演示 |

具体使用和限制先读 [中文 README](../../../README.zh-CN.md)，再读它链接的 `docs/verification/` 文档。

## C 这次已经落到源码里的修复

提交：[`e6b7d78`](https://github.com/shubolab/opengoland/commit/e6b7d7816f0d68395ec2a0b77ccf205e10554917)，已在 `main` 推送。

1. **Go 工作区的 `GOWORK=auto`。** 签名变更规划按正常的父目录/overlay 工作区发现处理 `auto`；仍区分 `off`、空值、绝对路径，并拒绝不支持的相对配置。
2. **gopls 的环境适配。** 只在 gopls 命令边界把实际 CONSOLE 父环境中的精确 `auto` 改成“存在且为空”的值。项目原始设置不被篡改；初始化只处理显式项目覆盖，不在 EDT 读取控制台环境。Windows 的环境键匹配也做了处理。
3. **LSP 协议 API 对齐。** 编译优先使用实际 LSP4IJ 分发中的协议 JAR，避免用旧 SDK 协议 API 编译、运行时却加载新 API。适配了 LSP4J 1.0 的 Diagnostic 与 typed TextDocumentEdit，以及相关生产 codec 和原有测试。
4. **增加了针对性回归。** 4 项真实 gopls 工作区测试、5 项明确为单元控制的测试，以及签名变更的真实工作区/overlay 控制。没有把模拟控制说成真实 gopls 或桌面验收。

源码输入共 **506 个文件**，摘要为：

```text
3229d2b438af1a1e43184665e4893e81cfdead9e1431f595bf884a7cb1e2ccdc
```

完整输入清单已保存为 [source-inputs-C.json](evidence/source-inputs-C.json)。本次旧版本 gopls 的真实 RED 是 **AUTO 工作区诊断报错**；当时跨模块定义查询其实成功了，不能在续接时改写成“旧版本跳转失败”。

## 验证做到哪一步

| 证据 | 当前结果 | 不能据此声称什么 |
| --- | --- | --- |
| C 的本机完整 Gradle 检查 | 实际 `--rerun-tasks --no-build-cache test buildPlugin` 成功；143 XML、795 唯一测试，0 failure/error/skip，149 关键身份核对通过 | 不代表 Windows 也通过，不代表完整 GoLand 验收 |
| C 的 Go 检查 | 实际 `-race -count=1 -json ./...` 与 vet 成功；347 个测试 PASS 事件、11 个包 PASS；命令包 `[no test files]` 单独记录 | 不能把无测试文件的包写成失败或 Kotlin skip |
| 当前本地 App | C 装配和严格候选验封通过，715 个自有 class 与 prepared/instrumented 完整 map 一致，工具/平台签名检查有记录 | 不是已下载公开 DMG 的安装验收 |
| 云端 run 42915342968 | 已结束：macOS success、Windows failure、publish skipped，整体 failure | macOS job 绿不能替代原始报告和 native 安装验收 |
| Windows 原始报告 | 143 XML、795 唯一测试，**7 failure、0 error、12 skip**；构建在 EXE 生成之前停止 | 不能写 Windows 795 PASS，不能发布 .6 |

证据：[本机完整结果（gzip）](evidence/local-full-result.json.gz)、[候选验封](evidence/candidate-strict-postcheck.json)、[CI 终态](evidence/ci-terminal.json)、[Windows 摘要](evidence/windows-xml-summary.json)。完整小型失败 artifact 已保存为 [ZIP](evidence/windows-failure-artifact.zip)，避免远端临时 artifact 过期后丢失诊断依据。

### 本轮实际原生操作

本地 App：`build/product/open Goland.app`，版本 `.6`，源码 C。真实使用本仓库自带的 Go 1.26.10 SDK；Go 引擎由固定 Go 1.27.3 构建。

| 项目 | 已观察到的事实 | 状态与证据边界 |
| --- | --- | --- |
| 继承 `GOWORK=auto` | 单次启动传入 raw `auto`，项目 Go settings 未设置；Go 面板识别 `go.work` 和 api/app 两个 module | REA `ev_1043363b4c3b542e918a400199375dd8577f6c38211e151c7b5a1b5322c83f44`；AX 存在缺口，不能作全面否定搜索 |
| gopls 实际子进程 | 两个有精确归属的 gopls 进程都观察到 `GOWORK` 存在且为空，测试 sentinel 一致 | 仅保留两个授权 key；[环境证据](evidence/native-gopls-key-only-environment.json) |
| 跨模块跳转 | 从 app 的 `api.Call` 用原生跳转进入 api/api.go 的 Call 声明；CUA 观察光标 10:6 | REA 截图 `ev_187dd6bfedac73fcf9a9834776244f8b6531a0281121ea95eecc3d6de816b65d`；[记录](evidence/rea-cross-module.json.gz) |
| Run | CUA 屏幕观察到内置 Go 执行 `go run .`，输出 `12 12`，exit code 0 | 尚未补独立 REA 终态归档，因此不结完整 native case |
| Debug | 已设置第 13 行断点；一次工具栏点击没有得到可确认的调试会话，也未采到 dlv | **未验证**；不能判断成调试通过，也不能仅凭点击无变化判定产品故障 |
| 显式项目 `GOWORK=auto` | 未执行 | 待验证 |
| Change Signature 跨模块预览→应用→运行→一次 Undo | 未执行 | 待验证 |
| Generate Go Tests typed edit 预览→创建/编辑→一次 Undo | 未执行 | 待验证 |
| 原始 Go 子进程/Delve raw `auto` 对照、initialize.env 中 key 缺失 | 未完整采集；短命 Go 进程错过采样，没有实际 Delve 对照 | 待验证，不能由源码或单元测试替代 |

实际 REA 捕获也保存在 [工作区记录](evidence/rea-inherited-workspace.json.gz)中。归档只复制了约 2 MB 的必要记录，不复制 App/SDK。

### 启动与收尾时必须知道的细节

- 第一个 gate 在 App 启动前因“封存引用与展开 JSON 比较”不匹配而拒绝；新增 V2 使用封存引用解包后再比较，真正执行 gate 后通过。旧文件保持不变。
- V2 **只启动过一次 App**。随后 observer 错误导入旧 gate，未开始采样；单独启动修正后的 observer，没有重启 App。因此启动到 observer 开始之间有采样空档。
- observer 在 7200 秒上限结束时 App 仍存活。**不能把这份有界观察写成正常退出通过。** 用户要求收尾后，CUA 通过 Command-Q / Confirm Exit 正常退出；再用新鲜进程表核对，记录过的 16 个身份均消失。两个阶段的原记录分别保留。
- [退出后复核](evidence/native-post-quit-recheck.json)、[原 observer 结果](evidence/native-bounded-observer.json)、[observer 修正记录](evidence/native-observer-rebind.json)描述了这些边界；不声称穷尽整个生命周期的所有短命进程。
- 六个 fixture 原始文件 SHA 全部恢复，项目 `.idea/openidea-go.xml` 仍不存在，见 [恢复核对](evidence/fixture-restoration.json)。UI 操作中一次额外换行已立即撤销。
- 已正常关闭 REA native session；当前没有由本轮验收留着运行的 IDE。已清理该 profile 的 index/caches，保留配置、日志和 LocalHistory。

## Windows 的 7 项失败，下一轮先处理

| 问题 | 已证实 / 推断 / 未知 | 下一步 |
| --- | --- | --- |
| 4 项 GoGoworkGoplsTest | 原始 XML 证实：预期 URI 含明文中文/emoji，实际 URI 使用 UTF-8 百分号编码；本次两端盘符相同。精确 local range 相同，3 个跨模块结果与 OFF 空结果已有 wire 记录，但后续断言未执行完 | 统一产生 ASCII URI 并保留精确等式，或仔细审查现有真实路径身份草案；不能削弱数量、范围和 OFF 隔离断言 |
| 上述测试中 3 项额外 cleanup error | EMPTY/ABS/OFF 报 consumer 目录仍被占用；`ownedRemaining=[]` 不足以解释持锁者。记录有 shutdown 响应与 exit，不能说 reader 死锁已证实 | 等待真实自然退出/EOF，持续记录自己的后代，有限清理并回收；先关 Files.walk 再删目录。须实际 Windows 复现，不得忽略异常或靠无限等待 |
| GoRunBuildContextNativeTest | 日志已记录原生配置创建/注册/执行；监听器却用 Windows 两种分隔符的原始字符串比较 | 有 fixture-only 路径 normalize 草案，保留原生事件和真实 gutter 动作；不在 EDT 做 toRealPath I/O |
| GoLaunchedAdapterLifecycleTest | `onExit().get(8s)` 返回后 `process.isAlive` 仍 true，现有证据不足以判断真实退出与观察 API 分歧 | 同一 8 秒预算验证实际 `waitFor`，记录真实退出；若仍活着则继续修产品清理，不能归为“抖动” |
| GoRunBuildContextLoaderTest | worker 5 秒等待超时。源码存在取消先于第一次子进程采样、stop 仅看旧快照的具体缺口；缺少超时线程栈，尚未证实就是这次超时的原因 | 真实慢 child/继承 pipe 回归，验证取消、两个 reader、父子进程和独立 sentinel。不要只加 timeout 或 skip |

原始四项 URI 事实见 [windows-gowork-four-facts.json](evidence/windows-gowork-four-facts.json)。

### 未应用草案：保留了，但不要当成修复

[unapplied-proposals/](unapplied-proposals/) 保存源码审查和三个无损压缩的 diff（解压后审阅，避免原始 diff 上下文空行被 Markdown 文件的空白检查改写）。它们**没有应用到 main，没有编译、没有 RED/GREEN，也没有实际 Windows 验证**。

- `fixture-only.diff.gz`：gutter 路径比较和 adapter 退出 oracle 的草案。
- `loader-production-v1.diff.gz`：stop 前重采样后代，同时包含 loop 采样前移。后续讨论倾向更小的“仅 stop 重采样”V2，但 **V2 和回归代码尚未落盘**。
- `gowork-uri-identity-v1.diff.gz`：使用 `toRealPath` 身份比较的初稿；随后审查倾向 ASCII URI 的更简单处理，但 **ASCII 版以及完整生命周期修复没有落盘**。
- Loader 回归设计可使用已有私有 execute/checkCurrent 入口：在首次采样前用 latch 卡住，真实 helper 再创建继承 stdout/stderr 的慢 child，然后取消；要求原有预算内 worker、两个 reader 与自有进程结束，独立 sentinel 仍活着。这里只是设计，不能写成已实现测试。

## 还未完成的大片工作

当前 [完整验收矩阵](../../reference/acceptance-matrix.json)有 36 项要求，每项分“参考应用真实行为”和“重建产品”两侧；**36 × 2 当前都还是 unverified**。这是缺少整项结项证据，**不等于 36 项都没有实现**。矩阵自身也说明它是选定功能义务，不是穷尽 GoLand 全部行为的清单。[本次状态副本](evidence/acceptance-status.json)便于后续比较。

优先仍包括：

- 本轮多模块/环境/签名变更/生成测试原生闭环，以及 Windows 上对应真实行为。
- 添加本地 SDK 的原生目录选择、准备/下载的取消，以及测试调试的准备和取消。
- Pause、Stop/Detach、Attach/Remote、不同停止时机、goroutine/系统调用帧等调试边界；不要把某次普通断点通过当成全部通过。
- 构建标签/SDK/环境改变后各项提示和动作的自然恢复；自动 documentHighlight 仍有独立未知项。
- 编辑体验、Smart Enter、完整语言/PSI 能力、性能、输入法、大型项目和更多真实工程环境的比较与验收。
- Windows 与 macOS 各自实际产品的安装、启动、升级/卸载、离线依赖、插件/中文和完整日常流程；重新验当前源码，不能借历史版本拼出通过。
- 新版真正验证完成后发布双平台安装包，并更新准确说明与确有必要刷新的真实演示 GIF。

## 发布状态与后续顺序

公开 `.5` 是 [GitHub 预发布版本](https://github.com/shubolab/opengoland/releases/tag/v0.1.0-preview.20261110.5)，有真实 Windows EXE、macOS arm64 DMG、SHA256SUMS 及两平台 provenance。它是保留的可下载版本；`releases/latest` API 对只有预发布版本的情况可能返回 404，不能据此断言 `.5` 不存在。

本轮 [run 42915342968](https://github.com/shubolab/opengoland/actions/runs/42915342968)只执行过一次 `publish=false` 的 workflow_dispatch，源码 C。Windows 在测试阶段失败，**没有新 EXE**。macOS job 成功，有 artifact id `13811243263`，ZIP 大小 939,445,566 字节，API 摘要为 `e3e1aee7533a62180c562c7dcb6ec8e435801cf16ef3d5bfbf8a22796f01e3ae`；为控制磁盘未下载或原生安装验收，不把它当成已验证发行物。CI artifact 会过期，按实际 API 状态处理。

下一轮建议顺序：

1. 读本快照、AGENTS、矩阵、Windows 原始失败和未应用 diff；重新核对 `git status`、磁盘、已有进程、远端状态。先修真正问题并补有区分力的回归，禁止靠跳过/放宽比较变绿。
2. 形成新的干净源码提交；执行要求的 Gradle、Go race/vet 和必要的专项验证，记录新源码与工具身份。
3. 只构建一份新候选，复用 pinned SDK/工具；按归属和打开文件检查退休旧候选，保留最新已验证公开 App/DMG。所有位置/Undo/取消/释放约束继续有效。
4. 用新候选补完上述 native cases；两个平台都用实际产品/安装器验收。已有 C 的证据只用于比较，不冒充新产物通过。
5. 对新源码仅调度一次不发布的双平台安装检查；修复、验证后才做真正发布。已有 `publish-tested-installers.yml` 支持提升已测试 Windows artifact，但需要正确 tag、draft 和同一 DMG 的原生证明。先读本机 `.tools/gowork-release-route-readonly-20261111/PLAN.zh-CN.md` 和工作流，不能同时 tag push + dispatch 制造重复构建。
6. 更新 release 说明、中英文 README 和必要的真实 GIF，继续逐项推进完整目标。不得因为一个里程碑通过就把 goal 标 complete。

## 本机续接材料与磁盘纪律

下面是同一台电脑上的保留路径；其中 `.tools/`、`build/` 被忽略，**新 clone 不会自带它们**。核心结果已复制到本 snapshot，完整本地原始材料仍留着。

| 保留路径 | 用途 |
| --- | --- |
| `build/product/open Goland.app` | 当前唯一 C 候选；manifest SHA `8a446d850a5ab06d713a823db07960dfb1ec3d44d3b63d9e8dc51161880dafe0` |
| `build/public-plugin-install-vnz28s86/open Goland.app` | 最新已验证公开 .5 安装副本 |
| `.tools/public-v5-release-audit/anonymous-actual-attempt2/` | .5 DMG；SHA `24fbb2509b757a33e9e4bbaa1cdb144d347c4f06cfe14cbf5b3541e29da7a7ce` |
| `.tools/platform/idea-2026.1.4/IntelliJ IDEA.app` | 固定开源平台 SDK；不要拿用户的商业 IDE 替代 |
| `.tools/go-tools-cache/`、`.tools/release-tools/` | 共用构建器、SDK 与固定 gopls/Delve；不要每个 worktree 重建副本 |
| `.tools/root-gowork-full-checks-v3-20261111/` | C 的完整本机检查和绑定 |
| `.tools/gowork-native-plan-20261111/` | fixture、V1/V2 helper、候选验封、REA 和本轮原生记录 |
| `.tools/excluded-inlay-native-plan/runtime/profile/` | 已关闭的隔离 IDE 配置；index/caches 已删除，配置/日志/LocalHistory 保留 |
| `.tools/next-v6-native-plan/runtime/profile/{go-cache,go-mod-cache,go-path}` | 本轮复用的 Go 缓存；可能影响离线验收，不盲删 |

**不要直接重跑旧 launch 脚本。** 它有单次 launch 输出防护，且 source gate 要求干净 HEAD 为 C。snapshot 文档提交后 HEAD 已变化；未来修改源码更必须新建正确绑定和验证记录，不能篡改旧证明来通过 gate。

每次大构建前查空间，至少留 20 GiB 余量；继续采用“一份当前候选 + 最新已验证公开安装包/应用 + 共用 SDK”。历史二进制已删除的事实有 receipt，旧日志中的绝对路径不代表旧 App 仍存在。

## 证据如何带走

[copied-evidence-manifest.json](evidence/copied-evidence-manifest.json)记录每份复制材料的原位置、原字节 SHA、归档 SHA 与 gzip 编码。压缩记录可以解压后逐字节校验。机器可读当前状态见 [handoff-state.json](evidence/handoff-state.json)。

这些证据保留观察范围和失败，不补写不存在的执行结果。恢复工作时以当前仓库、实际进程/安装器和 GitHub 状态为准。
