# 原生 Go 测试生成与导航 — 2026-11-09

本项补充 LANG-09 的测试生成部分；接口方法、结构体帮助方法以及完整 GoLand 行为验收仍开放。

## 参考的证据边界

[GoLand 2026.1 官方文档](https://www.jetbrains.com/help/go/2026.1/create-tests.html) 声明 Code > Generate 的 Empty test file、Function、File、Package 四种范围，以及标准 Test/Subject 导航。这是公开功能声明，不是安装版本运行证明。官方也有 [泛型测试生成说明](https://www.jetbrains.com/guide/go/tips/generate-a-test-for-generic-function/)。

本轮按 REA 技能只读核查本机 `GoLand.app/Contents/plugins/go-plugin/lib/go-plugin.jar`，SHA-256 `198efc87e023e13f166248e56421c90dab630dbd2552c756d172a56ca02155f0`。复用 inventory `ev_6e83a4534edd89427f4d3a3376a4298d405d86d086458de6bdecc6149fc82e8c`；本轮精确 occurrence view 为 `ev_70c7f14b24c3a4f5a3cf329ae60dc7483e46bdc251917bd9818f23a107c45991`，对应 inspection `ev_1bbe444ca99f806c351f57d339609565400246c3efe20f3108b70a77845a8cf3`。

| 只读资源 | 字节 | SHA-256 | 观察 |
| --- | ---: | --- | --- |
| `fileTemplates/internal/Go File.go.ft` | 27 | `d639f54cfcc25e5793992bcb93f3cc722f4b906a4319c107d22aac05f2444d8a` | 只有 package 占位声明 |
| `fileTemplates/code/Go Test.go.ft` | 68 | `c5893fae045ca181305683122922074d78fc1d8d595d484217684c8f5175f85e` | 单个空测试函数和光标占位，无 Skip |
| `fileTemplates/code/Go Table Test.go.ft` | 239 | `bf841f0a3b25304d45e1af94985a5b506aa2e0f43da942d93551d560693cecba` | 表和 Run 循环模板 |

逐项字节摘要与 REA node 一致，资源未复制入仓库，也未读取/反编译商业实现类。**未知：** Empty test file 动作究竟选哪个模板、是否额外插入函数，静态资源不能证明。我们当前 Empty test 是显式 Skip 的待实现函数；空 table 也显示 Skip。这是当前产品行为，不能声称与商业默认空文件/模板完全相同。

## 独立实现

[Go 后端](../../engine/internal/generate/tests/) 使用真实 AST、go/types、所选 SDK/环境/标签及 dirty source/module/workspace overlays；复用签名 planner 的语义快照和磁盘/目录前置条件。它返回标准 CreateFile/TextDocumentEdit，既不写项目文件，也不运行用户 init 或测试代码。

- Function、File、Package 选择声明；Empty 选择待实现测试名称。同包与 external 模式独立校验。
- 变参字段使用 slice，实际调用保留 `...`；方法保留 receiver。泛型函数和泛型 receiver 需要显式具体类型，并验证约束。
- 测试包内任何名称冲突均拒绝；已有默认同名测试不自动选中。外部模式不选择私有声明，不可命名的私有参数/结果类型形成明确冲突。
- 新文件默认按对应源文件分配并保留 build constraints/平台后缀；可显式指定同目录目标，包模式或约束不兼容时拒绝。已有测试、注释、导入别名和未保存文本保留。
- 完整候选重新 type-check，并证明生成函数实际进入 active test graph，防止被平台文件名排除却误判已验证。当前不迁移所有不同构建上下文。

[原生对话框](../../src/main/kotlin/dev/openidea/goide/generate/tests/GoGenerateTestsDialog.kt) 提供范围、包模式、声明选择、测试名、具体类型和可选目标文件；重分析期间禁止 Preview，回包按序号/存活状态校验。旧分析和关闭对话框会取消自己的后台 operation。UI 无文件读取或进程操作。

[服务](../../src/main/kotlin/dev/openidea/goide/generate/tests/GoGenerateTestsService.kt) 捕获原生文档与 Go 设置，后台调用独立 worker。所有文件创建与文本更新通过现有 Diff Preview、前置条件检查和单一全局 Undo 命令应用；过期依赖/文档/设置拒绝整组修改。标准 TestCreator/TestFinder 接入原生导航；名称提示有歧义时保留多个候选，不把词法关系宣称为类型证明。

## 当前验证状态

Go 新包的 12 项实际 fixture 测试已通过局部 race/vet，包括生成后真实编译、变参/方法/泛型、external、dirty metadata、build exclusions 和 worker/driver 取消。真实 RPC smoke 已证明 inspect/plan 经独立 worker 返回可预览创建操作，规划时磁盘不变。

初版统一检查 248 项 Kotlin 测试全部通过，无跳过；全量 Go race 测试通过 150 个测试事件（含子测试），vet 通过。真实 engine RPC → typed plan → native apply 的集成验证了 Unicode/空格 URI、dirty 源文档、两个 CreateFile/TextEdit、一条全局 Undo/Redo，以及依赖变更拒绝整组修改。测试也暴露了 Local History 项目索引查询缺少读锁，生产代码已用短 read action 修复。

[独立桌面记录](test-generation-native.json) 保留 `e388774d…` 构建的四个 Generate 入口、模态对话框内 Function→Package 后台切换、泛型具体类型填写、未保存 `[]string` 源签名、两文件 Diff/Apply 和原生一键 Undo/Redo。标准 Test/Subject 导航双向可用；空表明确 Skip，填入 Unicode 用例后在原生测试树真实通过。

交叉审查另发现 live 设置 A→B→A 可令分析上下文与捕获配置不一致。后续修复让生成 payload、project/info 与 engine 连接全程使用同一个不可变设置，并将请求绑定具体初始化连接；新增两项回归，最终 250 项 Kotlin 全量测试全部通过（无失败、无跳过）。最终独立包输入摘要 `3d8bd9dade696a845be76ab221b523e65162d6c8f967f1b796f461ce9b63df79`（220 文件），结构校验、provenance 校验和 `codesign --verify --deep --strict` 通过。初版桌面记录与最终版补充检查分开保存，避免将旧原生证据归给新源码。最终版已原生验证 external 模式、`Identity[int]`、取消预览不创建文件、再次 Apply 和真实用例运行通过；运行身份验证只证明应用及插件加载，功能结论来自对应桌面操作与真实输出。

开放边界：Test 选择器的声明标题出现 null；Undo 删除已打开新文件时，gopls 尚可能对在途 codeAction/semanticTokens/inlayHint 报 stat 不存在。恢复文件与实际运行成功，但删除/在途请求竞争不因本轮生成验收而关闭。Empty 默认模板和商业运行行为仍未知，LANG-09 的接口方法与帮助方法也未完成。
