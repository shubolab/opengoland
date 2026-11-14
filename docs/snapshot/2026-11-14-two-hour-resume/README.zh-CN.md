# open Goland 两小时续接快照

本轮从 `ef1a565` 继续，用户给定两小时上限：**2026-11-14 14:43:37–16:43:37 UTC**，也就是北京时间 **11月14日22:43:37至11月15日00:43:37**。本文件记录实际结果和后续入口；完整 IDE 目标仍未完成。

## 这轮处理的问题

项目通过 `go.mod` 的 `replace` 引用本机另一个目录。第一次打开项目时，那个目录还没有创建；后来把依赖补齐，命令行已经能看见新代码，编辑器却可能保留“找不到依赖”的旧错误。期望是补齐依赖后自动更新，不需要改调用方文件、重开项目或重启 Go 代码分析服务。

此前已经补上恢复通知：除依赖根目录外，对确实存在的 `go.mod` 发送精确文件变化通知。本轮首先补齐全量回归，再构建新的独立客户端 C11 验证。C11 是本机候选编号，版本字符串为 `0.1.0-preview.20261114.4`，尚未公开发布。

## 源码和测试

- `ea3084e`：补充[图标核对说明](../../reference/go-icons-resume-20261114.zh-CN.md)。截图中的重复青色 GO 方块此前已替换为区分源文件、测试、模块和工作区的独立图标；功能侧栏也已区分。本轮实际 C11 窗口能看到 Go、测试和模块图标，浅色及工作区文件的本轮复核仍未做。
- `7b98f16`：让真实 gopls 线级测试自行建立缓存，修正对开发机 `GOCACHE`、`GOMODCACHE` 的依赖。在缺少这两个环境变量的条件下，新测试和此前超时的精确调试用例共2项通过。
- 本机 C11 从干净的 `7b98f1672fc8097d4493ea1481db21ca8a12cdde` 构建：**1029项测试／180份原始XML，0失败、错误或跳过**；插件构建和结构检查通过。旧C10的1028个测试条目全部保留。597项构建输入与该提交的Git原始内容一致，构建前后不变；独立签名检查通过。
- 同轮首次并行重测试曾出现超时：SDK全套1029项中1项调试工具启动超时；Go race出现失败并被主动结束。原始失败记录保留。随后串行完整SDK回归通过；`go test -race -p 1 -count=1 -json ./...` **349项测试通过、0测试跳过、0失败**，12包通过、cmd包无测试，vet通过。并行资源竞争是可能因素，未证明它是唯一原因，也没有修改超时或跳过用例。
- `c15e102`：新Windows构建暴露测试可移植性问题：NIO返回空的目录 `fileKey`，测试在进入恢复通知前就退出。现在保持NIO身份比较，并在Windows需要时使用真实卷号及128位文件ID；前后目录身份仍必须相等。修正后的本机定向1项通过；Windows分支仍需新CI实测。

C11独立App仍绑定 `7b98f16`。`c15e102`只修改测试身份采样，未更改生产代码；本轮没有重新打包一个采用该测试修正的新本机App。详见[证据摘要](../../reference/cold-dependency-candidate11-20261114-evidence/summary.json)和[归档清单](../../reference/cold-dependency-candidate11-20261114-evidence/manifest.json)。

## 原生客户端实际进展

C11位于本机 `build/product-candidate11/open Goland.app`。App manifest SHA为 `3a79892e972b71a8c2274e060abf17a3daf4b7a9b268020d8c67c7e2e467c251`。内置引擎的实际构建信息指向 `7b98f16`，`vcs.modified=false`；引擎SHA为 `f4e68744ec0d0b1423d7da935a56c12c9826b3c5ce2dadadbddafa6a558176d4`。

