# 本机 GoLand / PyCharm 参考基线

目标是独立、完整可用且尽可能还原 GoLand 的 Go IDE。以下资料用于确定行为与验收范围，不是可直接装入新产品的实现资源。

## 固定的参考安装

| 产品 | 本机位置 | 版本 / build |
| --- | --- | --- |
| GoLand | `/Users/wangshubo/Applications/GoLand.app` | `2026.1.4` / `261.26222.72` |
| PyCharm | `/Users/wangshubo/Applications/PyCharm.app` | `2026.1.4` / `261.26222.68` |

身份、文件与 JAR member 的 SHA-256 保存在 [installed-ides.json](installed-ides.json)。调查只读取上述两个 app bundle，没有读取用户配置、许可凭证、缓存或项目。

重新采集：

```sh
python3 scripts/inspect_reference.py
```

脚本用 `plistlib`、ZIP 和 XML 解析器收集结构化声明，不执行应用、不加载 JVM 类，也不反编译实现。插件使用 CDATA 内嵌模块描述符；脚本解析这些模块，避免只看顶层 `plugin.xml` 导致遗漏。当前选定范围包含 11 份 descriptor、463 条字面声明，含资源重复引用，不能当作 463 个独立功能。选定的 3 个平台 JAR 在两份产品中有 2 个字节完全一致，这只证明所选文件共享，不能推断整个平台或完整产品等价。

## 观察到的架构边界

- Go 插件标识为 `org.jetbrains.plugins.go`。描述符明确面向 IntelliJ IDEA Ultimate；这份本机插件只能作为参考，不能被当作独立产品已经具备的 Go 引擎。
- Go 的主要声明位于 `META-INF/plugin.xml` 内嵌的 `intellij.go.impl` 模块。可以观察到平台 parser、completion、refactor、inspection 等扩展；该范围没有建立“GoLand 本身使用 gopls”的证据。
- 三个运行配置类型分别为 Go application、Go test、Go remote debug。已有 gotest、gocheck、gobench、gofuzz producer 声明。
- 设置声明包括 GOROOT、GOPATH、Go Modules、Build Tags、Imports、Printf-like functions、Go linter、Go profiler。
- 调试动作声明包含 goroutine 过滤、线程切换、指针地址与类型显示、core dump 和 replay；profile 模块包含 CPU、memory、block、mutex 类型。
- UI 与交互应复用开源 IntelliJ Platform 的公开扩展与编辑器；选定 jar 里观察到商业插件类名不意味着其实现可直接复制或分发。产品构建须由构建系统明确列出真实输入，不把已安装 GoLand 换标当作独立实现。

## 证据强度

`installed-ides.json` 是普通脚本产生的本地静态调查记录，不伪造 REA Evidence ID。根调查另行保留 REA 的 canonical Evidence 和 Info.plist 观察。

本目录中的声明只表明参考包注册了相应功能候选，没有证明功能已在真实项目中执行成功。原版运行观察与新产品运行验收均需补证；验收矩阵中的 `unverified` 必须以实际结果更新，不能因类名、按钮或绿色编译而自动通过。

## 产品级验收

[acceptance-matrix.json](acceptance-matrix.json) 将桌面交互、工程、代码智能、运行、测试、调试、性能分析和辅助工具分开记录。该矩阵是当前选定调查范围的基线，不声明穷尽 GoLand 的所有扩展；不得仅因矩阵当前条目通过就宣布完整 GoLand 等价。每条都需要原版参考观察、新产品结果、输入工程与证据路径。

可以先用自己的固定 fixture，再补多 module `go.work`、本地 replace、build tags、CGO、泛型、表驱动子测试、HTTP server、goroutine、失败与取消场景。截图需要与焦点、键盘行为和真实程序输出结合，不能单独证明可用。

## 实现与验收补充记录

