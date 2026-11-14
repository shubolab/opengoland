# 真实运行演示 / Native workflow demo

历史演示 `native-go-workflow.gif` 来自 open Goland 实际运行的窗口。
它依次展示编辑器、程序输出、测试结果、断点和单步求值。每一步都实际执行，
将各阶段的窗口截图按顺序播放，并压缩空闲等待；它不是连续录屏。

录制环境为 macOS Apple silicon，界面语言为英语，产品是
`0.1.0-preview.20261110.4` 开发构建。录制时该版本尚未发布；仓库首页的
下载区说明当前公开安装版本。本演示使用组装后的独立应用，不是插件开发沙箱。

- 录制源码：`17ca02adf80000501dc9ec6a061d7931a0b9d037`。
- 486 个构建输入的摘要：`612dfa94bfa64b7352dea586c73901989b850bad90b391cf2ec61e2b412f793e`。
- 应用清单摘要：`74b5ebe9d405a6321c909f7b2aba532eea8b0e26a16dba6d5e0ba65373d1b157`。
- 示例源代码：[fixtures/go-workspace](../../fixtures/go-workspace)，Go 源码和模块文件保持不变。
- 图像来自指定 IDE 进程的单个窗口，通过 REA 获取；未采集其他应用的画面。
- 仅进行 GIF 所需的缩放、调色板和透明度转换，不添加虚构界面或过渡动画。
- 动图为 5 帧、1200 × 779、16.4 秒、445,753 字节，保留窗口圆角的透明边缘。
  原始画面摘要、捕获证据和生成物校验值见 [recording.json](recording.json)。

可以按以下步骤复现：

1. 打开 `fixtures/go-workspace`，再打开 `app/main.go`。
2. 点击顶部 **Run 'main.go'**，控制台显示中文问候、`Total: 37` 和退出码 0。
3. 打开 `catalog/catalog_test.go` 并运行，原生测试树显示 **6 tests passed**。
4. 返回主程序，在第 15 行设置断点，点击 **Debug 'main.go'**，查看 `cart` 和 `price`。
5. 按 **F8** 单步到第 16 行，在调试面板输入 `cart.Items[0].Name` 并按回车，结果为 `"Book"`。
6. 继续运行，程序正常结束；随后正常退出应用。

录制使用示例的临时副本及独立 IDE 配置。复制后的相对 Git 目录映射通过原生设置
移除，避免把临时目录误当成原仓库；没有改动原示例或用户其他项目。

截图证明这些具体操作在所述环境中完成，不表示全部 GoLand 功能或 Windows
桌面交互已经验收。当前功能边界见[中文 README](../../README.zh-CN.md)与
[调试控制记录](../verification/debug-session-controls.md)。

## HTTP 批量请求片段

`native-http-batch.gif` 录自 macOS arm64 上 `.20261113.3` 修正版开发候选，
使用 [fixtures/http-client](../../fixtures/http-client) 的真实 loopback Go 服务。
三个阶段依次展示 Services 中的中文 JSON 批量响应、单项变量错误后继续，
以及 Stop All 取消当前和排队请求。每帧 4 秒，共 12 秒，这是操作阶段截图，
并非连续录屏；没有添加模拟界面、标注或过渡动画。

原图由 REA 对 PID84677/window2269 捕获，交互由 CUA 完成。第一帧的 REA
可访问性读取存在 22 个子节点读取缺口，不能据此声称取得完整 AX 树；确切窗口截图
及 CUA 的请求/响应观察分别保留。[录制记录](http-batch-recording.json)绑定
566 个输入、插件 JAR、原始图片与 GIF 摘要；[桌面验收](../verification/http-batch-native-20261113.json)
另记录真实连接取消、活动移除、关闭项目、二进制保存及运行后签名检查。
公开安装器的状态以首页下载区为准。此前 `.13.2` 单请求 GIF 和记录保留不变。

## 历史 HTTP 单请求片段

`native-http-client.gif` 来自 macOS arm64 上 `0.1.0-preview.20261113.2` 开发候选的
真实窗口，使用 [fixtures/http-client](../../fixtures/http-client) 的 loopback Go 服务。
三个阶段分别是 POST 中文/emoji 请求体及响应、Stop 取消慢请求，以及原始 302 响应。
每张截图显示 4 秒，共 12 秒；这是操作阶段截图的顺序播放，不是连续录屏。