首次原子补齐流程已在真实界面观察到：起初报找不到依赖；原子创建只定义 `Greeting` 的依赖后，自动改为准确的 `undefined: dep.Message`；只把外部函数改回 `Message` 后，自动清错。调用方三个文件摘要、依赖根inode、App39850和gopls40036/40038保持不变，实际进程环境没有GOWORK。没有Reload、修改调用方、重开工程或重启语言服务。

**证据保留有缺口。** Codex更新重启中断了截图导出，之前初始错误和Greeting阶段的PNG没有成功保存，临时REA会话也已清空；只能保留本对话中的实际观察与当时返回的证据ID、摘要，不能声称原图已经归档。更新后重新捕获的最终清错PNG已落盘，显示绿色检查和 `No problems in main.go`，其PID、窗口、原字节SHA及REA来源均已核对。仍需补录完整的三阶段原生证据。

第一次直接启动的实例已由界面正常Exit。为取得可校验启动时间，之后改用系统LaunchServices和明确的隔离环境启动。更新后CUA桌面操作工具不可用，已结束的atomic实例通过仅向自有App发送SIGTERM退出；已采样的App/gopls进程均消失，这次不是菜单Quit验收。

分步创建流程另由正常IDE命令行打开工程与 `main.go`，只在独立profile中预置信任这一个自有夹具目录。REA曾因原生Swift helper超时而采集失败；误解包的旧atomic图片已隔离，未纳入分步证据。之后取得了匹配PID49178／窗口16202的新基线和后续截图。全程根inode92576018与调用方摘要不变。

| 原生流程 | 新源码只定义Greeting时 | 外部源码改为Message后 | 本轮结论 |
| --- | --- | --- | --- |
| 一次补齐完整依赖目录 | 实际观察到undefined Message；这张原图未归档 | 自动清错，最终原图已保留 | 需补录完整证据 |
| 空目录→go.mod→deep/a/b/leaf.go | [导入路径仍报红](../../reference/cold-dependency-candidate11-20261114-evidence/native-slow/greeting.png)，CLI已报undefined Message | [绿色检查](../../reference/cold-dependency-candidate11-20261114-evidence/native-slow/message.png)，CLI运行与无缓存测试成功 | 首次发现深层源码的阶段失败 |
| 已有go.mod及anchor.go的根→fresh/a/b/leaf.go | [新深层包的导入路径仍报红](../../reference/cold-dependency-candidate11-20261114-evidence/native-steady/greeting.png)，CLI已报undefined Message | [绿色检查](../../reference/cold-dependency-candidate11-20261114-evidence/native-steady/message.png)，CLI运行与无缓存测试成功 | 新建深层包的阶段失败 |

后两条流程没有打开Problems面板，因此证据只确认错误标记所在位置及绿色检查，不能虚构完整原生诊断文本。steady全程App61186／gopls61272/61274、根inode91788866、调用方三摘要和依赖go.mod摘要保持。两条流程的三阶段原PNG、REA绑定、源文件、CLI原输出、阶段日志和退出记录均已保留；没有Reload、改调用方或重启服务。

