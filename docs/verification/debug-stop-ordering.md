# 附加调试会话的 Stop 顺序与 adapter 回收

记录日期：2026-11-09。本文区分固定公开源码所证明的调用顺序、自动化测试、直接工具探针和独立客户端验收。**历史 macOS 暂停后 detach 偶发留下停止进程的问题仍未被证明全面解决；完整调试验收保持开放。** 原有失败见 [native-debug-recovery.md](native-debug-recovery.md#native-detach)。

## 已确认的前端时序问题

固定版本的公开实现建立了以下调用链：

1. [LSP4IJ 0.21.0 `DAPDebugProcess.stop`](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/dap/DAPDebugProcess.java#L264-L284) 在后台执行 dispose，再调用原生 session.stop；dispose 会调用 client.terminate。
2. [LSP4IJ `DAPClient.terminate`](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/dap/client/DAPClient.java#L496-L532) 的 DAP disconnect 是异步请求。先前本项目的附加客户端同样立即返回，再在响应或超时后关闭连接。
3. [OSS IntelliJ Platform 2026.1.4 `XDebugSessionImpl.stop`](https://github.com/JetBrains/intellij-community/blob/idea/2026.1.4/platform/xdebugger-impl/src/com/intellij/xdebugger/impl/XDebugSessionImpl.kt#L1201-L1213) 对尚未结束的 process handler 执行 detach 或 destroy；本地 adapter 的 handler 使用 destroy。

因此，旧的本地 adapter handler 可以在 disconnect 确认前收到平台终止请求。这是一条已确认、可以单独验证的产品生命周期竞态；它不等于已证明历史 `Ts` 失败只由该竞态造成。

[Delve 1.24.2 的 disconnect 实现](https://github.com/go-delve/delve/blob/v1.24.2/service/dap/server.go#L1265-L1390) 对单次附加会话先停止调试操作，再执行非终止 detach；[gdbserial 后端](https://github.com/go-delve/delve/blob/v1.24.2/pkg/proc/gdbserial/gdbserver.go#L1083-L1108) 还需要完成调试 stub 的退出。提前发出另一条 adapter 终止路径会与这些清理步骤竞争。对 headless multi-client 会话，该固定版本明确保留当前运行或暂停状态；不能把本地附加修复推广为自动恢复所有远端目标。

## 当前实现

- [GoConnectionDescriptor.kt](../../src/main/kotlin/dev/openidea/goide/debug/GoConnectionDescriptor.kt) 为本项目启动的本地 adapter 安装专用 handler；外部 DAP/headless 配置不创建该本地进程。
- `GoAttachedDAPClient.disconnectAttached()` 以原子共享 future 合并客户端 terminate 和平台 handler Stop。已有 DAP 连接只发送一次 `disconnect`，且明确设置 `terminateDebuggee=false`。没有连接时直接结束客户端；失败或超时也会结束客户端连接。
- [GoAttachedAdapterProcessHandler.kt](../../src/main/kotlin/dev/openidea/goide/debug/GoAttachedAdapterProcessHandler.kt) 在分派 disconnect 回调前先启动独立清理计时器，再将回调放到后台。回调自身阻塞、尚未返回 future 时，清理截止时间仍然有效。EDT 不等待 RPC、进程退出或计时器。
- 收到成功确认后，先允许 adapter 自然退出，不立刻追加终止信号。失败、超时或自然退出宽限到期后，只对该 handler 持有的确切 Java Process 请求正常终止；继续存活时才对同一 adapter 强制结束。所有路径都禁止递归销毁进程树。
- 默认阶段各为 3 秒：等待 detach、确认后的自然退出宽限、正常终止后的强制回收宽限。最长阶段路径为 **3 + 3 + 3 秒**，另有线程调度开销；未确认的路径会跳过自然退出宽限。已退出进程使后续计时回调失效。

代码不查找或向被附加的 PID 发送 SIGCONT、SIGTERM 或 kill，不为规避 detach 添加 Continue，也不改变 headless multi-client 的暂停保留策略。项目关闭仍通过注册的 disposable 请求这一条有界回收路径。强制回收的是本项目拥有的 adapter；这不能证明底层调试器在所有异常情况下均已恢复外部目标的运行。

## 已执行的自动化验证

最终构建输入 SHA-256 为 `448a585dc11282af6a9d5d8fb9b2670f2947ee804db7c4e61afc0058f276409a`，共 **192 个输入**。根线程执行的完整 Gradle 测试通过 **229 项，0 failures、0 errors、0 skipped**。下文原生附加观察来自明确标注的较早包，不能将其构建身份混写为最终包。

[GoAttachedAdapterLifecycleTest.kt](../../src/test/kotlin/dev/openidea/goide/debug/GoAttachedAdapterLifecycleTest.kt) 的 3 项测试均通过，总计 **6.978 秒**。测试使用真实 `DAPDebugProcess.stop`、LSP4J socket 协议和自建 Python adapter 进程；session.stop 边界为与固定 SDK 源码一致的测试替身，DAP peer 可控制确认时间。它们不冒充原生 UI 或真实 Delve detach 验收。

| 测试 | 实际核查内容 | 本次用时 |
| --- | --- | ---: |
| `testNativeStopWaitsForOneSharedDetachAndTheAdaptersNaturalExit` | peer 暂不确认时，实际平台 Stop 已到达 handler，但自建 adapter 仍存活且未收到 SIGTERM；重复 terminate 共用一个非终止 disconnect；确认后自然退出，没有自动 Continue | 0.375 秒 |
| `testDetachDeadlineReapsOnlyTheOwnedAdapterWithoutBlockingTheEdt` | EDT Stop 返回不等待确认；截止时间回收忽略 SIGTERM 的自建 adapter；它创建、带独立 token 的子进程仍存活，之后由测试单独清理 | 5.470 秒 |
| `testBlockedDisconnectDispatchCannotPreventTheIndependentCleanupDeadline` | 人为阻塞回调本身、使其尚未返回 future；独立截止时间仍终止自建 adapter，测试最后解除阻塞并清理 | 1.133 秒 |

这些证据证明回收时序和拥有权边界，不能证明任意被附加 Go 目标的业务执行、心跳或暂停状态。

## 直接 Delve 探针

使用实际 **Delve 1.24.2**，只启动、附加和清理探针自己的 Go fixture 与 adapter：

- 断点处暂停/已继续，分别保留/清除断点，4 组各重复 3 次，共 12 例。
- entry stop 后检查 scopes；8 例均遇到 globals 失败，并完成临时关闭 globals、读取 locals、恢复 globals 的序列。再组合继续/不继续与保留/清除断点，共 8 例。

**20 例全部通过**：非终止 disconnect 后自建目标仍存活且心跳继续；探针最终确认自己的 adapter 和 fixture 均已清理。本地原始记录为 `.tools/debug-detach-probe.json`、`.tools/debug-detach-entry-probe.json` 和 `.tools/debug-stop-source-review.json`。这些本地诊断记录不替代可迁移 CI 或原生发行包验收。

此组探针没有重现旧的间歇性 backend 停止现象，也没有通过新原生 handler；因此它是有限范围的工具观察，不是该历史问题已修复的结论。

## 独立客户端验收：本轮暂停附加/Stop

本轮 CUA 操作使用上述 `2c8f0472cc35286010bdda002606aaee4214b3e4368d0a051efe8a6846662515`、190 输入的独立应用，应用 PID 为 **93844**。目标 **93833** 由本轮验收创建，可执行文件为 `.tools/native-test-workflows-b9avg91q/owned-attach`；PID、路径及 UUID 已与本地 metadata 核验，没有附加其他用户进程。

原生 **Go Attach to Process** 配置填写 PID 93833 后执行 Debug。应用启动自己的 Delve adapter **95024**，其调试 stub 为 **95029**；编辑器停在 SDK `proc.go` 的 entry stop。此时 OS 状态为 `TXs`，两次相隔约 0.3 秒的心跳读取为 **33377 → 33377**，与暂停状态一致。

本轮没有发出 Continue，也没有添加断点。通过实际原生 Stop 按钮结束该附加会话后，控制台显示退出码 0。随后 OS 核查得到：

| 身份 | Stop 后观察 |
| --- | --- |
| 自有 adapter 95024 | 已消失 |
| 自有 debugserver 95029 | 已消失 |
| 自建目标 93833 | 仍存活，父 PID 为 1，状态 `Ss` |
| 目标心跳 | 约 0.3 秒内 **33542 → 33552** |

因此，本例的目标在暂停时直接执行原生 Stop 后仍然存活并继续运行。应用随后也通过 CUA 确认 Exit 退出。

**证据边界：**本轮没有单独捕获实际 disconnect 请求与 wire ack 的顺序，不能把控制台退出信息或进程结果写成抓包证明，也不能据此判断是否使用了某一清理退路。前述自动化测试独立证明受控确认和截止时间下的时序约束；本节只证明这一次原生附加/Stop 的实际结果。一次成功不推翻此前间歇性 backend 停止的失败记录，外部 DAP 和 headless multi-client 的对应行为也未在本例重验。

本文件保持 DEBUG 和完整 GoLand 对齐矩阵的其余验收项开放；后续构建的测试或打包成功不自动继承本例原生证据。
