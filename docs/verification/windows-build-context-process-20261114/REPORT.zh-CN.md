# C1 Windows 构建失败：外部文件断言与进程清理

**状态：`.20261114.1` 未发布。** 固定源码为 `cac85013896b0508d88dab9f8c42d5bbc388fec0`；原 tag 保留，不移动。后续修正要使用新的源码与版本。本记录没有运行新回归，也没有声明问题已修好。

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

本目录只提交两份原JUnit XML及紧凑来源记录，没有完整CI作业日志、API凭据、AX、配置或profile。

## 本机窄修检查（Windows 仍待新 CI）

生产 Loader 已改用既有 Windows 原子 Job owner，不再采样后代；测试改用实际文件身份。新增三个真实进程控制覆盖孤儿、首次检查前取消、独立 sentinel，以及私有环境/目录/UTF-8/非零退出。五类本机针对性测试 **38 项 / 5 报告通过，零失败、错误和跳过**。首轮仅新夹具的 Unix PTY 换行预期失败，修正为无换行精确输出后通过，生产解码未改。本机是 macOS，不能代替 Windows 分支的实际执行；新源码 `.14.2` 的完整门禁与 Windows 构建结果仍需取得。