slow右下的 `Dependencies changed ... Yes / No / Always / Never` 提示来自gopls的govulncheck漏洞扫描询问，未点击。[gopls提示源码](https://raw.githubusercontent.com/golang/tools/gopls/v0.24.0/gopls/internal/server/vulncheck_prompt.go)、[异步调用](https://raw.githubusercontent.com/golang/tools/gopls/v0.24.0/gopls/internal/server/text_synchronization.go)和[LSP4IJ处理](https://raw.githubusercontent.com/redhat-developer/lsp4ij/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/ServerMessageHandler.java)表明提示异步进行；不能把它解释成Reload许可或自动回答它来掩盖失败。当前修复方向仍是待验证推断：目录prime发现了新叶文件，但发给gopls的通知可能只有根目录和内容未变的go.mod。下一步应记录真实线级通知，并用精确leaf URI的Changed作正控；不能先把这个解释写成已证唯一根因。

本轮**尚未完成原生Run/Test**。CLI克隆工程的 `go run` 与 `go test -count=1` 有独立记录，不能替代IDE按钮的运行和测试。原演示GIF继续绑定已发布14.3，本轮没有用静态图伪造新的运行演示。

## GitHub构建与公开下载

- 首次不公开候选：[43610212164](https://github.com/shubolab/opengoland/actions/runs/43610212164)，源码 `7b98f16`、版本字符串14.4、`publish=false`。Windows原始180份XML为1029项、1失败、0错误或跳过，唯一失败就是上述目录身份采样。未生成Windows EXE。修正后该旧构建被取消，失败报告仍保留。
- 修正后不公开候选：[43612505270](https://github.com/shubolab/opengoland/actions/runs/43612505270)，源码 **`c15e102a77fd422cd58536041e851f33d2a08000`**，同样 `publish=false`。最终状态在截止前回读更新；触发构建不等于已经生成或公开安装包。
- 当前公开下载仍为 **[14.3](https://github.com/shubolab/opengoland/releases/tag/v0.1.0-preview.20261114.3)**，该版首次缺失依赖的已知问题仍适用。旧tag和五个公开资产保持原样，本轮没有新tag或Release。

## 磁盘和保留位置

本轮只清理了C11首次直接启动、已经正常退出的 `native/atomic/profile/system`。删除前保留原始文本/XML并逐项校验，两轮新鲜进程与FD检查均无引用；config/log、父目录、夹具、所有App、SDK及构建sandbox均保留。操作窗口实际空闲增加301,625,344字节，结束约28.67GiB；它是当时的空间差值。[退役记录](../../verification/generated-direct-system-retired-after-candidate11-20261114.json)指向本机紧凑证据，不能把旧计划用于后来同名重建路径。

原生收口后已采样的自有App、fsnotifier与gopls均退出；C9/C10各四项关键载荷及C11的manifest、launcher、engine、plugin JAR摘要保持不变，C11深度严格签名检查通过。四份IDE完整日志均无ERROR/SEVERE行。16:21UTC采样实际空闲29,925,421,056字节（约27.87GiB），仍高于20GiB保留线，详见[原生结束核对](../../verification/candidate11-post-native-20261114.json)。

C9／14.3已验证App和安装器继续保留。C10失败App暂时保留作对照，清理计划仍为HOLD。C11和当前分步夹具也保留；所有后续清理都要重新核对身份、引用与证据。没有修改GoLand／PyCharm参考应用、用户数据或其他仓库的进程。

## 下次续接

1. 先看新CI的准确结果。如果仍运行中，继续检查该run；如果失败，读取实际原始XML与日志。Windows身份采样分支在实测前不能记作修好。
2. 优先围绕本轮两条新建深层源码反例补实际RED：分步建立空根与go.mod、确认真实通知到达，再创建Greeting叶文件；检查生产VFS prime后的包中是否含精确leaf URI，失败后仅补同FIFO的leaf Changed作正控。保持调用方、根身份、go.mod摘要及语言服务PID，不回答漏洞扫描提示，也不改workspaceFolders。完成有界发现、FIFO、身份、失败重试与ack验证后再改生产代码。
3. 恢复桌面操作能力后，补录首次缺根的初始错误→Greeting错误→Message清错完整证据，重新验证分步和稳态流程，避免把重开后的恢复误记为自动恢复。
4. 做独立App中的无缓存Test、中文/emoji Run、工具链选择和退出检查。CLI克隆与headless回归只能证明各自范围。
5. 原生与安装器条件满足后，再创建新版本tag、核对真实EXE／DMG及校验值、发布并回读五个资产。当前候选不可直接标为已交付，14.3资产也不可替换。
6. 继续完整36×2验收矩阵，包括真实工程、重构、生成、调试、终端、Git、插件及Windows交互；本轮局部进展不代表整个IDE完成。

**收尾状态：进行中，最晚16:43:37 UTC更新snapshot、提交并推送，然后暂停。**
