# C10 独立客户端：首次补齐目录仍未通过

本机候选版本为 `0.1.0-preview.20261114.4`，源码固定在 `7e4679b9cf6faf281a7b223ad3809d41e770e643`。**它尚未发布，没有14.4安装包或tag。** 本轮实际打开独立macOS App后发现：第一次补齐依赖目录仍保留旧导入错误；随后只修改外部源码，编辑器可以自动清错。因此首次缺根恢复还没有完整修好。

## 构建与现场身份

新App位于本机独立的 `build/product-candidate10/open Goland.app`，保留原来的C9 App及14.3实际安装包。打包前强制重建Go引擎，核验其中的Git提交为本次源码、`vcs.modified=false`，避免增量任务复用旧的提交信息。

这次实际打包重新通过 **1028项测试、179份原始XML，0失败、错误或跳过**，并通过插件结构及App签名检查。596个构建输入在打包前后不变，逐个匹配提交的Git原始内容；Jar内嵌清单与插件版本也经过独立核对。产物manifest摘要为 `2c32306ef8ffba09c01e90862545d33e2bb8f349f6fd693f891128cae828a58a`。这些结果证明构建来源和测试范围，原生行为仍需另外观察。

实际运行使用新的system/plugins/log目录、此前自有测试配置的副本和默认内置gopls。IDE进程为69073，fsnotifier为69095，gopls为69270/69271。只打开调用方 `main.go`；没有编辑调用方、打开外部依赖文件、手动Reload或重启服务。三个调用方文件的SHA始终相同。

## 三个阶段的实际结果

| 阶段 | 独立源码副本的真实Go命令 | 独立App中观察到的结果 |
| --- | --- | --- |
| 首次打开，`../dep`不存在 | Run/Test报缺失replacement目录 | Problems显示旧的`could not import`错误，符合初始预期 |
| 原子创建完整依赖，只定义`Greeting` | 已能加载依赖，改报`undefined dep.Message` | 随后封存的界面仍为旧导入错误，未达到预期 |
| 只把外部函数改回`Message` | Run输出`依赖-v1\|中文😀`，无缓存Test通过 | Problems显示`No problems in main.go`，编辑器出现绿色检查标记 |

Greeting阶段的操作窗口约4分26秒，期间进行了离散观察与截图；没有连续录屏，也没有测量精确的诊断响应时间。新依赖根的inode为91377860，后续修改函数时保持不变。CLI始终在小型源码副本中执行，不把CLI的Run/Test称为IDE原生Run/Test。

[首次缺根画面](initial-absent-dependency-20261114-candidate10-evidence/baseline.png)、[补齐Greeting后仍旧报错](initial-absent-dependency-20261114-candidate10-evidence/created-greeting.png)、[仅修改Message后清错](initial-absent-dependency-20261114-candidate10-evidence/restored-message.png)保留REA原始PNG字节。证据ID分别为 `ev_35156cce…f9f09`、`ev_2402bf3a…fb42e0`、`ev_1417b9a1…f5f73b`，完整ID、截图摘要和限制见[结构化摘要](initial-absent-dependency-20261114-candidate10-evidence/summary.json)。

正常退出时，在确认窗口选择Exit。退出后四个已记录进程均不存在，当前可见进程中也没有以C10 App内程序路径启动的实例。C10四个关键载荷及C9关键载荷摘要未变，签名检查通过，完整IDE日志中没有ERROR/SEVERE行。配置、源码、原始日志与截图均保留；这不把没有错误日志当作内部查询成功的证明。

## 下一步验证什么

当前源码只在补充加载后发送“依赖根目录Changed”通知。对本机校验过的官方gopls 0.24源码进行只读检查后发现：目录通知展开的是快照已经认识的文件，不会据此扫描完全冷的新目录。这与“后续实际`.go`文件修改才清错”的观测一致，**但尚未通过真实协议对照证明它是唯一根因**。

下一步用实际gopls保存两个方向的原始协议帧，对照目录通知和精确文件通知；再补真实wrapper启动、已打开的调用方、自动capture及pending探测的完整接线测试。已有SDK/VFS用例和生命周期用例分别覆盖了其中的部分链路，不能代替这条完整原生链。

本轮没有执行原生Test/Run，也没有执行分批写入或稳定根新增子树的原生流程。待修复并重新构建后，继续这三组原生流程及双平台安装器验证。公开下载仍是14.3，完整验收矩阵和goal继续开放。约0.8MB的[证据清单](initial-absent-dependency-20261114-candidate10-evidence/manifest.json)包含原始截图、CLI对照、原始打包测试报告与日志；完整App及4.9MB原manifest暂留本机，不把这个证据目录当成安装包。

结束后已单独清理本次 `atomic/profile/system` 缓存，保留profile父目录、config/log、源码夹具、C9/C10 App、共享测试缓存和实际14.3资产。先保全132条原字节、593,991字节（含1份XML），再核对944文件SHA、1,201项目录身份及新鲜进程/FD；逐项删除只允许封存的原目录项。操作窗口的卷空闲增加301,617,152字节，结束时40,384,040,960字节（约37.6GiB）；这个差值可能包含并行写入影响。见[退役记录](../verification/generated-system-retired-after-candidate10-20261114.json)。上述旧system路径现在不存在，原始日志与截图仍在。
