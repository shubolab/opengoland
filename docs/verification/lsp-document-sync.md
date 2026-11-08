# 初始化期间文档同步 — 2026-11-09

本项追踪上一轮独立客户端出现的 `textDocument/codeAction: column is beyond end of file`，不把所有 LSP 请求竞争视为同一个问题。原始工作流见 [测试验收记录](native-test-workflows.json)。

## 已复现的原生缺陷

两次普通启动/重启控制未复现。随后对自有 fixture 使用透明 stdio relay，只暂缓官方 gopls 的 initialize 响应。在服务初始化尚未完成时，通过真实原生编辑器粘贴新函数，再放行响应。旧发行包首次发送的 `didOpen` 仍只有 41 个换行、不含新函数，且没有对应 `didChange`。新版编辑器随后请求 `(line=42, character=5)`，实际 gopls 返回原来的 `column is beyond end of file`。完整的请求/响应与快照摘要见 [协议证据](lsp-document-sync.json)。这是受控延迟下的原生复现，不是普通启动发生率测量。

固定的 [LSP4IJ 0.21.0 LanguageServerWrapper](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/LanguageServerWrapper.java) 在初始化前缓存文档文本，初始化完成后才创建同步器并注册监听器；这段连接代码没有持有 IntelliJ read action。期间的编辑既不在旧快照中，也没有监听器可报告。[DocumentContentSynchronizer](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/DocumentContentSynchronizer.java) 优先发送该缓存文本。磁盘即使已有新版文件，也不能替换服务端仍打开的旧 overlay。

## 构建内公开源码修复

保留固定公开版本与许可证头，用 [补丁](../../product/patches/lsp4ij-0.21.0-document-sync.patch) 作两处独立调整：

- 在后台 read action 内读取当前文档、创建同步器、注册监听器并捕获首次 didOpen；自有文档锁位于 read action 内，不持锁等待 read access。`waitForDidOpen=false` 仍建立原子基线，但不等待发送。
- didOpen 和 didChange 共用现有 FIFO dispatcher；通知 future 在实际分派后完成，删除原先 500ms 经验性等待。它不是服务端处理完成的 ACK。

[构建脚本](../../scripts/patch-lsp4ij.py) 核验原版 JAR、固定 commit 下两个源码文件以及补丁的 SHA-256，再编译并替换完整的 7 个 outer/nested/record class。它只修改私有开发/测试 sandbox 的副本，不改下载缓存、本机 GoLand/PyCharm 或其他已安装插件。发行包从该 sandbox 组装；JAR 内含来源和逐 class 摘要，构建 provenance 也覆盖补丁与来源文件。

## 验证与边界

新增 [真实上游回归](../../src/test/kotlin/com/redhat/devtools/lsp4ij/GoLspInitializationSyncTest.kt) 使用实际 wrapper、synchronizer、dispatcher 和 IntelliJ Document，只替代 remote service 与初始化 gate。覆盖初始化期间编辑、didOpen 不被增量修改超车、UTF-16 镜像、noWait 行为、异常 future，以及 classloader 实际加载完整补丁。5 项上游/原生同步回归全部通过，且 248 项 Kotlin 全量检查通过（无跳过）。在 `e388774d…` 独立发行包中重复同一 held-initialize 原生操作：首次 didOpen 已含新增函数（898 code points / 900 UTF-16 units / 43 LF），随后两次真实增量修改按版本 2、3 发送，包括 emoji 后第 55 UTF-16 列的插入。离线重放与原生保存文档逐字相等；5 个 Code Action 请求均无错误，新增 TestDuringInit 在原生测试树中运行通过。实际进程身份、补丁 class 摘要、请求和回包见 JSON。退出后 wrapper/gopls 均消失。这只验证该初始化连接窗口，完整 ROBUST/LSP 验收仍开放。

本次修复不证明所有 position 请求都在待发送 didChange 之后分派，也不关闭更广的 stop/disconnect 竞争。Code Action 与其他位置请求在快速连续编辑时的一致性仍需单独核查。
