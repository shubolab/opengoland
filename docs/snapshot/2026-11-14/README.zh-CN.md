# 2026-11-14 续接快照

完整目标仍为 **active**：继续建设可日常使用、尽可能还原 GoLand 2026.1.4 的独立原生 Go IDE。工作台使用 Kotlin / 开源 IntelliJ Platform，语言及工程服务使用 Go engine、gopls、Delve。IDE 账号、计费、订阅和试用门槛保持移除，开发工具认证保留。

**当前公开安装版仍为 [.20261113.3](https://github.com/shubolab/opengoland/releases/tag/v0.1.0-preview.20261113.3)。本轮 `.20261114.1` 正在检查双平台安装包，尚未公开。** 本机原生通过不等于新 EXE/DMG 已交付。

## 本轮做成了什么

用户指出文件树、标签页和多个侧栏复用蓝底 `GO` 方块，偏离原版。已改为透明背景的蓝色 Gopher：源码、测试、`go.mod`、`go.work` 分别显示相应图形或角标；测试角标最终为红绿方向标记，侧栏按依赖、覆盖率、性能分析和检查等用途显示功能图标。图形是独立绘制的 Gopher 设计改编，署名随包提供。最终源码的深色和 Islands Light 原生窗口均已检查，不能再用中间版本的烧瓶角标截图代替最终效果。

同时修复项目外本地 `replace` 依赖的自动刷新。实测原问题不只在文件系统：gopls 根本没有注册该外部 `.go` 文件的监视范围。现在按打开的 package、所选 SDK/环境和构建标签发现实际依赖，使用私有 dirty overlay 与磁盘检查，安装有界监视范围，并在关闭或替换时释放。

最后修复零参数函数补全后的参数提示异常。LSP4IJ 的可选参数列表为空时沿用既有空列表呈现，只修改一行公开源码。4 项实际 handler 回归先复现旧 NPE，再验证修复；加上初始化/运行封存检查共 9/9 通过。最终补丁是 21 份来源、42 个类，旧 20 份来源和 41 个类逐字节不变。

## 最终本机候选 candidate4

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

## 安装包发布继续点

构建来源为 [manual producer 43517336656](https://github.com/shubolab/opengoland/actions/runs/43517336656)，固定上述源码、版本，`publish=false`。macOS 实际构建、安装、签名和无缓存检查已通过：原始 173 份 XML / 966 项均通过，586 输入逐项匹配 C1；实际 DMG 为 1,023,853,737 字节，SHA `a0710f86a4ff1439f39332498a61ee7ee53ca9b9e0d5e59711857c8f88061acd`。

attempt1 的 Windows hosted runner 于 23:18:32Z 失去与 GitHub 服务器的通信，GitHub 将 job 判为失败；主步骤仍显示 in_progress、后续保留产物步骤未执行，原日志下载返回 BlobNotFound，没有具体测试失败证据。失联原因仍未知，不能断言产品测试失败，也不能断言只是平台瞬断。root 据此仅重跑一次 failed jobs，同一 run 的 attempt2、新 Windows job `132233144372` 正在运行。Mac 成功结果复用为 alias job `132233146893`，其原始时间和每个 step 与 attempt1 完全相同，实际 Mac artifact 仍为 `14083677862`；没有第二次 Mac 构建。失败记录和原始 annotation 已保留在 ignored `runtime/release14-1-build/attempt-1/`。

待 Windows 最终通过、整体 run completed/success 后，再提取真实 EXE、DMG 和原始 provenance，核对源码、原始 XML、race、安装结果和五个资产摘要，先私有草稿审计，最后公开。已取回一份经过 API SHA 校验的 Mac ZIP，约 0.9 GiB；Windows 仅在实际成功后下载。若再次失联，先排查资源/构建原因，不盲目第三次重试。公开前首页下载区继续指向 `.13.3`。

推送新 tag 自动触发的冗余 [run 43523430835](https://github.com/shubolab/opengoland/actions/runs/43523430835)已精确识别并取消，未影响 manual producer。旧 `publish-tested-installers.yml` 只接受较早的平铺 Mac 证据，本次 CI 使用嵌套 cohort，因此改为直接复用两个真实 CI artifact，保留证据布局，不伪造 `native-observations.json`，也不把本机 App4 冒充 CI 安装副本。组装和独立审计辅助脚本位于 ignored `.tools/continuation-20261113/`。

公开后补发布验证 JSON、下载入口、双语 Release 正文并提交推送文档；随后清理已结束的候选索引、冗余下载 ZIP 和旧 App，保留最新验证应用、安装器与紧凑证据。

## 完整目标还剩什么

完整矩阵仍有 **36 条 reference_runtime、36 条 reconstruction 为 unverified**；本轮只补充 PROJECT-02、LANG-02、LANG-04 的局部证据。继续验证真实工程、环境/构建标签、重构生成、终端/Git/插件/中文界面和双平台交互。Windows 交互与本机 macOS Developer Mode 调试闭环尚未完成。整个外部根删除重建、深层新增目录和资源上限之外的工程也不能由本次已有文件改名验证代替。

安装启动检查、本机特定操作通过和完整产品对齐是不同证据。不要因本次图标与刷新修复发布而将 goal 标记完成。

## 本机资源

当前 canonical `build/product/open Goland.app` 是已通过上述本机检查的 candidate4。`build/product-verified-20261113.3/open Goland.app` 暂保留，待新安装包公开并接受后退役。两个已结束 candidate3/4 的 system 索引已清理，实际可用空间净增加约 0.54 GiB；81 份日志和配置保留。[本次缓存退役记录](../../verification/generated-caches-retired-20261114.json)保留两次占用检查与前后容量。

此前已清理两份旧生成 App 和 candidate1/2 的已结束配置，实测净回收 **3.83 GiB**；[退役记录](../../verification/generated-artifacts-retired-20261114.json)保存身份、占用检查与历史路径说明。更早三套 profile 清理回收约 1.64 GiB，两轮不能重复计算。当前约 **62 GiB 可用**，大下载或构建前仍需重测并保留至少 20 GiB。

共享 SDK/工具、必要缓存、worktrees、其他项目、参考应用和公开 Release 资产均保留。85 → 64 GiB 的历史下降缺少分目录基线，原因仍未知。没有修改参考应用、用户原配置、许可或系统调试权限。

前序完整交付与 HTTP/Git 记录见 [11 月 13 日快照](../2026-11-13/README.zh-CN.md)；原始工作记录在 `.tools/continuation-20261113/`，profile、完整日志和 AX 仅留在 ignored 工作区，不提交。
