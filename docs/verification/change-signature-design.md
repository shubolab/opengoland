# LANG-07：open Goland 原生 Change Signature 设计与真实探针

当前产品实现与验证进度见 [Change Signature 当前实现与验证边界](change-signature-implementation.md)。下文保留原始研究基线；其中“没有产品实现”描述该次研究时点，不代表当前仓库状态。

状态：设计和工具验证；没有产品实现，没有修改 `src/`，没有 Gradle 或 GUI 操作。研究基线为 root 指定的 `840a0aa`。本地工具为 **gopls v0.21.1 / Go 1.24.3 darwin/arm64**；gopls 本身由 Go 1.25.8 构建。本文不能作为 LANG-07 已完成的验收证据。

决定：**不把 `gopls.change_signature` 包装成原生入口。** 新增参数、改类型、结果变更、接口关系、函数值和求值副作用由新的 Go engine typed-AST planner 完整建模；Kotlin 提供原生对话框、整组预览和已有原子编辑/Undo。gopls 可继续提供普通语言能力，不作为该重构的写入后端。

## 1. 证据边界和参考声明

GoLand 的参考功能取自 [2026.1 官方 Change Signature 文档](https://www.jetbrains.com/help/go/2026.1/change-signature.html)：原生 Name/Parameters/Result parameters，参数新增、删除、重排、默认实参、类型修改、结果新增，调用及实现更新，接口方法的实现范围选择，以及 Preview。该页是**功能声明**，不是安装版本 2026.1.4 的运行证明，尤其不证明它能保持所有副作用。

本次依照 REA skill 尝试仅用显式路径 `inspect_plist` 读取已授权 GoLand 的 Info.plist；当前服务返回 `target_unavailable / No app is open`。没有调用 `open_binary`、没有激活应用、没有修改安装或用户配置。已有 `docs/reference/installed-ides.json` 中 ChangeSignature action/keymap 是历史静态记录，本次不将其升级为运行事实。没有读取或复制任何商业实现。

完整原始探针、前后源码、WorkspaceEdit、效果 trace 和编译诊断保存于 [change-signature-evidence](change-signature-evidence/README.md)。五份选定官方 Go OSS 源文件完整保存为 `.go.txt`，附 BSD 许可；[sources.json](change-signature-evidence/sources.json) 保存固定 tag/commit URL、SHA-256 及与本地缓存逐字节一致的校验结果。

## 2. gopls 0.21.1 的实际协议和限制

使用 `workspace/executeCommand`，而不是标准 LSP 中不存在的 ChangeSignature 请求：

```json
{
  "command": "gopls.change_signature",
  "arguments": [{
    "Location": {
      "uri": "file:///workspace/api.go",
      "range": {
        "start": {"line": 3, "character": 12},
        "end": {"line": 3, "character": 12}
      }
    },
    "NewParams": [1, 0],
    "NewResults": [0],
    "ResolveEdits": true
  }]
}
```

- `Location` 是函数签名内的 LSP range，字符偏移为 UTF-16。旧参数索引按参数逐个展开，`a, b int` 是两个参数；不把 receiver 算入参数序列。variadic 参数仍是一行参数。
- `ResolveEdits:true` 返回 WorkspaceEdit，不写文件。本次所有该类探针均确认服务器没有改磁盘；由探针在自己的临时项目应用返回编辑后再编译/运行。
- `ResolveEdits:false` 走服务端 `workspace/applyEdit`，命令结果通常是 null，客户端必须响应 applyEdit；不能同时再应用同一批编辑。
- `ChangeSignatureArgs` 的 schema 声明元素可为旧索引或新字段字符串，也声明了 `NewResults`。**实现没有完成 schema 宣称的全部语义**：server handler 拒绝新参数字符串，且完全没有读取 NewResults。因此结果变更会返回成功但没有完成请求，甚至只产生格式化编辑。
- Rename 扩展是在 `func` token 发送 `textDocument/rename`，`newName` 是 `func(...) ...`。它支持已有参数的排列/删除路径，拒绝新增参数、改变参数类型和结果变更。不能把函数名位置的一般 Rename 当作该扩展。
- 命令处于实验状态，不能根据当前 pkg.go.dev/master 文档推断固定 0.21.1 的实现。
- 即便使用 `ResolveEdits:true`，LSP4J 的 `executeCommand` 返回类型是 Object。未来若处理任何此类结果，需要使用带 LSP4J Either adapters 的显式 WorkspaceEdit codec；不能依赖 `response is WorkspaceEdit` 就认为 JSON 对象已经自动变成模型。

固定源码：[command schema](https://github.com/golang/tools/blob/gopls/v0.21.1/gopls/internal/protocol/command/interface.go)、[handler](https://github.com/golang/tools/blob/gopls/v0.21.1/gopls/internal/server/command.go)、[rewriter](https://github.com/golang/tools/blob/gopls/v0.21.1/gopls/internal/golang/change_signature.go)。该 rewriter 使用 wrapper/inliner，但向 inliner 传入 **`IgnoreEffects: true`**；对应 [Options](https://github.com/golang/tools/blob/b69ac100ecb7/internal/refactor/inline/inline.go) 明确称此选项忽略实参潜在副作用、不可靠。

## 3. 真实工具结果

所有 12 个基线均先 `go test` 编译通过。探针只创建、修改和删除自己的 canonical 临时目录；禁网依赖获取，未执行用户项目。

| 探针 | 实际结果 | 对产品的含义 |
|---|---|---|
| 已有参数重排，实参为 `mark(1),mark(2)` | 重排前 `[1 2] 12`，后 `[2 1] 12`；两边均编译通过 | 结果值相同仍可能改变行为，不能只做编译检查 |
| 删除函数体未用参数，其实参调用 `mark(2)` | `[1 2] 1` 变成 `[1] 1`，编译通过 | 删除形参不能静默删除实参的效果或 panic |
| 删除仍被函数体使用的参数 | 命令报 rewritten package 中 `undefined: b`，没有编辑 | 本地 planner 必须给出函数体使用位置及迁移方式 |
| 增加 `"c int"` | 明确 unsupported | 不能在原生表格中展示可用却交给该命令 |
| NewResults 重排、删除 | 命令成功；原 `(int,error)` 保留，只改格式 | 客户端必须验证请求意图，不能把 RPC success 当完成 |
| 函数被作为值引用 | `non-call function reference`，没有编辑 | 本地 planner 需要函数值/回调策略 |
| 跨包实现方法：int/string 参数重排 | 静态调用被更新；接口声明/动态调用未更新；之后编译失败 | 必须分析并更新接口关系闭包 |
| 同类型接口参数重排 | 编译仍通过，输出从 `12 12` 变成 `12 21` | 类型检查也不能识别漏改动态调用的语义错误 |
| go.work 两个模块，签名前同一行含 😀/中文 | 两个文件编辑正确，前后编译均通过，Unicode 保留 | 已证明此有限场景的模块和 UTF-16 映射能力 |
| Rename 扩展改参数类型、加结果 | 分别明确 unsupported | 不能用 Rename 扩展补齐完整功能 |

最小效果示例：

```go
var trace []int
func mark(n int) int { trace = append(trace, n); return n }
func Target(a, b int) int { return a*10 + b }
// 原调用 Target(mark(1), mark(2)) 输出 trace=[1 2], value=12。
// gopls 改声明为 Target(b, a int)，调用改成 Target(mark(2), mark(1))。
// value 仍为12，但 trace 变成[2 1]。
```

此外，9 个独立 Go lowering 探针验证了候选生成方式：有类型的 adapter 保持重排/删除实参效果和 nil 的上下文类型；直接 hoist 的 defer 调用保持 recover；结果重排保持带副作用 LHS 的求值顺序；移除结果保留函数体 return 表达式的效果；接口闭包修改后前后输出相同。新增默认表达式采用“已有实参之后求值”的明确策略，得到 `[1 2 3]`。一个禁止内联的 deferred adapter 反例使 recover 返回 nil、进程失败，说明不能依赖优化器隐藏额外调用层。

这些是**生成形状的工具证明，不是一个已实现 planner 的端到端验收**。

## 4. Go engine typed-AST planner API

新增独立的 `engine/internal/refactor/signature/`，不是 shell 文本替换、正则或 CLI `-w`。解析、符号、关系、调用重写、校验均在 Go；Kotlin 不尝试用 flat PSI 推断 Go 类型。

### inspect

`refactor/signature/inspect` 接受 operationId、目标 URI/UTF-16 caret、当前工具链/GOOS/GOARCH/CGO/tags/workspace 配置及 dirty overlays。返回目标 kind、稳定 symbolId、旧参数/结果的稳定 rowId、各行名字/类型/variadic、receiver、可编辑范围、有关接口与函数值使用情况。

symbolId 包括声明文件/原始 byte span/内容 digest、包路径和 receiver/generic origin；不能只用符号名字或跨 packages.Load 的对象指针。caret 在调用处时用 `types.Info.Uses/Selections/Instances` 定位声明；方法表达式、promoted methods、接口方法要区分。旧信息只对对应文档和配置版本有效。

### plan

`refactor/signature/plan` 的核心数据：

```json
{
  "operationId": "signature-uuid",
  "symbolId": "from-inspect",
  "configurationDigest": "sdk+env+tags+workspace",
  "overlays": [{
    "uri": "file:///workspace/api.go",
    "text": "unsaved editor text",
    "documentStamp": 123,
    "diskSha256": "baseline",
    "lsp": {"session": 4, "version": 19}
  }],
  "change": {
    "name": "Target",
    "parameters": [
      {"rowId":"old-b", "oldIndex":1, "name":"b", "type":"string"},
      {"rowId":"new-context", "oldIndex":null, "name":"ctx", "type":"context.Context",
       "defaultArgument":{"kind":"expression", "text":"context.Background()", "scope":"declaration", "evaluation":"afterOriginalArguments"}},
      {"rowId":"old-a", "oldIndex":0, "name":"a", "type":"int"}
    ],
    "results": [
      {"rowId":"old-result", "oldIndex":0, "type":"string"},
      {"rowId":"new-error", "oldIndex":null, "name":"err", "type":"error", "bodyDefault":{"kind":"zero"}}
    ],
    "parameterBodyMigrations": [],
    "removedResultUseMigrations": []
  },
  "policies": {
    "interfaceScope":"relatedWorkspaceClosure",
    "functionValues":"adaptOrPropagateTypedUses",
    "removedArgumentEffects":"preserve",
    "reuseTypes":true
  }
}
```

- 未出现在新列表中的 oldIndex 表示移除；一个旧行不能映射两次。旧行允许独立改名/改类型。receiver 不可被误当普通参数移动；variadic 必须位于最后。参数和结果索引是两个命名空间。
- `defaultArgument` 是**生成到调用处的实参表达式**；Go 没有语言级默认参数。新结果的 `bodyDefault` 是实现中返回什么，移除结果后的 `removedResultUseMigrations` 是调用方原有值如何替代，三者不能混用。
- 默认表达式的符号按声明上下文绑定，可引用旧参数 rowId；移植时绑定到旧实参的单次求值结果，不能复制 `mark()` 的源文本。包引用按 symbol/import path 重新限定，不依赖原 alias 文本。
- overlay 必须含原生编辑器当前 text。LSP version/session 只有来自真实同步镜像且文本匹配时才携带；engine 不能生成或猜测 LSP version。没有可用 LSP 版本时用 native stamp + disk digest 作为前提，编辑模型中 version=null。
- `parameterBodyMigrations` 以旧 rowId 指定“移除后用哪个表达式替代其 uses”或“新类型经指定转换进入旧类型 local binding”；不能从已移除实参凭空恢复原值。`removedResultUseMigrations` 以旧 result rowId/具体 usage 指定替代值，必要时在原赋值点保存一次，不能让带效果的替代表达式在每个 use 重复执行。未提供而又确有使用的迁移是冲突。

返回一个不可变 plan：

```text
planId / planDigest / symbolId / configurationDigest
preconditions[]: uri, disk digest, editor stamp, actual optional LSP session/version
workspaceEdit: all versioned-or-native-baseline text edits, never write to disk
usages[]: definition / static call / interface call / implementation / function value /
          function type / return / import; each has source range and rewrite reason
coverage: main modules, package/test variants, build contexts, unresolved external boundary
conflicts[]: concrete source locations + cause + available migration choice
validation: parse/typecheck status and diagnostics before/after
```

`refactor/cancel {operationId}` 取消 load/分析/生成和 owned 编译子进程；计划和预览取消不写文件。planId 不是绕过版本检查的授权。发布计划前和确认提交前都校验 preconditions；有变化则重新计划。多个计划不能共享可变 AST 或 types.Info。

## 5. 必须独立实施的引擎模块

| 模块 | 具体实现与交付边界 |
|---|---|
| `snapshot` | SDK/env/tags/go.work/replace 主模块 + overlays 的不可变快照；规范 URI 与 UTF-16/byte 双向映射；取消/配置 digest |
| `load` | 固定版本公开 `x/tools/go/packages`，NeedTypes/Syntax/TypesInfo/Imports/Deps/Module、Tests=true；加载全部可编辑 main modules 及反向依赖，处理内部/外部测试包；一个分析中使用同一类型对象世界 |
| `symbols` | flatten AST fields，识别 FuncDecl、receiver、interface method、generic origins；objectpath + 文件/span/digest 标识；按对象和 selection 收集引用，不能按相同名字全局替换 |
| `relations` | `types.Implements/Satisfies`、method sets、embedded/promoted selections、实际接口赋值/返回/实参/断言及工作区潜在实现的关系闭包；接口方法与具体方法同一变更计划 |
| `calls` | 调用上下文分类、旧参数实例化类型、tuple/variadic、callee/receiver/实参求值保存、fresh names；普通表达式与 defer/go 使用不同 lowering |
| `values` | 函数/方法值、显式 func type、回调/容器/赋值/返回的 typed use graph；可证明的 adapter 或类型传播；未知动态流向返回具体冲突，不能漏改后宣称全部完成 |
| `bodies` | 参数改名使用 object-bound refs；参数改类型及已使用参数删除的迁移；所有 return/named result/defer 的结果变更；排除嵌套 function literal 的 return |
| `imports` | 类型/表达式移植、包可见性、别名冲突和 local shadowing；使用 public astutil/imports 能力，保留注释和用户格式意图 |
| `validate` | 对完整候选 overlays 再 parse/typecheck 涉及的整个依赖闭包和 test variants；比较已有诊断；检查请求的签名真实实现；effect-preserving rewrite 分类的可测不变量 |
| `plan` | 有序、无重叠 edits、稳定 usages 和 plan digest；不触碰磁盘；供 Kotlin 一次性原子应用 |

当前 engine 尚无上述类型分析层；`engine/go.mod` 只有 pprof 外部依赖。可使用与本地工具相同的 x/tools `v0.39.1-0.20260109155911-b69ac100ecb7`（其 go.mod 声明 Go 1.24.0），但必须单独审查升级和依赖/许可。

C/D 共享不可变 SignatureIR 和 usage 分类，分别给出参数/结果映射与 body migration；由单一 emitter 生成整个 FuncType/调用修改，不能让两模块各自产生互相重叠的签名 edits。AST 的 comments 位于独立表中，不能假定 CloneNode/FormatNode 自动保留全部注释：参数 row 的前后 trivia 要绑定到行，优先用原 token spans 做最小替换，并以含注释重排样例验证。

`gopls/internal/...`、`x/tools/internal/refactor/inline` 受 Go internal 导入规则限制，不能从本项目直接 import。可以独立实现上述公开 API 方案；若确需移植 Go OSS 算法，应保留 BSD 许可、固定版本并修改/验证其效果策略。不能通过改 module 名绕过 internal 规则，不能复制 JetBrains 实现。

## 6. 副作用、defer/go 与默认值的正确性

以 [Go 求值顺序](https://go.dev/ref/spec#Order_of_evaluation)、[defer](https://go.dev/ref/spec#Defer_statements)、[panic/recover](https://go.dev/ref/spec#Handling_panics) 为约束。

普通表达式上下文的保守模板可保持旧参数的上下文类型及单次求值：

```go
// Target 已改为 func Target(b, a int) int。
value := func(oldA, oldB int) int {
    return Target(oldB, oldA)
}(mark(1), mark(2)) // 保持 [1 2]，不是[2 1]。
```

删除参数也保留原实参的求值，不能把副作用、channel receive、索引/解引用 panic 一起删掉。先做保守正确形状；只有证明是可交换的纯表达式时才简化。nil、未定类型常量、named types、泛型实例化必须有旧参数的上下文类型；`tmp := nil` 是错误生成。

callee/receiver 自身可能有副作用，必须在旧实参之前且只求值一次。方法值和方法表达式不能混淆 receiver 索引。单个 tuple 实参 `F(pair())` 不能直接变成非法的 `adapter(F, pair())`；需要保持 sole-tuple argument 或在原表达式位置的 IIFE 内缓存 callee，再一次展开 tuple。short-circuit、循环、条件初始化等位置不能把求值提升到原本不会执行的路径外。

**defer/go 不得套用普通调用 adapter：**

```go
// 对 defer Target(mark(1), mark(2)) 的参数重排：
{
    callee := Target
    var oldA int = mark(1)
    var oldB int = mark(2)
    defer callee(oldB, oldA)
}
```

这保持注册时求值和最终直接 deferred callee；改成延后再调用 Target 的 helper 可能破坏 recover。go 调用同样先在原 goroutine 计算 callee/实参，再 `go callee(...)`，不能把实参计算移进新 goroutine。fresh bindings 的作用域、label/goto 和控制流必须校验。函数值 adapter 若可能作为 defer callee 且目标含 recover，不能未经证明套用；需传播已知 defer 使用或报告具体不安全使用点。

默认值策略明确为：原有 callee 和实参按原规则求值一次，再计算新增默认表达式；无值参数用“生成零值实参”，不是 Go 签名中的默认值。默认引用旧参数时用绑定结果，不复制原表达式；默认所需 type/import 在每个调用文件检查可见性。新增 effect 是用户引入的语义变化，预览要展示它的求值位置。

`*new(T)` 可以表达很多类型的零值，但 T 必须能在调用位置合法引用。不可见的私有类型、只有 callee-local 才存在的名字或无法解析的默认表达式是具体冲突；不能盲塞 nil/0。参数改变类型时，AssignableTo、可转换与用户意图不同：例如 int→string 的 Go 转换不是十进制格式化。需要用户指定转换/函数体迁移，不能自动猜业务语义。

## 7. 接口、方法和函数值不能降级成静态调用列表

同类型接口探针说明，漏改 dynamic call 的 bug 可以通过所有类型检查。因此必须在**变更前**计算相关关系闭包，而不是先改具体方法，再依赖编译器找漏项。

- 起点是选中的具体方法或接口方法；沿实际赋值约束、实现关系、接口嵌入、promoted method sets、generic constraints 和相关方法规格扩展。
- 修改接口方法时，原生对话框按 GoLand 声明提供受影响实现范围；范围之外仍有必须维持的关系则列冲突，不允许静默跳过。
- interface 的两个 int 参数也要携带旧 rowId 映射；更新 dynamic call 的实参，而不是因类型签名相同就认为不受影响。
- 一个方法可能同时满足多个接口，多个接口可能共享 embedded 方法；必须闭包去重。名字相同不意味着同一关系。外部只读接口、不可编辑实现、build variants 不完整时不能伪造可安全的完整方案。
- exported API 的未知外部消费者、reflection/unsafe/string-based lookup 不是可枚举的本地调用图。报告工作区边界；可保留兼容 API 的 bridge 是显式设计选择，Go 不能用同名不同参数重载来同时保留两套方法。
- 函数值可以选择传播其可证明的本地 func 类型/调用，或生成保留旧函数类型的 adapter；后者用新默认实参调用新函数，且 method receiver 必须在取值时捕获一次。共享命名 func type、回调流向接口或外部代码时，传播会影响其他实现，需纳入闭包或使用可证明 adapter。SSA/VTA 可帮助定位，不能当作反射或开放世界的完整性证明。

## 8. 结果变更与函数体迁移

- 参数改名只更新对应 types.Object 的引用，不能替换同名局部变量/字段/注释字符串。删除仍在函数体使用的参数时，给出所有使用点；可指定表达式或显式迁移，否则是未解决冲突。
- 新增结果：更新所有 return 路径和必要的 named-result 声明，使用明确的 bodyDefault；调用方按上下文增加 `_`、调整赋值或使用保留旧结果形状的 adapter。
- 结果重排：返回表达式的求值顺序仍需保存；调用方 LHS 可能有副作用或别名。不能简单交换 `a[f()], a[g()]` 两个 LHS。可用 RHS adapter 先接收新结果，再按旧映射返回。
- 删除结果：保留被移除 return 表达式的效果。调用方未使用值可以丢弃绑定；仍使用该值时必须指定替代/传播方案。不能默默置零、删使用语句或移除错误处理。
- named results、裸 return、defer 改结果、shadowing、函数值的返回类型都需要单独分类；不是只改签名括号和显式 return 的逗号数量。移除 named result 后若 defer 仍使用它，需要保留合适 local binding 或明确迁移。
- 参数/结果改类型后函数体可能需要真正的业务修改；例如原代码 `x*2` 改成 string 没有唯一正确算法。完整功能应给出具体冲突和迁移输入，不能宣称所有类型变化都能自动保持行为。

## 9. dirty UTF-16、多模块、预览和 Undo

1. 原生动作在 EDT 只捕获上下文和快照；文件/包加载、RPC、parse/typecheck 全在后台。
2. 所有 open/dirty overlays 参与 packages.Load，包括调用方和实现。Go token.Pos 是 byte，不是 UTF-16；逐文件映射处理 non-BMP、CRLF、最后一行和非法 surrogate 边界，输出 edits 必须基于准确原文本。
3. go.work 所有 main modules 和本地 replace 参与关系/反向依赖。加载模式不能在 workspace root 直接假定 `./...` 有效；Go 和 fixture URI 使用一致 canonical 根，不能混用 `/var` 与 `/private/var`。
4. 当前 GOOS/GOARCH/tags 之外的文件和不同测试 variants 要有明确覆盖策略。发现可能相关但未能类型解析的构建分支，应返回有位置的冲突/未覆盖项；不能把当前配置成功叫作所有平台成功。
   包加载不能隐式 tidy 或修改 go.mod/go.sum，应采用适用的 readonly 模块模式。dirty go.mod/go.work 必须进入配置前提；不能假定 Go source Overlay 自动提供了正确的新模块图。若选定 SDK/loader 尚不能分析该 dirty 配置，应明确要求完成模块同步后重试，而不是使用旧图声称覆盖了调用方。
5. planner 只返回编辑，**不执行 go generate、用户 init 或测试**。产品的自动验证是 parser/type checker；真正运行测试是用户后续原生 Run 工作流。本次报告中的运行仅针对自建临时 fixture。
6. Kotlin 使用现有 GoWorkspaceEditService 的磁盘/文档/真实同步版本前提，单个全局 WriteCommand/Undo group 一次提交所有声明、调用、接口、函数值和 import。资源/磁盘读取不进 EDT。
7. Native Preview 展示整组 usages + diff + 实际冲突，确认后应用同一 planDigest。预览期间发生编辑、文件重命名、SDK/tags 改变、gopls session 重启或项目关闭，应拒绝旧计划并重新计算；不覆盖新内容。
8. 取消计划或关闭预览不写文件；一旦原子 write command 已开始，不在一半中止而留下半个重构。Undo/Redo 必须整组恢复，验证包括原先关闭文件、dirty 文档和外部 workspace module。

## 10. 原生入口和可独立分配的实现包

同版 SDK 公开 API 已只读核对：`RefactoringSupportProvider.getChangeSignatureHandler()`、`ChangeSignatureHandler`、`ChangeSignatureDialogBase`、`ParameterInfo` 存在。

| 工作包 | 所有权建议 | 可独立验收输出 |
|---|---|---|
| A 快照/协议/加载 | Go engine + Kotlin bridge | inspect/plan/cancel DTO、dirty overlays、稳定 symbol/row IDs、两模块/测试 variants、UTF-16 往返 |
| B 类型关系和使用分类 | Go engine | 对象引用、接口闭包、receiver/generic、函数值与外部边界；重排两个 int 的动态调用也完整枚举 |
| C 参数/调用 lowering | Go engine | 新增/删/重排/改类型/默认值；effect trace、panic、tuple、variadic、defer/go、short-circuit、nil/常量测试 |
| D 结果和函数体 | Go engine | 新增/删/重排/改结果类型、named return/defer/使用迁移与冲突；返回及 LHS 效果测试 |
| E 原生对话框 | Kotlin semantic UI | 注册 Go RefactoringSupportProvider/ChangeSignatureHandler，真正的 Refactor > Change Signature / Ctrl+F6；Name/参数/结果/默认值/实现范围、Go 类型/表达式校验；没有 WebUI |
| F 整组预览/事务 | Kotlin refactor | native usages/diff、计划取消/过期拒绝、全局 Undo/Redo 与关闭文件/多模块，复用现有事务而不直接写磁盘 |
| G 验证和冲突汇总 | Go + Kotlin tests | 解析/类型检查、请求意图验证、语义效果回归、干净错误/取消；实际独立 App GUI 验收 |

E 的 fast availability/target lookup 只能用本地 PSI/token，不能在动作 update 或 findTargetMember 同步 RPC。invoke 后后台 inspect 获取真正 Go 声明；当前 flat PSI 并不阻止采用原生 handler。对话框可以使用平台 RefactoringDialog/DialogWrapper + JBTable/Results tab；不必套 Java 的 visibility/throws 字段。

验收不能缩成 gopls 支持的那几项。A/B 可以先独立实现，但 LANG-07 必须保持未完成，直到 C/D/E/F/G 的完整功能和下面矩阵都经过验证。

## 11. 必需的真实验收矩阵

- 普通函数、方法、接口规格：新增/删/重排/改名/改类型及新增/删/重排/改类型结果，所有调用与实现匹配。
- same-type 动态接口调用、heterogeneous 接口、嵌入接口、promoted 方法、pointer/value receiver、同一方法满足多个接口、泛型实例及约束。
- 函数值/方法值/方法表达式、显式/推导 func type、局部回调/容器/返回、外部未知流向的具体冲突。
- 实参 call/read/write/receive/panic、删除实参效果、callee/receiver 单次求值、tuple、variadic、defer recover 和 go 注册时求值、short-circuit 与有副作用/别名 LHS。
- 默认表达式引用旧参数、包别名和 shadow、零值/私有类型、类型转换与函数体未解决冲突；不能只测常量 `1`。
- named/naked return、defer 改结果、删 error 后的实际使用迁移、增加结果时所有 return 路径。
- go.work 两个模块及 replace、不同 package test variants、dirty caller/implementation、中文+emoji 同一行、CRLF、unicode标识符、过期文档、关闭文件、取消/项目关闭。
- 每个安全样例：比较可观测效果 trace/输出（不是仅编译），整组 native Preview、Apply、一次 Undo、一次 Redo、再运行真实测试。失败/冲突样例：零修改，给出具体位置及原因。

真正无法静态自动安全完成的情况包括业务意义不确定的类型/删除值迁移、不可写的必要接口/实现、无法解析的构建分支、无法证明的动态函数流向和未知外部使用。完整 IDE 的表现应是可理解、可定位、可选择迁移的冲突处理；不是返回成功、只改一部分，也不是悄悄删掉副作用。
