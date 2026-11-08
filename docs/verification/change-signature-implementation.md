# LANG-07：Change Signature 当前实现与验证边界

记录日期：2026-11-09。本文记录当前产品源码、自动化测试及正在进行的独立桌面验收；[原始设计与工具探针](change-signature-design.md)保留其研究基线。**LANG-07 仍保持未完成，当前实现不能作为全部 GoLand 行为已经还原的声明。**

## 当前实现

Kotlin 和 IntelliJ Platform 提供标准 Refactor > Change Signature 入口、原生参数/结果表格、冲突对话框、差异预览和原子编辑。Go engine 使用公开 Go AST、`go/types` 和 `golang.org/x/tools/go/packages` 独立分析与生成，不把 `gopls.change_signature` 当作写入后端，不复制 JetBrains 实现。

支持的变更包括函数/方法改名，参数与结果的新增、删除、重排、改名、类型修改，以及 variadic 调整。变更依靠稳定 `rowId` 和 `oldIndex` 对应旧行。新增参数默认值、旧参数的调用处转换、结果的函数体默认值/转换、参数函数体迁移、结果使用处迁移分别表达，不能互相替代；需要用户决定的业务转换不会自动猜测。新增行的空默认值生成有类型的 Go 零值，保留行的空字段不替换原值。

已建模的调用形态包括普通函数与方法、泛型实例、绑定方法值、方法表达式、tuple 实参、variadic 普通/展开实参、结果消费、`return`、`defer` 和 `go`。有副作用的形态使用有类型的临时绑定或 adapter，保存原 callee、receiver 和实参的单次求值；只有证明安全的普通调用才直接重排。新增默认表达式在原 callee/实参之后求值。直接 `defer`/`go` 使用独立生成方式，保留注册时/原 goroutine 中的求值，以及 deferred 目标的直接调用关系。函数值 adapter 在创建时捕获绑定 receiver，并保留原函数类型。

接口关系按变更前的同一类型世界求闭包，覆盖可证明的工作区接口/具体方法、嵌入/提升和泛型实例关系；同类型参数重排也更新动态调用。加载选定 SDK、环境、build tags、go.work 主模块及测试变体，并纳入未保存的 Go 源码和模块/工作区元数据。planner 只返回候选编辑，不保存用户源码；候选重新加载并检查类型，有冲突时不返回可误用的部分编辑。原生应用还要求 `validation.typeChecked=true`。

| 责任 | 当前拥有源码 |
| --- | --- |
| 标准原生 action、后台 inspect/plan、取消与项目关闭 | [GoRefactoringSupportProvider.kt](../../src/main/kotlin/dev/openidea/goide/refactor/signature/GoRefactoringSupportProvider.kt)、[GoChangeSignatureService.kt](../../src/main/kotlin/dev/openidea/goide/refactor/signature/GoChangeSignatureService.kt) |
| 行模型、参数/结果/迁移表格、签名预览 | [GoSignatureModel.kt](../../src/main/kotlin/dev/openidea/goide/refactor/signature/GoSignatureModel.kt)、[GoChangeSignatureDialog.kt](../../src/main/kotlin/dev/openidea/goide/refactor/signature/GoChangeSignatureDialog.kt) |
| 快照、UTF-16、SDK/workspace/test 加载、目录成员前提 | [snapshot.go](../../engine/internal/refactor/signature/snapshot.go)、[directories.go](../../engine/internal/refactor/signature/directories.go) |
| 符号、接口/泛型关系、名称绑定保护、未激活 build 文件 | [symbols.go](../../engine/internal/refactor/signature/symbols.go)、[relations.go](../../engine/internal/refactor/signature/relations.go)、[rename.go](../../engine/internal/refactor/signature/rename.go)、[inactive.go](../../engine/internal/refactor/signature/inactive.go) |
| 参数/结果函数体迁移、调用/函数值生成、imports、整组候选检查 | [bodies.go](../../engine/internal/refactor/signature/bodies.go)、[calls.go](../../engine/internal/refactor/signature/calls.go)、[edits.go](../../engine/internal/refactor/signature/edits.go)、[plan.go](../../engine/internal/refactor/signature/plan.go) |
| RPC operation 隔离和 worker 生命周期 | [server.go](../../engine/internal/rpc/server.go)、[worker.go](../../engine/internal/refactor/signature/worker.go)、[worker_unix.go](../../engine/internal/refactor/signature/worker_unix.go) |
| 依赖/文件/文档/LSP/目录再次校验、整组预览、单个 Undo command 和失败回滚 | [GoSignatureValidation.kt](../../src/main/kotlin/dev/openidea/goide/refactor/signature/GoSignatureValidation.kt)、[GoWorkspaceEditPreviewDialog.kt](../../src/main/kotlin/dev/openidea/goide/refactor/GoWorkspaceEditPreviewDialog.kt)、[GoWorkspaceEditService.kt](../../src/main/kotlin/dev/openidea/goide/refactor/GoWorkspaceEditService.kt) |