原图由 REA 对确切的已运行 IDE PID/window 捕获，UI 操作通过 Computer Use 完成。
仅缩放至 1200 × 857 并编码为 GIF，没有添加模拟界面。该候选包含参考端对照后的
请求体边界修复，原始中文请求发出 39 字节、服务器返回 104 字节。对应源摘要、截图哈希、原生/自动化验证边界见
[http-client-recording.json](http-client-recording.json) 和
[HTTP 执行记录](../verification/http-client-native-20261113.json)。
公开安装包是否包含该功能，以 README 下载区和绑定版本的发布记录为准。

## 构建标签、补全与撤销的补充片段

`native-build-context-completion.gif` 来自 `0.1.0-preview.20261110.6` 开发候选版在 macOS 27.2 arm64 上实际运行的窗口，录制源码是 `8c6ddaa6d66d727aa24730120f279a6885a64ba2`。它是四张操作阶段截图、16 秒、1200 × 779，并非连续录屏；保留了平台快捷键冲突提示和撤销后尚未完成代码的诊断。

它依次展示默认标签下测试通过、切换 `native_extra` 后另一项测试调试通过、接受补全并自动导入，以及一次撤销。没有可靠捕获候选列表，因此不展示或虚构 Lookup 帧。录制时候选版尚未发布，且打开被排除文件的语言服务通知问题仍未修复；不能据此认为全部功能或后续修复已经验收。

四个示例文件已放入 [fixtures/native-workflow](../../fixtures/native-workflow)，与录制临时副本中的对应文件逐字节一致。操作方法、临时测试快捷键和代码还原步骤见该目录的说明；系统、原始画面摘要、REA 证据与生成物信息见 [build-context-completion-recording.json](build-context-completion-recording.json)。原 `.4` 动图及其版本记录保持原样。

## 2026-11-14：Go 图标、外部依赖与无缓存测试

早期候选图 `native-go-workflow-20261114.gif` 来自独立 App 的实际窗口，
录制版本为 `0.1.0-preview.20261114.1`，macOS arm64，固定源码 `cac8501`。
三个阶段各 4 秒：默认程序运行并输出中文和 emoji、Cmd+B 跳到项目外本地依赖的
新 `Greeting` 定义、原生测试面板显示 1 passed 和退出码 0。
这是按操作顺序播放的截图，不是连续录屏，也没有新增调试验收结论。

[示例](../reference/cold-external-refresh-20261113-evidence/fixture)包含两个工作区
模块、一个回退模块和项目外的本地依赖。复制整个 fixture 目录，在 IDE 中打开
`project`，打开 `one/main.go` 后运行。外部文件里的 `Message` 改为 `Greeting`
时，调用方在不修改的情况下自动出现错误；只恢复外部文件后错误消失。
再次改名，输入 `Gree` 并执行 Basic 补全，可接受唯一的 `Greeting` 候选，
保存后运行、在调用处 Cmd+B 跳转。打开 `main_default_test.go`，在原生 Go Test
配置中填写 Go tool arguments `-count=1`，执行 `TestCompose`，确认实际命令和 PASS。
仅在自有副本中执行这些修改。

最终候选还实际检查了 Cmd+P 的 `<no parameters>` 提示、深浅主题中的最终图标，
以及正常退出后的进程回收与签名完整性。这些不是 GIF 中额外添加的画面。
[录制记录](native-go-workflow-20261114-recording.json)和
[原生验证](../reference/cold-external-refresh-20261113.zh-CN.md)
区分了本机 App 与云端安装包证据；完整 GoLand 对齐和 Windows 交互仍需继续验收。

## 2026-11-14 修正版：最终源码的原生演示

此前 C4 主图 `native-go-workflow-20261114-2.gif` 使用 **0.1.0-preview.20261114.2**
本机独立 App 的三张新截图，源码固定为 `07bb8eb35f48f11564bac5b076fa7752ff03466a`。
依次展示中文/emoji 的 Run exit 0、Cmd+B 跳到外部 `Greeting` 定义、原生 Test
以 `-count=1` 执行并显示 1 passed / package 0.587s / exit 0。
每帧4秒，共12秒；只进行等比缩放和GIF调色，未添加模拟界面。

示例与操作沿用上面的自有副本步骤。修正版另完成外部依赖改名自动报错/恢复、
Basic唯一候选补全、实际 `<no parameters>` 提示、深浅图标、正常退出与签名完整性检查。
[新录制记录](native-go-workflow-20261114-2-recording.json)绑定587个封存输入、原始截图和实际App；
[原生证据](../reference/cold-external-refresh-20261113.zh-CN.md)保留具体边界。
本机完整门禁969项/174报告通过，仍不能代替Windows交互或完整GoLand对齐。

较早的 `.14.1` 候选GIF和日期保持不变；该版本没有双平台安装器发布，Windows失败与修正过程见
[失败记录](../verification/windows-build-context-process-20261114/REPORT.zh-CN.md)。
公开安装包的状态以首页下载区为准，不能仅凭这段本机演示认定已经发布。


