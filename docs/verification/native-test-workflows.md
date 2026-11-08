# 原生测试、Benchmark 与 Fuzz 工作流 — 2026-11-09

本轮实现复用 IntelliJ Platform 原生运行配置、编辑器 gutter、测试结果树和调试器；Go 工具链执行实际测试。下文区分源码实现、自动化实测和待完成的独立桌面验收。**TEST03 的完整 GoLand 对齐仍未关闭。**

## 公开参考与实现范围

GoLand 2026.1 的公开文档将 `gotest`、`gocheck`、`gobench`、`go test -fuzz` 放在同一 Go Test 配置的 Test framework 下，并提供 Directory、Package、File 测试范围和分段正则 Pattern。本轮实现其中的 `gotest`、`gobench`、`go test -fuzz` 三种模式；完整范围选择器和 `gocheck` 仍待实现。[Go Test 配置文档](https://www.jetbrains.com/help/go/2026.1/go-test.html)

公开文档还声明测试 gutter 运行、临时配置保存、Fuzz 失败目录链接和 corpus 文件 gutter 重跑。这些声明用于确定交互目标；**不是对已安装 GoLand 2026.1.4 的运行观察**，也不证明我们的独立应用已经完成相同桌面流程。[Run tests 文档](https://www.jetbrains.com/help/go/2026.1/performing-tests.html)

## 原生配置与执行语义

只有一个 Go Test 配置类型。原生 Swing 编辑器中的 Test framework 切换模式并展示对应字段；工作目录、环境变量、构建参数和测试参数继续保存在同一配置中。旧配置默认 `gotest`，模式字段参与 XML 持久化、克隆和上下文配置复用。[配置参数](../../src/main/kotlin/dev/openidea/goide/run/GoRunParameters.kt)、[配置编辑器](../../src/main/kotlin/dev/openidea/goide/run/GoRunSettingsEditor.kt)、[持久化](../../src/main/kotlin/dev/openidea/goide/run/GoRunConfiguration.kt)

| 模式 | 原生字段与 Run | Debug |
| --- | --- | --- |
| `gotest` | Test pattern → `-run`；普通单测、Example 和 Fuzz seed 执行 | 编译测试二进制，使用对应 `-test.*` 参数 |
| `gobench` | Benchmark pattern、时间或迭代次数、内存统计 → `-bench`、`-benchtime`、`-benchmem`；默认 `-run ^$` | 保留 Benchmark 选择器、次数/时长和内存统计 |
| `go test -fuzz` | Fuzz pattern、运行时间或次数、最小化时间或次数、worker 数 → `-fuzz`、`-fuzztime`、`-fuzzminimizetime`、`-parallel`；默认 `-run ^$` | **只执行匹配函数的 seed corpus**，不启动 mutation workers |

Benchmark/Fuzz 可通过 Also run tests 字段显式加入普通测试。Fuzz 要求一个具体包目标，不接受 `...`；时长、次数和 worker 数有本地校验，正则合法性由 Go 判断。专用模式拒绝自定义参数重复覆盖其受管选择器；IDE 管理 `-json`。这些字段遵循 Go 的测试标志语义，而非启动另一套测试框架。[字段校验](../../src/main/kotlin/dev/openidea/goide/run/GoTestWorkflow.kt)、[Go 1.24.3 测试标志](https://pkg.go.dev/cmd/go@go1.24.3#hdr-Testing_flags)

gutter 和上下文 producer 从缓存的源码/PSI 识别 `TestX(*testing.T)`、`BenchmarkX(*testing.B)`、`FuzzX(*testing.F)` 及有效 Example，识别 testing 导入别名，排除方法、泛型、错误签名和独立 `TestMain`。当前识别采用平衡词法扫描，尚不是完整 Go 类型检查。生成的配置仍使用上述单一类型，模式与精确函数选择器共同决定复用。[入口识别](../../src/main/kotlin/dev/openidea/goide/run/GoRunContext.kt)

Debug 将测试运行参数转换为测试二进制的 `-test.*` 参数，构建参数单独传给编译步骤。Fuzz Debug 的 seed 语义与普通 `go test` 执行 seed 的官方定义一致；真正的 mutation 必须由 Run 的 `-fuzz` 启动。失败重跑保留 Benchmark 模式；mutation 的真实 JSON 只上报 Fuzz 函数失败，适配器从该包/同名函数的标准输出绑定保存输入，收到明确 fail 后才生成精确 seed 选择。取消、路径歧义或不安全测试名不会冒充精确输入失败。保存的 Fuzz 失败输入重跑切换为 `gotest`，不重新启动随机探索。[Debug 参数](../../src/main/kotlin/dev/openidea/goide/debug/GoTestDebugArguments.kt)、[Debug launch](../../src/main/kotlin/dev/openidea/goide/debug/GoDebugLaunch.kt)、[重跑计划](../../src/main/kotlin/dev/openidea/goide/run/GoTestRerunPlan.kt)、[Go Fuzzing](https://go.dev/doc/security/fuzz/)

## Benchmark 结果与 Stop

真实 Go 1.24.3 的 Benchmark JSON 有 `run`、`output` 和测量行，但没有逐 Benchmark 的 `pass` 事件。适配器在包 `pass` 或后续兄弟/顶层运行证明前一个 Benchmark 已返回时完成相应节点；不会仅凭用户打印的数字行判为成功。普通测试缺失终态仍按失败处理。测量结果保留在原生测试输出中。[结果适配器](../../src/main/kotlin/dev/openidea/goide/run/GoTestOutput.kt)、[Go test2json 源码](https://github.com/golang/go/blob/go1.24.3/src/cmd/internal/test2json/test2json.go)

Stop 在向自己启动的进程发送中断前先登记取消，消除退出失败消息与取消状态的竞争。未完成节点以停止/忽略结束，包含处理中断后晚到的 pass 或 fail；运行中的 Benchmark 不伪报 PASS，已确认的失败继续保留；只回收此项目启动的 Go 进程及其子进程。[进程处理](../../src/main/kotlin/dev/openidea/goide/run/GoProgramRunner.kt)、[取消与完成规则](../../src/main/kotlin/dev/openidea/goide/run/GoTestOutput.kt)

## Fuzz 失败输入导航与精确重跑

控制台识别标准 `Failing input written to testdata/fuzz/FuzzName/<文件名>` 行。相对路径只能在预加载包元数据给出**唯一包目录**时解析；目录不明、多包歧义或路径逃逸均不猜测链接。过滤器只读缓存，点击后才在后台刷新/读取 VFS，再切回 EDT 打开原生编辑器，并检查项目是否已释放。[控制台链接](../../src/main/kotlin/dev/openidea/goide/run/corpus/GoFuzzCorpusConsoleFilter.kt)

corpus 文件类型同时检查 `<包>/testdata/fuzz/FuzzName/<文件名>` 路径与 `go test fuzz v1` 首行，使用独立的数据语言，不接入 gopls。gutter/context availability 只读取缓存文档与 VFS 路径；未保存编辑若破坏首行，立即停止提供执行入口。[文件类型与路径](../../src/main/kotlin/dev/openidea/goide/run/corpus/GoFuzzCorpusFileType.kt)

Run/Debug 对同一个输入使用 `gotest`、目标 `.`、对应包工作目录与 `^FuzzName$/^<转义后的文件名>$`。当前精确重跑支持标准 hash 和 `[A-Za-z0-9.-]+` 的手工文件名（排除 `.`、`..`）。含 `_`、空白、控制字符、`#` 等名字仍可作为 corpus 数据打开，但不提供可能选错输入的执行入口：Go 会改写测试名并消解重名，完整支持需要后台碰撞索引。不能把文件名直接等同于最终测试名，也不能在 availability 中读取目录枚举。[原生 producer/gutter](../../src/main/kotlin/dev/openidea/goide/run/corpus/GoFuzzCorpusRun.kt)、[Go 测试名改写](https://github.com/golang/go/blob/go1.24.3/src/testing/match.go#L280-L320)

## 自动化证据

本轮 `./gradlew test buildPlugin` 通过：**229 tests，0 failures，0 errors，0 skipped**。构建输入为 192 个文件，SHA-256 为 `448a585dc11282af6a9d5d8fb9b2670f2947ee804db7c4e61afc0058f276409a`；统计来自本轮 JUnit XML，输入信息来自构建 provenance。`engine` 源码本轮未改，完整 `go test -race ./...` 和 `go vet ./...` 通过；signature 包本次实际执行 234.781s，其他部分使用有效缓存。

| 可复现测试源码 | 本轮结果 | 实际覆盖 |
| --- | --- | --- |
| [GoTestWorkflowTest](../../src/test/kotlin/dev/openidea/goide/run/GoTestWorkflowTest.kt) | 6 项，0.002s | 模式参数、校验、Debug 转换、重跑和 Stop 终态竞争 |
| [GoTestWorkflowNativeTest](../../src/test/kotlin/dev/openidea/goide/run/GoTestWorkflowNativeTest.kt) | 2 项，0.058s | 原生配置持久化/深克隆；Coverage 只允许普通 TEST 模式 |
| [GoRunContextNativeTest](../../src/test/kotlin/dev/openidea/goide/run/GoRunContextNativeTest.kt) | 5 项，0.766s | 原生 PSI/editor DataContext producer、实际 gutter action update、有效签名与配置复用 |
| [GoTestWorkflowIntegrationTest](../../src/test/kotlin/dev/openidea/goide/run/GoTestWorkflowIntegrationTest.kt) | 3 项，3.061s | 真实 Go Benchmark/Fuzz/编译后 seed 执行、真实 JSON 结果树、无限 Fuzz Stop 回收、数字日志不假 PASS |
| [GoFuzzCorpusTest](../../src/test/kotlin/dev/openidea/goide/run/corpus/GoFuzzCorpusTest.kt) | 4 项，0.001s | 首行+路径检测、分段转义、UTF-16 链接范围、目录歧义/逃逸 |
| [GoFuzzCorpusNativeTest](../../src/test/kotlin/dev/openidea/goide/run/corpus/GoFuzzCorpusNativeTest.kt) | 6 项，0.376s | 原生文件类型/叶子语言、平台 provider 与 daemon collector、producer、未保存编辑、链接实际打开编辑器 |
| [GoFuzzCorpusToolTest](../../src/test/kotlin/dev/openidea/goide/run/corpus/GoFuzzCorpusToolTest.kt) | 1 项，1.999s | 真实 Go 和编译后二进制精确选中单个 corpus，排除普通测试及其他 seed |

真实工具 fixture 证明：Benchmark 只运行 `BenchmarkEcho/small`，保留 `ns/op`、`B/op`、`allocs/op`，包完成后树节点成功；Fuzz 运行指定函数 100 次；相同测试二进制的 Debug 参数只执行 `seed#0`、`seed#1`，无 mutation 输出。无限 Fuzz 停止后其实际子进程退出。另一个仍在运行、打印 Benchmark 格式数字的 fixture 停止后为忽略状态，没有成功结果。上述测试是实际工具与原生平台适配测试，尚不等同独立桌面按钮验收。

额外 Go 1.24.3 darwin/arm64 corpus 探针分别选择失败 hash、成功 hash 和 `manual-case.json`，仅出现指定子项，退出码分别为 1、0、0；排除了 `F.Add` seed 和其他手工输入。编译后二进制精确重现相同失败项。临时项目和本轮启动的进程均已清理；可复现逻辑保留在上述 corpus 工具测试中。

新增 [GoCancelledParentNativeTest](../../src/test/kotlin/dev/openidea/goide/run/GoCancelledParentNativeTest.kt) 2 项原生 SM 结果树回归：子项已经完成、父函数仍运行时，Stop 必须留下可见的忽略阶段，不能仅凭已通过子项显示父函数通过；已有子项失败及精确重跑范围保留。覆盖无终态、晚到 pass、晚到 fail 共 6 组合。

新增 [GoFuzzFailureRerunToolTest](../../src/test/kotlin/dev/openidea/goide/run/GoFuzzFailureRerunToolTest.kt) 2 项、1.912s：真实 mutation 保存失败输入后，经结果适配器与重跑计划只执行新 hash，排除其他 seed；真实无限 Fuzz Stop 后不能将未完成函数显示为 PASS。纯输出回归另覆盖晚到 pass/fail、已完成结果保留、多包/函数绑定、歧义/逃逸以及缺失终态。

最终 `448a585d` 包已再次通过独立进程身份和插件加载验证。32 项插件 artifact 摘要在桌面运行后不变。原生运行 `TestParentStop`，子项先 PASS，父函数输出 `parent teardown started` 后保持运行；Run > Stop 后树显示 **1 passed、1 ignored**，既保留子项成功，也保留父阶段取消，两个进程均退出。229 项测试全部通过，最终包的输入摘要与上文一致。

## 独立桌面验收与剩余边界

初始 `2c8f…2515` 包的原生配置窗口已实际创建并执行 BenchmarkEcho/small（3x、内存统计，1 passed、exit 0），FuzzEcho（100x、worker 1，1 passed、exit 0），FuzzFailure（真实 mutation 写入失败 hash，1 failed、exit 1）。点击控制台实际下划线路径成功打开 corpus 原生编辑器。无限 Fuzz Stop 已回收三层自有进程，但暴露晚到 PASS 错算成功；corpus 编辑器也暴露叶子 token 语言导致缺失 gutter。两项均已修复并通过上文回归；修复后的发行包桌面复验另记。后续 `b38bd22f` 包复验已看到 corpus 第一行运行图标；Control+Shift+R 从原生上下文只执行指定 hash，Control+Shift+D 编译后只传同一 `-test.run`，命中 `subject_test.go:38` 回调断点，原生求值 `value` 为 corpus 内容 `"3eed"`。继续后测试目标按预期 exit 1、adapter exit 0，相关进程全部退出。物理 gutter 点击弹出菜单尚未证明，不能用上下文快捷键代替该项验收。

该包的无限 Fuzz 再次停止时，实际 Go 仍输出 PASS，原生树正确显示 **1 test ignored** 和 `Go test run was stopped`，三层进程均退出。重启后原生配置仍保留 framework、pattern、100x、minimization 0 和 worker 1。完整原生观察见 [记录](native-test-workflows.json)。

**仍待补：** 原生 Benchmark Debug、物理 gutter 点击及菜单执行、从 mutation 结果工具栏直接精确重跑、完整保存/结果恢复组合。上述原生上下文与 DAP 调试证据不替代这些不同入口。

本轮没有关闭完整 TEST03。仍需完成 GoLand 完整测试范围与入口、`gocheck`、所有手工 corpus 名改写及碰撞处理、测试生成及 LANG09 相关能力，并覆盖跨平台/远端等未验收组合。当前测试入口的词法识别也不等同完整类型语义。GoLand 的完整测试生成要求不能靠简单接通 `gopls.add_test` 宣称完成；需要独立处理泛型、变参、已有测试冲突等实际边界。完整验收矩阵继续开放。

另有未解决观察：最终原生验收中，重启前从外部扩展自有 fixture 后定位新函数时，出现 gopls `textDocument/codeAction: column is beyond end of file` 通知。未保存该请求的协议 trace，根因尚未建立；测试运行/Stop 仍成功，但文档同步与 Code Action 的完整健壮性不能标记通过。
