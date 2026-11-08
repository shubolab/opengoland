# 原生接口方法生成 — 2026-11-09

这是完整 LANG-09 的接口方法部分，完整 IDE 等价验收仍开放。

## 参考与证据边界

[官方 Implement an Interface 指南](https://www.jetbrains.com/guide/go/tips/implement-an-interface/)
描述了标准 Implement methods 动作、为接口新建类型或选择既有类型，以及接口内嵌提示入口。
[REA 参考记录](../reference/method-generation-reference.json) 固定了本机 Go 插件
JAR 的 SHA-256、公开资源 descriptor 的摘要，以及原生 implementMethod 扩展和动作声明。
未反编译商业实现，也未将其模板或类装入产品；商业对话框、默认方法体与实际运行行为未知。

## 独立实现

Go 的 typed planner 支持从 receiver 或 interface 进入，选择既有本地定义类型或新建 struct，
pointer/value receiver、接收变量名、泛型接口参数以及全部或部分缺失方法。接口目录包含当前
工作区、导入闭包和常见标准包；明确包路径可加载额外 SDK/依赖接口，并与工作区一起完成同一
go/types universe 的分析。不是靠拼接错误提示或把两个独立加载结果的类型当作同一类型。

它检查实际方法集、promoted methods、同名签名、字段冲突和包私有方法，保留接收类型所在源文件
的 build constraints、导入别名和未保存文本。已有缺失方法断言可以由候选消除；最终完整候选需
重新 type-check。全部缺失方法生成后还验证真实 Implements；部分选择明确保留未完成状态。
方法体明确 panic TODO，规划不执行用户程序或测试，也不直接写项目文件。

标准 Code > Implement Methods 通过开源平台的 LanguageCodeInsightActionHandler 接入。
原生对话框保留最后成功分析的实际请求与 IDs；重名接口以包路径区分；可编辑下拉框提交与无变化焦点事件保留所选 wire ID，真实输入变化仍失效旧分析。改动分析字段会禁止 Preview，异步回包按序号、
存活和模态作用域接收。额外加载接口的 catalog ID 在冲突重开后保留。配置、文档和依赖快照使用
不可变输入；选定方法经 Diff 预览、前置条件复验和一次全局 Undo 命令应用。

## 当前验证

真实 RPC smoke 已通过独立 worker 验证未导入 io.Reader 的 inspect/plan、缺失 Read 方法、
完整 type-check/Implements 和规划时磁盘不变。全仓库 Go race（172 个测试事件，含子测试）
及 vet 已通过。真实 Kotlin heavy integration 进一步验证 dirty 源文档、完整 native apply、
一次全局 Undo/Redo 和 stale 依赖拒绝。

[原生桌面记录](interface-generation-native.json) 固定 `4b8c567b…`（245 输入文件、269 项
Kotlin 测试）的实际发行包：接口 inlay 渲染及 Cmd-click、标准 Ctrl+I、既有 Stream 的
Read/Close 生成、未保存 Unicode 修改、Diff/Apply/Undo/Redo、填入实际方法体后 TestStream
通过、新建 ValueStream 的 value receiver 生成/撤销/恢复，以及多目标导航的命名标题与
receiver-qualified 候选。运行身份 verifier 只证明应用及插件加载，功能结论来自上述真实交互。

该过程也校正了一次自动化误判：部分 AX 点击只聚焦 JButton；Space 实际执行 Analyze 后旧包
正常启用 Preview，不能把此前现象记作产品分析失败。后续下拉框无变化事件与重名条目保护
有独立 Swing 回归，最终新构建另附身份与补充检查，不把旧原生证据归给新源码。

## 尚未关闭

当前入口面向工作区中的本地可写 Go 文件；从只读 SDK 源定义直接选择项目目标文件的流程、
完整 struct helper 生成和所有跨构建上下文迁移仍未验收。商业默认体与 UI 布局等价也未证明。
解析到的 inline 提示仅作为动作入口，真正类型分析由后台 planner 完成。


补充构建为 `86365f300c24abe945d91258351ba9b742b6f9ba0bb42f3b3b1854a7d1c6215b`
（245 个输入文件）：271 项 Kotlin 测试无失败/跳过，结构、provenance 和 strict codesign
校验通过。该包原生显示了带包路径的两个 rand.Source，并重新分析 Stream，将 Read/Close
正确识别为 existing；Escape 取消未创建额外 FinalStream。完整生成/Undo/运行仍归属于前述
4b8c 桌面 cohort，不能把其结果改写为新源码的全流程复验。


交付构建为 `57be2c7437c6a4c34118cba45282454080304a0fbb162f9cef956a68849aa914`
（同为245输入文件）：清理一处源文件EOF空行后重新构建，271项测试、结构、来源及签名再次通过。
不假设编译class字节相同：记录比较的392个class中2个摘要变化，Go engine保持字节相同。
交付包另行原生验证了标准入口、重名接口区分与已有方法检测，并记录其独立进程身份/退出。
完整生成与真实业务测试仍保留4b8c cohort的原始归属。
