本轮在独立 open Goland 客户端完成了一个有界的多光标同步验收：原生编辑器向两个导出字段的 tag 值粘贴 `新😀`，撤销后用 Quick Documentation 查询真实 gopls，再以未定义类型产生、撤销和重做两项编译错误。观察支持本 fixture 的生产同步链路正确工作，不据此宣称所有编辑竞态或 GoLand 行为已完整复刻。

本轮使用 `sync.go` 中的 `SyncProbe`，直接字段为 `A int`、`B int`，各自包含不同的 `json` options 与 `xml` tag。透明 stdio wrapper 原封转发请求、通知和响应；分析器只读捕获记录，不注入源码、版本或服务器响应。最终原始 trace 为 234,509 bytes，SHA-256 为 `9436d496284e61091a54338b2ccee855537b95226f1efcf4fb55aae7bfe47aa1`。选取的原始事件、源码快照及运行身份记录见 [multicaret-gopls-native.json](multicaret-gopls-native.json)。

分析器从 `didOpen` v1 开始，按每条 `didChange` 内部的实际顺序应用 UTF-16 ranges，核对边界、`rangeLength` 和严格递增版本。本轮 v1–v8 未出现重建错误。v2 加入包与类型注释；v3 在同一通知内包含两个插入事件，各插入 `新😀`，长度均为 3 个 UTF-16 code units。v4 包含对应的两个删除事件，保留原来的 `omitempty`、`string` options 和两个 `xml` tag。

首次 Quick Documentation 的真实请求 ID 是字符串 `"130"`（trace 353/354），返回 `新😀a,omitempty` 与 `新😀b,string`。v3 后、该请求前已经出现 `didSave`（348），因此这次查询只能证明服务器返回了新 tag，不能称为未保存内容的 hover 证据。

随后第一次 Cmd+Z 只恢复光标状态，没有生成文档变更；第二次 Cmd+Z 才产生 v4（363）。请求字符串 `"146"`（389/390）返回原来的 `a,omitempty` 与 `b,string`，v4 到该响应之间没有 `didSave`，下一次保存是 393。`probeDiskAtUndoHover` 检查点仍包含两个 `新😀` tag。因此，在本轮记录的操作顺序中，服务器确实返回了与磁盘不同的撤销后 overlay。该磁盘检查点是原生验收保存的内容记录；hover 协议本身不携带文档版本。

`actualServerHoverPairs` 中的形态匹配只比较被抽取的 A/B 字段类型与 tag，不是服务器返回完整文件 SHA 的确认。原 tag 在多个版本重复出现，分析器会保留所有相同字段形态的候选版本，不把这些匹配宣称为唯一的服务器版本。实际请求 ID 配对与返回内容是证据，队列 mirror 和通知 future 不充当服务器 ACK。

在 v3，gopls 真实返回两条 severity 2 的 `SA5008` 警告：`invalid JSON field name "新😀a"` 与 `invalid JSON field name "新😀b"`（342、352）。它们说明这次任意文本编辑产生了不符合 JSON field-name 约定的值，不能记作两项 compiler error，也不能据界面数字推断同步丢失。撤销恢复原 tag 后，这两条警告消失。

之后将两个字段的 `int` 改为 `MissingProbeType`，保持原 tag。服务器的诊断包含显式版本，且能唯一绑定到本轮重建快照：

| 文档版本 | didChange 行 | publishDiagnostics 行 | 真实服务器结果 |
| --- | --- | --- | --- |
| v5 | 407 | 409 | 两条 severity 1、`compiler` / `UndeclaredName`，内容为 `undefined: MissingProbeType` |
| v6 | 429 | 430 | Undo 后 `diagnostics: []` |
| v7 | 449 | 451 | Redo 后再次返回上述两项错误 |
| v8 | 473 | 474 | 再次 Undo 后 `diagnostics: []` |

原生界面对应观察为两项错误、清空、两项错误、清空。`probeDiskAtFirstErrors` 检查点不含 `MissingProbeType`；v5–v8 控制序列期间也没有该文件的 `didSave`，所以这部分验证的是未保存文档。该结论限定于这份完整、可解析的 fixture，以及记录到的四阶段操作。

自动回归 [GoMulticaretGoplsSyncTest.kt](../../src/test/kotlin/dev/openidea/goide/lsp/GoMulticaretGoplsSyncTest.kt) 使用真实原生 Editor、Paste、Undo/Redo、已打补丁的 LSP4IJ Wrapper/DocumentContentSynchronizer，以及自有实际 gopls 进程。它逐项比较 native `beforeDocumentChange` 与传输中的增量 ranges/版本，通过真实 hover、symbol 和版本化诊断验证服务器内容，并检查未保存源文件的磁盘原文。测试末尾另外验证 `didClose`、mirror 清理和服务器回退到磁盘内容。

SDK 单元测试模式中的 DocumentContentSynchronizer 会立即发送变化。上述生产客户端 trace 补充验证了正常提交路径：一次已提交的多光标操作可以将两个原生事件放进同一条 `didChange`，随后真实服务器返回相应结果。这两种证据互补，不能把单元测试的调度路径描述为生产事件循环。

独立客户端已正常退出；本轮自有进程退出情况、完整构建及测试 cohort 由相邻运行证据记录。最终 trace 的末尾只捕获 `envelope.go`、`envelope_test.go` 的 `didClose`，没有捕获 `sync.go` 单独的关闭通知，因此不声称本次原生退出为每个打开文件都发送了 `didClose`。

之前 struct-helpers fixture 快速粘贴/Undo 时出现的 3/5 界面计数未捕获对应诊断消息，原因仍未知。本轮 SA5008 只能解释本 cohort 的两条警告，不能追认解释先前现象。本轮也不关闭完整验收矩阵，不推导所有初始化、取消、关闭、并发编辑竞态或商业 GoLand 交互 parity 均已解决。
