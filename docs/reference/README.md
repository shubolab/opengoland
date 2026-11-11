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

[快照续接验证](continuation-20261111.json)单独绑定了新源码 `94d1b89` 的完整本机检查，以及旧候选 C 的跨模块改签名、运行和一次撤销、测试文件生成和一次撤销观察。两种源码身份不能混用；证据压缩文件及原字节校验值一并保留。新一轮 Windows/macOS 安装检查仍在进行，完整目标和验收矩阵继续开放。

[Windows 测试跨平台改造](windows-controls-portability-20261111.json)记录了 `36a838d` 的 796 项完整本机检查、Go race/vet，以及此前因 Unix 脚本跳过的 12 项测试改造。两个辅助程序的 Windows 交叉编译成功仍不是 Windows 执行证明。专用工作流方案已完成本机校验，但因当前 GitHub 授权缺少 workflow 权限而未推送；保留方案，改用已有双平台流程继续验证。实际 Windows 报告仍须核对精确测试身份、原始计数和所有跳过/失败，不能由本机结果或检查脚本通过代替。