## 原生操作流程

标准菜单/快捷键调用 handler 后，在后台进行 Go 分析。对话框提供 Name、Parameters、Result parameters、Body and usage migrations；支持 Add、Remove、Move Up/Down、variadic、默认值和转换，并显示 receiver、相关声明数、函数值使用数和签名预览。Preview 触发语义分析；冲突显示位置和原因，并保留草稿以便继续修改。

无冲突的候选进入共享预览窗口，展示 Files/Usages 和只读 Before/After 差异；Apply 应用整组修改。磁盘读取和 package/process 分析在后台，提交前在原生 write command 内再次检查已缓存的文档与 VFS 前提。文件内容、原生文档 stamp、真实可选 LSP session/version、依赖、目录成员或 SDK/build 配置有变化时拒绝旧计划。成功应用使用一个原生全局 Undo command；中途失败按事务 journal 回滚。

当前采用自动相关关系闭包，没有 GoLand 式的实现范围选择器。类型和表达式是原生表格/文本框，语义校验由 Preview 中的 Go planner 完成；尚无 Go 代码片段补全及逐单元格语义编辑器。Usages 负责选择对应文件，尚未实现完整的使用处树和精确行级预览导航。这些差异仍属于原生体验验收范围。

## 明确冲突与覆盖边界

- **Build contexts：**仅加载当前 GOOS/GOARCH/tags 的类型世界。排除的 Go 文件含潜在相关标识符时返回 `inactive-build-context`；扫描到名字不等于证明它是同一符号，因此可能保守拒绝。尚未完成所有平台/tag 组合的统一迁移。
- **关系闭包：**只读外部接口契约返回 `readonly-interface-contract`；泛型约束重叠且无法对所有实例证明关系时返回 `generic-relationship`。不能以“候选编译通过”替代这些关系证明。
- **函数值与 recover：**可适配的函数/方法值保留旧类型；含直接 `recover` 的目标函数值返回 `deferred-function-value`，尚未完成整条 typed function-value flow 的新类型传播。
- **参数/结果迁移：**删除或改变仍被函数体使用的参数，需要明确函数体迁移。删除仍被消费的结果、改变旧调用方需要的结果类型，需要明确使用处迁移。部分保留的 named result 改类型或去名且函数含 `defer` 的情形返回 `named-result-defer-migration`，不能声称任意 deferred result 写入都可自动转换。
- **名称与可见性：**局部短声明冲突、标识符捕获、已有 builtin 重新绑定、提升成员碰撞、init/main 隐式生命周期变化、跨包私有符号、无法在目标作用域绑定的类型/默认表达式，以及候选类型错误均拒绝。合法且未捕获既有使用的 builtin 名称可以使用，不采用一律禁用 builtin 名称的策略。
- **工作区边界：**响应持续标记 `coverage.externalBoundary=true`。未知外部调用者、reflection、unsafe 和字符串查找不在完整调用图证明内；自动生成公共兼容 API/bridge 尚未实现。
- **平台与进程：**当前独立桌面发行目标是 macOS。Unix worker 拥有独立进程组并在取消/父管道关闭时回收自身拥有的分析后代；[Windows 实现](../../engine/internal/refactor/signature/worker_windows.go)目前只终止 worker 自身，Job Object、后代回收和 Windows 桌面验收仍待完成。

冲突计划清空 `workspaceEdit.documentChanges`。旧 symbol/configuration digest、磁盘/文档、LSP 同步和目录成员变化也必须重新分析，不能继续应用旧预览。

## 自动化验证记录