[GOWORK 自动工作区边界](go-gowork-auto-20261111.json)记录了原生应用的错误诊断，以及修复前后的真实 gopls 对照。修复后的四种工作区设置和五项环境控制共 9 项通过，签名引擎的独立六分支对照也通过。旧版的跨模块跳转本来就能成功，本记录修复的是 `auto` 引起的工作区诊断错误。新源码完整检查、实际桌面继承环境、新安装包与完整 GoLand 验收仍待完成。

[快照续接验证](continuation-20261111.json)单独绑定了新源码 `94d1b89` 的完整本机检查，以及旧候选 C 的跨模块改签名、运行和一次撤销、测试文件生成和一次撤销观察。两种源码身份不能混用；证据压缩文件及原字节校验值一并保留。该轮 Windows 后来因托管运行器失联结束，未上传报告和 EXE；macOS 的原始报告和 DMG 已核对，具体见下方安装检查记录。完整目标和验收矩阵继续开放。

[Windows 测试跨平台改造](windows-controls-portability-20261111.json)记录了 `36a838d` 的 796 项完整本机检查、Go race/vet，以及此前因 Unix 脚本跳过的 12 项测试改造。两个辅助程序的 Windows 交叉编译成功仍不是 Windows 执行证明。专用工作流方案已完成本机校验，但因当前 GitHub 授权缺少 workflow 权限而未推送；保留方案，改用已有双平台流程继续验证。实际 Windows 报告仍须核对精确测试身份、原始计数和所有跳过/失败，不能由本机结果或检查脚本通过代替。

[本地 SDK 原生续验与刷新修复](sdk-settings-refresh-20261111.json)记录了旧候选 C 通过系统目录选择添加 Go 1.27.3、真实运行与测试、恢复默认 SDK 和正常退出的过程。也复现了“命令已切换 SDK，Go 面板仍显示旧版本”的问题。源码 `8dfdbe0` 已补上保存设置后的刷新通知；原生新版的自动刷新仍待验证。

[新源码完整检查](sdk-settings-full-20261111.json)保留实际 797 项 Kotlin 测试的全部 143 份原始 XML、Gradle 日志、347 个 Go race 测试 PASS 事件、vet 和源码/产物摘要。全部检查通过，没有 Kotlin 跳过项。新源码仅调度一次双平台安装检查：[run 43049891202](https://github.com/shubolab/opengoland/actions/runs/43049891202)，当前结果仍待验收。

[上一轮安装检查终态](installer-ci-20261111.json)明确区分 Windows 运行器失联、macOS 构建报告、下载和磁盘映像校验。已校验的旧源码 DMG 尚未进行本机原生安装验收，不能代表新修复已在安装包中通过。`.6` 仍未公开发布。

[新版原生 SDK 自动刷新验证](sdk-settings-native-20261111.json)记录了源码 `8dfdbe0` 的独立应用：实际选择 Go 1.27.3 并保存后，Go 面板自动更新，REA 截图已保留。旧 C 候选在检查进程、文件占用和保留摘要后被替换，同一路径现指向新应用。第一次原生测试命中了 Go 缓存；后续配置窗口暂时无法在当前桌面操作，实际无缓存测试、运行、恢复 SDK 和正常退出仍待完成，不能写成全部通过。

[Windows 新报告与 SDK 取消问题](windows-sdk-cancel-20261112.json)：源码 `8dfdbe0` 的 797 项测试实际执行，0 跳过，原来 7 项失败已全部通过；仅 1 项新启用的 SDK 取消控制失败。取消与已退出 launcher 两个用例都耗时约 30.6 秒，需继续核实未及时回收的子进程和管道。断言文字不能证明误杀了无关进程，30 秒自然退出也不能当成及时清理通过；新 EXE 尚未生成。

旧源码 `94d1b89` 的临时 macOS ZIP 和 DMG 已在保留原始报告、日志与摘要后移除，见[安装检查中的清理记录](installer-ci-20261111.json)。旧记录中的本机路径不表示二进制仍可使用；该候选从未完成本机原生安装验收。最新已验证的 `.5` 安装包和应用、当前 `8dfdbe0` 应用及共用 SDK 均保留。
