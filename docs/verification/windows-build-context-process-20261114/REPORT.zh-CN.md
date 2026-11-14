# C1 Windows 构建失败：外部文件断言与进程清理

**状态：`.20261114.1` 未发布。** 固定源码为 `cac85013896b0508d88dab9f8c42d5bbc388fec0`；原 tag 保留，不移动。后续修正要使用新的源码与版本。本节保留C1当时的失败；后续本机检查另列，尚未取得新源码Windows验收通过。

[生产流程43517336656](https://github.com/shubolab/opengoland/actions/runs/43517336656) 的 attempt2 已结束，Windows [job132233144372](https://github.com/shubolab/opengoland/actions/runs/43517336656/job/132233144372) 失败。独立读取原始 Windows artifact 的173份XML，统计为 **966项、2项失败、0错误、0跳过**；原 ZIP 的大小和 SHA 与 API 相符，压缩包没有 EXE，因此没有这轮 Windows 安装通过的结论。[紧凑记录](failure-summary.json)保留确切身份、两个失败与来源摘要。

## 两个失败分别是什么

1. **外部内容事件的断言误判。** `GoColdExternalWatchTest.testAbsoluteExternalWatcherDiscoversUnopenedSourceAndVfsSeesItsDiskChange` 失败。原XML明确列出了真实内容变化事件：外部文件长度从63变成74，stdout也记录了 `contentsChanged`。C1却直接比较 `it.path == f.source.toString()`；Windows的路径分隔符表示不同，字符串不等并不表示事件没发生。这项证据仍是受控VFS测试，不能当作原生监视、gopls或Windows交互已经验收。
2. **正常查询收尾误选了当前JVM。** `GoLocalDependencyScopeLoaderTest.testVendorLocationsDoNotWatchTheUnusedOriginalReplacementTree` 失败，异常为 `destroy of current process not allowed`。失败栈从JDK `destroyForcibly` 指向生产 `GoRunBuildContextLoader.execute$stop:630`、`execute:666`。固定C1源码会用 `process.descendants()` 快照记住子进程，随后强制终止仍存活的句柄；正常查询的 `finally` 也走这条清理路径。这次清理把当前测试JVM交给了终止操作，JDK拒绝自杀并抛错。它是生产进程所有权边界问题，不能归类成仅有测试断言写错。

两份 [冷VFS原XML](TEST-com.redhat.devtools.lsp4ij.GoColdExternalWatchTest.xml)与[Loader原XML](TEST-dev.openidea.goide.run.GoLocalDependencyScopeLoaderTest.xml)按原字节保留，各自只有1项失败；[清单](manifest.json)记录本地源文件、原ZIP成员及 SHA。

## Mac 记录和早先 runner 失联

相同C1的Mac **attempt1** 已通过966项 /173报告。原 artifact `14083677862` 为940,591,858字节，API/下载 SHA为 `1a73e933a2b277a7da8c3dc4422ded8e21732125137f4ca626ea3ecd1578f58a`；其DMG为1,023,853,737字节，SHA为 `a0710f86a4ff1439f39332498a61ee7ee53ca9b9e0d5e59711857c8f88061acd`。attempt2的Mac job只是复用成功结果的API别名，没有再次构建。来源及原始proof在摘要的 `macosSeparateCohort` 中单独绑定，不能把它和Windows失败合写为双平台发布成功。

Windows **attempt1** 的GitHub注解只说明hosted runner失去联系，详细日志不可取，根因仍未知。没有证据把这次attempt2的进程清理异常指定为那次失联的原因。本机C1 App4和GIF也保留原日期、版本与源码，不改标为后续版本。

## 修复正在做，尚未验收

后续方向是修正平台路径比较，并复用Go engine已有的原子Windows Job所有权，移除这条生产 `descendants()` 清理。需要主线程完成针对性与完整门禁，再用新源码跑Windows CI；届时才补修复结论。当前不把计划、Mac通过或局部测试当成Windows已修好，也不增加完整GoLand验收结论。

本目录保留两份C1原JUnit XML、后续C3的原始测试XML压缩包及紧凑来源记录，没有完整CI作业日志、API凭据、AX、配置或profile。

## 本机窄修检查（Windows 仍待新 CI）

生产 Loader 已改用既有 Windows 原子 Job owner，不再采样后代；测试改用实际文件身份。新增三个真实进程控制覆盖孤儿、首次检查前取消、独立 sentinel，以及私有环境/目录/UTF-8/非零退出。五类本机针对性测试 **38 项 / 5 报告通过，零失败、错误和跳过**。首轮仅新夹具的 Unix PTY 换行预期失败，修正为无换行精确输出后通过，生产解码未改。本机是 macOS，不能代替 Windows 分支的实际执行；新源码 `.14.2` 的完整门禁与 Windows 构建结果仍需取得。


## C3 本机完整门禁：新增夹具在退出后读取身份失败

后续C3源码 `e6264e4abeab13ecfa0384c2f61c2997d26a0957` 的 `.20261114.2` **本机**完整门禁为969项 /174报告，968项通过、1项失败、0错误、0跳过；没有组装出新的App。唯一失败是新增的 `GoRunBuildContextProcessOwnershipTest.testCompletedLauncherCannotLeaveAChildHoldingTheMetadataPipes`：命令退出后，夹具在 `recorded():131`（测试调用处第26行）查询PID91318的身份，观察到 `isAlive=true`，但可选的 `info.command` 已不存在，因此无法确认身份。操作系统快照为何呈现这种不一致，原因仍未知；这项失败本身不能归类为生产修复回归，也没有给出新的Windows执行通过证明。

[紧凑C3摘要](c3-local-fixture/summary.json)与[原始174份XML压缩包](c3-local-fixture/test-results.tar.gz)绑定该源码、587个输入及原始SHA。针对性38项曾通过，随后完整运行发现这项夹具时序问题，两次结果分别保留。三个新增进程所有权测试和全部断言继续要求通过；下一步仅在helper父子进程都被屏障保持存活时记录身份，再允许孤儿场景的parent退出，关闭夹具复用已经捕获的身份。待C4源码重新通过完整门禁及Windows CI后再补结论，当前不声称已修好。

C3的云端run [43532637816](https://github.com/shubolab/opengoland/actions/runs/43532637816) 由任务所有者为避免浪费主动取消，终态为 `cancelled`；它不是一份云端测试失败结果，也没有为C3创建 `.14.2` tag。本机C1的 `.14.1` GIF继续保留原版本、日期和源码，不改标为C3或之后的候选。

## 屏障夹具的后续针对性检查

将 orphan 父进程在发布PID后保持存活，在首次循环检查内先记录并验证父子身份，再释放父进程正常退出；退出后只检查原句柄。夹具清理复用已知身份，未知活进程仍失败且不终止。五类针对性检查再次38/38通过、零跳过。尚需新源码的完整门禁与 Windows CI，此处不将本机针对性结果写成新的安装器已通过。

## C4 安装后启动失败（独立证据）

C4 的 Gradle、EXE 生成与安装均成功，但安装后 `-version` 已记录 TerminalEngine 静态服务初始化 SEVERE；后续 GUI gate 读到旧日志拒绝继续，未证明已达项目窗。Mac 成功不等于双端可发布。完整失败事实及有界取证边界见 [c4-installed-startup](c4-installed-startup/README.md)；后续候选仍待完整构建与实际启动验收。