主线程已对候选包完成全量 Gradle 验证：Kotlin **182 passed，0 failed，0 skipped**，plugin structure 与构建时的签名打包检查通过。该候选包的构建源码 digest 为 `ae7475ad3ca625411b277eacde2b6b203ac8fca83224841ab7b5b246a940903f`，共 **177 个输入**。此前中间包的 digest 为 `2f81aa5947740553f7e006f39a6560a9bfe871578f18186ab5e56629b8547523`，同为 177 个输入；两份包的桌面证据分别记录于下节。候选包启动后发现的 code-sign 资源写入问题已通过重新打包及启动前后校验解决，最终发行构建记录见文末。

独立调用生成模块此前完成：

```sh
cd engine
go test -race ./internal/refactor/signature -run 'Test(CallRewrite|CallDefault|FunctionValue)' -count=1
```

结果通过（39.938s）。最终全 engine `go test -race ./...` 和 `go vet ./...` 已由拥有方完成并退出 0：**61 个顶层测试、含子测试共 137 项通过，0 failed、0 skipped**；其中 signature 包为 37 个顶层测试、含子测试共 103 项，耗时 240.285s；RPC 包耗时 7.001s。该全量结果包含下述显式转换和 schema 防护。本文没有自行再次运行 Gradle 或全量 Go 测试。

显式字段回归已经修复并验证：保留结果同类型/同映射时填写 `bodyDefault` 仍触发函数体转换；调用方同类型的结果使用迁移也不会被忽略，覆盖直接调用和保存的函数值。针对批次 race 测试通过，5 个顶层测试、含子测试共 20 项（70.757s）。其中四个真实运行案例覆盖未命名结果的表达式/零值，以及命名结果的裸 return/显式 return 与 defer。未知或重复迁移、字段放错位置、无效表达式以及当前不支持的同类型参数函数体 override 均返回无编辑冲突。对应回归为 `TestUnchangedResultShapeStillAppliesExplicitBodyConversion`、`TestUnchangedResultShapeStillAppliesExplicitCallerConversion` 和 `TestExplicitChangeFieldsCannotBeSilentlyIgnored`，见下表 `plan_test.go`。

| 测试源码 | 核查的实际行为 |
| --- | --- |
| [calls_test.go](../../engine/internal/refactor/signature/calls_test.go) | 编译/运行自建 fixture，比较实参/receiver/默认值效果 trace、typed nil、泛型、tuple、variadic、直接 defer/recover、go、结果消费和函数值；还覆盖 nested edits、注释、词法绑定、fresh names、取消及不可变快照并发 |
| [plan_test.go](../../engine/internal/refactor/signature/plan_test.go) | 真实 typed planner、接口动态/静态调用、泛型关系、named result/defer、结果/参数迁移、dirty go.work/模块、UTF-16/CRLF、vendor/imports、目录新增、名称捕获和 init/main 生命周期保护 |
| [signature_test.go](../../engine/internal/rpc/signature_test.go)、[signature_worker_unix_test.go](../../engine/internal/rpc/signature_worker_unix_test.go) | RPC inspect/plan/cancel 与拥有的 Unix worker/后代生命周期 |
| [GoSignatureModelTest.kt](../../src/test/kotlin/dev/openidea/goide/refactor/signature/GoSignatureModelTest.kt)、[GoChangeSignatureHandlerTest.kt](../../src/test/kotlin/dev/openidea/goide/refactor/signature/GoChangeSignatureHandlerTest.kt) | 稳定行身份、默认值语义、wire UTF-16/真实可选版本，以及标准平台 Change Signature handler 注册 |
| [GoSignatureValidationTest.kt](../../src/test/kotlin/dev/openidea/goide/refactor/signature/GoSignatureValidationTest.kt) | 未编辑依赖变化、文档变动、BOM/CRLF、SDK 变更、sum 文件出现、未编辑包新增 Go 调用方、native 新包、disk/VFS 竞争和额外 main-module 根校验 |

## 独立桌面验收与交付边界

以下为主线程在 **中间包 `2f81aa5947740553f7e006f39a6560a9bfe871578f18186ab5e56629b8547523`** 上已经观察到的独立应用行为，尚未扩展为最终候选包或其他未执行流程的结论：