## C5 启动窄修：新的 Run、终端和原生测试演示

此前首页主图 `native-go-workflow-20261114-2-startup-fixed.gif` 使用 C7 的三张新原图，
源码 `07ae69f3b2bbc36f3221ff08e8fe9046f199b624`、版本 `.20261114.2`、macOS arm64。
依次是中文/emoji Run exit0、真实终端完成命令/参数补全后的实际测试、原生 Go Test 的无缓存结果。
每帧4秒、共12秒，只缩放和调色，没有模拟UI、鼠标或补全弹窗动画。

在自有 fixture 副本的 `project` 打开终端，`go tes` 唯一补成 `go test`；
输入 `go test -r` 可见 `-run` / `-race` 候选，接受 `-run`。
实际 `go version` 为 Go1.26.10 darwin/arm64，终端执行
`go test -run TestCompose -count=1 ./one` 成功，package0.450s。
原生 Go Test 配置设置 `-count=1`，实际 `go test -json -count=1 -run ^TestCompose$ .`
显示1passed/package0.344s/907ms/exit0；最初一次cached结果没有用来证明无缓存。

[新录制记录](native-go-workflow-20261114-2-startup-fixed-recording.json)与
[C7证据](../reference/cold-external-refresh-20261113-evidence/candidate7/native-summary.json)
绑定新源码587个输入、原图和退出检查。冷 `-version` 退出0，stdout是平台版本2026.1.4；
有WARN但没有ERROR/SEVERE。C7只验证本机startup/Run/当前macOS终端/nativeTest，
没有重做C4的外部改名或深浅主题，也不增加Windows、调试或完整矩阵验收。
C6/C4旧图和记录保持原源码，不改标；公开安装器仍以首页下载区为准。

## 2026-11-14：外部依赖目录恢复后的 C9 演示

新的首页主图 `native-go-workflow-20261114-3.gif` 来自固定源码
`643480a3d9d01c526210bdeba16df8431c1f4d75` 的本机独立 C9，版本
`0.1.0-preview.20261114.3`，macOS 27.2 arm64（build 26B123）。
三张真实截图依次展示恢复外部依赖后清错的编辑器、原生无缓存测试、中文与 emoji
程序输出，每帧4秒、共12秒、1200×857。它不是连续录屏，也没有展示整个目录
移走、重建过程；编码仅等比缩放和 GIF 调色，没有添加模拟 UI。

实际目录操作沿用[自有示例](../reference/cold-external-refresh-20261113-evidence/fixture)：
先只打开 `project/one/main.go`，将外部依赖整个根移走；等待原生缺失诊断。
原路径以新 inode 重建，并只定义 `Greeting` 时，调用方自动出现 `undefined Message`；
只恢复外部 `Message` 后自动清错。这些阶段的六张原图与磁盘/CLI 对照分别保存在
[根恢复记录](../reference/external-root-recovery-20261114.zh-CN.md)及
[C9 原生摘要](../reference/external-root-recovery-20261114-candidate9/native-summary.json)，
不能只凭主 GIF 宣称完整恢复过程已被录制。

随后原生 Go Test 的实际命令为
`go test -json -count=1 -run ^TestCompose$ .`，显示1 passed、840ms、
package 0.365s、exit0；Go Run 输出 `外部-v1|workspace-two|default|中文😀`，exit0。
[新录制记录](native-go-workflow-20261114-3-recording.json)绑定590个封存输入、
REA PID/window 身份、原图摘要、实际 ffmpeg 编码命令与三帧解码验证。
本机 App 使用默认内置 gopls、没有 trace override；操作后15个源文件与原根 inode
均恢复，正常退出后四个已采样自有 PID 不存在，关键产物与签名核验通过。

本机 C9 与云端安装器使用同份源码，但不声称两者字节完全相同。
公开版本与双平台安装器证据见首页下载区及
[14.3 发布记录](../verification/releases/0.1.0-preview.20261114.3.json)。
另一次同源码本机检查已发现已知问题：首次打开项目时本地 `replace` 依赖不存在，
后来创建完整依赖后，编辑器仍可能保留旧导入错误，即使实际 Go 命令已经成功。
这个场景的自动恢复尚未修好，目前没有验证过有效的绕行办法，见
[首次缺失依赖记录](../reference/initial-absent-dependency-20261114.zh-CN.md)。
它与本段已监视根恢复通过是不同场景。稳态深层新子树、Windows 交互和完整
GoLand 矩阵仍开放；此前 C7/C4 及更早的 GIF 和录制记录保持各自的原始来源与日期。
