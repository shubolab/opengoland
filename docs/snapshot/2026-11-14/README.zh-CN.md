# 2026-11-14 续接快照

完整目标仍为 **active**：继续建设可日常使用、尽可能还原 GoLand 2026.1.4 的独立原生 Go IDE。工作台使用 Kotlin / 开源 IntelliJ Platform，语言及工程服务使用 Go engine、gopls、Delve。IDE 账号、计费、订阅和试用门槛保持移除，开发工具认证保留。

**当前公开安装版仍为 [.20261113.3](https://github.com/shubolab/opengoland/releases/tag/v0.1.0-preview.20261113.3)。`.20261114.1` 未发布；`.20261114.2` 的 C4 本机完整门禁及原生复验通过，但 CI 的 Mac 成功、Windows 安装后启动日志门禁失败，当前不能发布。** `.14.2` 尚未创建 tag 或公开 Release。本次提交包含窄修，16项本机回归通过，完整包和新CI尚未运行，没有新producer。本机原生通过不等于新 EXE/DMG 已交付。

## 本轮做成了什么

用户指出文件树、标签页和多个侧栏复用蓝底 `GO` 方块，偏离原版。已改为透明背景的蓝色 Gopher：源码、测试、`go.mod`、`go.work` 分别显示相应图形或角标；测试角标最终为红绿方向标记，侧栏按依赖、覆盖率、性能分析和检查等用途显示功能图标。图形是独立绘制的 Gopher 设计改编，署名随包提供。最终源码的深色和 Islands Light 原生窗口均已检查，不能再用中间版本的烧瓶角标截图代替最终效果。

同时修复项目外本地 `replace` 依赖的自动刷新。实测原问题不只在文件系统：gopls 根本没有注册该外部 `.go` 文件的监视范围。现在按打开的 package、所选 SDK/环境和构建标签发现实际依赖，使用私有 dirty overlay 与磁盘检查，安装有界监视范围，并在关闭或替换时释放。

最后修复零参数函数补全后的参数提示异常。LSP4IJ 的可选参数列表为空时沿用既有空列表呈现，只修改一行公开源码。4 项实际 handler 回归先复现旧 NPE，再验证修复；加上初始化/运行封存检查共 9/9 通过。最终补丁是 21 份来源、42 个类，旧 20 份来源和 41 个类逐字节不变。

## C1 本机候选 candidate4（本机通过，未发布）

固定源码 **`cac85013896b0508d88dab9f8c42d5bbc388fec0`** 已推送，版本/tag 为 `0.1.0-preview.20261114.1` / `v0.1.0-preview.20261114.1`。586 个构建输入逐项与该提交匹配，摘要为 `59dd547a00eecb7f06cfd53255561b80b08baa3fb69fa4d060d04a86f73f4c02`；engine 元数据同为此提交，`modified=false`。

完整本地门禁 **966 项 Kotlin 测试、173 份 XML，零失败、错误和跳过**；86 项 Python 检查通过。Go engine 本轮没有改源码；固定 Go 1.27.3 的无缓存 race（12 个包）与 vet 已通过。最初错误地显式设置 `GOWORK=off` 的失败也保留，未改变生产语义来获得通过。

实际使用全新隔离配置，只先打开调用方 `main.go`：

1. 从未在 IDE 打开的外部函数 `Message` 改为 `Greeting`，调用方字节不变，自动显示 undefined；只恢复外部函数后错误自动清除，没有手动 Reload 或重启 gopls。
2. 再次外部改名，Basic 将 `Gree` 自动补成唯一的 `Greeting`。未保留补全弹窗，不能声称已验收 popup 外观。Cmd+P 实际显示 `<no parameters>`，未再发生旧 NPE。
3. 保存后 Run 输出 `外部-v1|workspace-two|default|中文😀`、exit 0；Cmd+B 跳到项目外的 `Greeting` 定义。
4. 原生 Go Test 配置填写 `-count=1`，实际命令带该参数，显示 1 passed / PASS / package 0.689s / exit 0，未使用结果缓存。
5. 最终深浅主题图标均已核对。正常退出后四个已知 PID 消失；整个本次日志无 SEVERE/ERROR，签名结构检查 exit 0。586 输入、manifest、插件和启动器摘要不变，自有样例的 15 个源文件已恢复。

[详细原生记录与逐阶段证据](../../reference/cold-external-refresh-20261113.zh-CN.md)区分失败的 candidate1/2/3 与最终 candidate4。中间门禁 932/959/962 项各自绑定不同阶段，不能混成最终测试结果。

[新版真实运行 GIF](../../demo/native-go-workflow-20261114.gif)由 Run、外部定义跳转、无缓存测试三张实际窗口截图组成，共 12 秒；[录制记录](../../demo/native-go-workflow-20261114-recording.json)保留原始 SHA、REA 身份和版本。它不是连续录屏，也不新增调试验收结论。中英文 README 已将此图作为主要示例，历史调试片段保留原版本说明。

## Windows 失败后的窄修与发布继续点

[来源43517336656](https://github.com/shubolab/opengoland/actions/runs/43517336656) 的 C1 Mac attempt1 已完成：173 份 XML / 966 项全部通过、586 输入匹配，实际 DMG 为 1,023,853,737 字节，SHA `a0710f86a4ff1439f39332498a61ee7ee53ca9b9e0d5e59711857c8f88061acd`。单份 Mac ZIP已下载并核对 API SHA，不将这个单平台成功写为双平台交付。

Windows attempt1 的 hosted runner 于 23:18:32Z 失联，原日志不可取，原因未知。一次 failed-only 重跑后，attempt2 job `132233144372` 提供了真实失败日志：**966 项、2 失败、零错误和跳过，没有 EXE**。一个是 VFS/native 路径字符串斜杠比较导致误判，原始内容事件实际已发生；另一个是生产 metadata loader 正常收尾时从 `process.descendants()` 拿到当前测试 JVM，尝试终止它，被 JDK 拒绝。不能把后一问题断言为第一次 runner 失联的原因。

[失败记录与两份原始 XML](../../verification/windows-build-context-process-20261114/REPORT.zh-CN.md)保存确切绑定。Mac 原成功 job 被 attempt2 API 复用成别名，不是第二次执行；原 artifact `14083677862` 保持不变。冗余 tag run `43523430835` 已精确取消。原 C1 tag `v0.1.0-preview.20261114.1` 不移动，也不再重跑它来掩盖源码缺陷。

当前窄修只有三个源文件：

- 冷 VFS 测试保留真实内容事件断言，用 `Files.isSameFile` 比较实际文件身份。
- Windows metadata 命令复用 Go engine 既有的原子 Job 所有者，保留选定 SDK 的环境、目录和参数；删除后代快照清理。仅终止本次直接启动的 owner，由 Job 回收真实后代。清理异常保留原始失败。
- 新增真实进程控制：正常 launcher 退出后遗留子进程、首次循环检查前取消、独立 sentinel、私有环境/工作目录/UTF-8/非零退出。没有改 Go engine 源码。

五类针对性检查先通过38项，但 C3 `e6264e4` 的本机完整门禁随后为 **969 项 / 174 报告，1 失败**：新增 orphan 夹具在命令返回后读取可选进程身份信息，遇到 `isAlive=true`、command缺失。具体OS快照原因未知，不能据此定性生产回归。没有生成App；root主动取消同源云端 `43532637816`，两端为cancelled，不是云端测试失败，未创建14.2 tag。完整原XML和来源见[本机夹具失败记录](../../verification/windows-build-context-process-20261114/REPORT.zh-CN.md)。

夹具已收紧为：父进程发布PID记录后等release；第二次检查中先验证父子都存活、记录可执行文件和出生身份，再允许父正常退出；随后只断言已捕获句柄回收，独立sentinel继续运行。清理复用已知身份，未知活进程不冒险终止，不跳过用例或删除断言。修改后的五类针对性检查再次 **38/38通过、零跳过**。首轮新夹具的Unix PTY换行预期失败也单独保留，生产输出解码没有改变。

## C4 / candidate6（本机通过，Windows 安装后启动检查失败）

固定提交 **`07bb8eb35f48f11564bac5b076fa7752ff03466a`** 已推送。587个输入摘要
`9303eee51d0cf4d6efab26b4d5207d83b8e08dfca09a9fcfe690850c90b3bd3d`
逐项匹配Git；engine同提交、`modified=false`。完整打包成功：**969项 /174报告，零失败、错误、跳过**。

candidate6 使用全新隔离配置，先仅打开调用方。两次外部改名均自动被感知，恢复外部文件后自动清错；
Basic将Gree补成唯一Greeting，Cmd+P显示 `<no parameters>`，Run与外部定义跳转均成功。
原生Go Test配置实际带 `-count=1`，1 passed、package0.587s、exit0。最终深浅主题图标均实机检查。
正常退出后四个已知PID消失，整个本次日志无SEVERE/ERROR，签名结构检查exit0；587输入及产品摘要不变，
15个自有源文件全部恢复。记录范围仍限于这些具体操作和已采样身份。

[新的14.2真实GIF](../../demo/native-go-workflow-20261114-2.gif)及
[录制记录](../../demo/native-go-workflow-20261114-2-recording.json)使用本次三张新原图；
旧14.1图不改标。中英文README主图已换为14.2，下载区仍指向公开13.3。

本轮 [producer43536268282](https://github.com/shubolab/opengoland/actions/runs/43536268282)
于00:45:03Z以C4、14.2、`publish=false`启动，attempt1：Windows job `132265950724`，
Mac job `132265950662`。两端已结束：**Mac success，Windows failure，整个 run 为 failure，publish skipped**。
Windows 已通过 Gradle 门禁、创建 EXE 并完成安装。首次真实 `SEVERE` 出现在安装副本的 **`-version` 进程**，
后续 GUI 烟测检查共享启动日志时立即拒绝；不能写成已经证明 GUI 进程本身触发该错误。
因此这轮 Windows 安装验收未通过，生成 EXE 与本机 C6 成功都不构成双平台交付。

当前观察到的错误链经过 `GoTerminalCompletionDefaults.applyOnce:38` 与
`ApplicationInitializedListener:48`，触发 `TerminalEngine` enum 的 `<clinit>`，并沿
`TerminalBundle`、`LocalizationStateService` 访问 `FeatureUsageSettingsEvents`。
这里记录的是安装后版本查询/启动阶段的真实错误，不推断 GUI 一定崩溃。
[原始启动日志与局部 ZIP 取证](../../verification/windows-build-context-process-20261114/c4-installed-startup/README.md)已落盘，保留精确调用链、CRC/SHA 和整包未验证的边界。

当前窄修在迁移条件确认后、首次访问 `TerminalOptionsProvider.instance` 前，
先调用公开 `DynamicBundle.getLocale()` 做只读语言状态初始化；现有3类终端测试 **16项通过，零失败、错误和跳过**。
窄修随本次提交保存，新source由 Git 提交确定；完整打包、新Windows安装验证和新producer尚未运行，不能写成已消除该SEVERE。

接下来对新源码完成完整门禁、原生检查与新 CI；
两端成功、真实安装器和原始证据核对后，才进入私有草稿审计与公开发布。当前没有新的 producer，
`.14.2` 未创建 tag 或公开；C4 产物不能推广，既有 `*-release14-2-final-*` 工具仍固定该 C4/run，保持未应用。
C3取消的小型证据、C1失败产物和本机 C6 画面分别保留，不复用成新的 CI 安装验收。
旧14.1/C3工具继续归档，历史 GIF 的版本、日期与源码不改标。

## 完整目标还剩什么

完整矩阵仍有 **36 条 reference_runtime、36 条 reconstruction 为 unverified**；本轮只补充 PROJECT-02、LANG-02、LANG-04 的局部证据。继续验证真实工程、环境/构建标签、重构生成、终端/Git/插件/中文界面和双平台交互。Windows 交互与本机 macOS Developer Mode 调试闭环尚未完成。整个外部根删除重建、深层新增目录和资源上限之外的工程也不能由本次已有文件改名验证代替。

安装启动检查、本机特定操作通过和完整产品对齐是不同证据。不要因本次图标与刷新修复发布而将 goal 标记完成。

## 本机资源

C1 candidate4 已从 canonical 路径原地移动到 `build/product-verified-20261114.1/open Goland.app`，未再复制一份；C3 candidate5 完整门禁失败，未生成 App。已关闭的 C6 经两次无打开句柄检查后，原地移动到 `build/product-verified-c4-20261114.2/open Goland.app`，没有复制；记录在 `.tools/continuation-20261113/workspace-consistency/runtime/candidate6-relocated.json`。canonical 当前尚无新的启动修复候选 App，待新源码组装；C4 验证应用、C1/13.3 应用和共享 SDK 继续保留。`build/product-verified-20261113.3/open Goland.app` 暂保留，待新安装包公开并接受后退役。两个已结束 candidate3/4 的 system 索引已清理，实际可用空间净增加约 0.54 GiB；81 份日志和配置保留。[本次缓存退役记录](../../verification/generated-caches-retired-20261114.json)保留两次占用检查与前后容量。

此前已清理两份旧生成 App 和 candidate1/2 的已结束配置，实测净回收 **3.83 GiB**；[退役记录](../../verification/generated-artifacts-retired-20261114.json)保存身份、占用检查与历史路径说明。更早三套 profile 清理回收约 1.64 GiB，两轮不能重复计算。本次重测约 **54 GiB 可用**，大下载或构建前仍需重测并保留至少 20 GiB。

共享 SDK/工具、必要缓存、worktrees、其他项目、参考应用和公开 Release 资产均保留。85 → 64 GiB 的历史下降缺少分目录基线，原因仍未知。没有修改参考应用、用户原配置、许可或系统调试权限。

前序完整交付与 HTTP/Git 记录见 [11 月 13 日快照](../2026-11-13/README.zh-CN.md)；原始工作记录在 `.tools/continuation-20261113/`，profile、完整日志和 AX 仅留在 ignored 工作区，不提交。