- 标准原生菜单和 Cmd+F6 均进入 Change Signature 对话框。
- 未保存的 go.work 将一个尚未打开其调用文件的外部 main module 纳入分析。
- 预览显示两个文件的只读着色差异，以及包含函数值的三个 usages。
- 保持预览期间新增调用方后，Apply 因刷新/目录成员前提变化被拒绝；八个原始磁盘文件保持不变。
- 随后重新分析并成功 Apply：未保存的 go.work 纳入尚未打开调用文件的外部 main module。真实项目 `go run` 输出为 `[1 2] 12 34 56`，保留副作用顺序和调用结果。
- 一次原生 Undo 精确恢复全部受影响 Go 源码，同时保留重构之前已经存在的 go.work 编辑；一次 Redo 精确恢复整组修改，再次运行输出相同。

主线程随后在 **候选包 `ae7475ad3ca625411b277eacde2b6b203ac8fca83224841ab7b5b246a940903f`** 上完成以下原生流程。该次应用进程为 84866、gopls 为 84951、engine 为 85879；这些本项目启动的进程均已退出并回收。

- 接口方法 `Compute` 显示 Related declarations 为 2。Preview 显示 contract、implementation 和外部调用方共三个文件的着色差异。Apply 后保存并真实 `go run`，`convert` 的两个结果均为 1，后续 trace/结果行仍为 `[1 2] 12 34 56`。一次 Undo 精确恢复三个文件，一次 Redo 精确恢复整组修改。
- 组合变更将 `Convert` 改名为 `Converted`，新增 `scale int` 参数及调用默认值 `3`，新增零值 `error` 结果，并为保留结果指定 `oldResult0+1` 函数体转换。Preview 显示两个文件。Apply 后真实运行，`convert` 的两个结果均变为 2，后续 trace/结果行仍为 `[1 2] 12 34 56`。一次 Undo 精确恢复两个文件，一次 Redo 精确恢复整组修改，随后再次运行输出相同。
- 打开只读 SDK `runtime/proc.go`（214687 字节），原生 caret 到达 7376:1/文件末尾。截图显示 sticky function `doInit1` 在 7324 行呈蓝色，7365–7370 行的 builtin `print` 呈蓝色，并有类型及参数名 inlay hints；没有文件上限错误提示或 messages-disabled 提示。SDK 文件 SHA-256 `9cff4fca76c6778741947fbcf362530a5259b050cc7643838d6feeaa77df905a` 保持不变。这证明该位置的实际显示，不代表所有范围语义请求都已取得原生 wire 证据。

以上证明这些有限原生工作流已经通过。泛型及其余迁移、关闭/取消/会话失效路径仍需按验收矩阵逐项补证；这些结果也不证明安装的 GoLand 2026.1.4 在相同情形下的行为。

### 发行签名问题已修复并重新验收

`ae7475a…` 候选应用启动后，LSP4IJ 将两个 bundle 的文件解压到 `.app` 内，导致启动后的 code-sign 校验失效。最终打包改为**签名前预先解压两个固定资源**。新发行构建的源码 digest 为 `2960da4418ca4da403eb6b04d56889e38d3d89b5cf70272af2131c352274e961`，仍为 177 个输入；程序逐项比较确认相对于 `ae7475a…` **只有 [scripts/package-product.py](../../scripts/package-product.py) 一个输入变化，没有 Kotlin 或 Go 源码变化**。重新执行全量 Gradle test/buildPlugin、plugin structure 和 package 验证，再次得到 182 passed、0 failed、0 skipped。

预先准备的两个 bundle 资源大小分别为 680 和 957 字节，其固定路径与 SHA-256 记录于 [change-signature-native.json](change-signature-native.json)。主线程对新包实际启动并打开只读 SDK `runtime/proc.go`，在 214687 字节文件的 7376 行仍观察到蓝色语义着色及 hints；随后打开原生 Change Signature 对话框，显示正确的 `Converted(scale int) (int, error)` 和 Function value uses 为 2，再 Cancel，没有产生源码编辑。新应用进程 87875、gopls 87899、engine 87957 均已退出并回收。

`codesign --verify --deep --strict` 在**启动前、原生 LSP 与 engine 活跃时、退出后**三次均退出 0。32 个插件工件的校验结果保持不变；bundle 目录恰好为预先准备的两个文件，没有启动后新增文件。该证据解决了上述启动后签名失效问题，并将相同 Kotlin/Go 实现的既有工作流证据与新发行包的实际启动校验连接起来；不会将尚未执行的其他验收项标为通过。完整构建、原生流程与签名生命周期证据见 [change-signature-native.json](change-signature-native.json)。

整体状态以 [acceptance-matrix.json](../reference/acceptance-matrix.json) 的开放验收项和后续独立桌面证据为准。
