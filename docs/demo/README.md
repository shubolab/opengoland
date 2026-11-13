# 真实运行演示 / Native workflow demo

README 中的 `native-go-workflow.gif` 来自 open Goland 实际运行的窗口。
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
