# 2026-11-14 续接快照

完整目标仍为 **active**：继续建设可日常使用、尽可能还原 GoLand 2026.1.4 的独立原生 Go IDE。工作台使用 Kotlin / 开源 IntelliJ Platform，语言及工程服务使用 Go engine、gopls、Delve。IDE 账号、计费、订阅和试用门槛保持移除，开发工具认证保留。

**当前公开安装版为 [.20261114.3](https://github.com/shubolab/opengoland/releases/tag/v0.1.0-preview.20261114.3)。真实 EXE、DMG 与三份校验/证明已公开，双端实际安装检查和五文件独立核对通过。** 产品源码固定 `643480a`，正常 tag producer `43564600307`；两端各981项/175报告全绿。已存在依赖根删除重建通过，首次缺根后来补齐仍有已记录的原生失败，完整目标继续 active。此前14.2的 C5/控制层记录保留在历史阶段中。

## 本轮做成了什么

用户指出文件树、标签页和多个侧栏复用蓝底 `GO` 方块，偏离原版。已改为透明背景的蓝色 Gopher：源码、测试、`go.mod`、`go.work` 分别显示相应图形或角标；测试角标最终为红绿方向标记，侧栏按依赖、覆盖率、性能分析和检查等用途显示功能图标。图形是独立绘制的 Gopher 设计改编，署名随包提供。最终源码的深色和 Islands Light 原生窗口均已检查，不能再用中间版本的烧瓶角标截图代替最终效果。

同时修复项目外本地 `replace` 依赖的自动刷新。实测原问题不只在文件系统：gopls 根本没有注册该外部 `.go` 文件的监视范围。现在按打开的 package、所选 SDK/环境和构建标签发现实际依赖，使用私有 dirty overlay 与磁盘检查，安装有界监视范围，并在关闭或替换时释放。

最后修复零参数函数补全后的参数提示异常。LSP4IJ 的可选参数列表为空时沿用既有空列表呈现，只修改一行公开源码。4 项实际 handler 回归先复现旧 NPE，再验证修复；加上初始化/运行封存检查共 9/9 通过。最终补丁是 21 份来源、42 个类，旧 20 份来源和 41 个类逐字节不变。

## C1 本机候选 candidate4（本机通过，未发布）

固定源码 **`cac85013896b0508d88dab9f8c42d5bbc388fec0`** 已推送，版本/tag 为 `0.1.0-preview.20261114.1` / `v0.1.0-preview.20261114.1`。586 个构建输入逐项与该提交匹配，摘要为 `59dd547a00eecb7f06cfd53255561b80b08baa3fb69fa4d060d04a86f73f4c02`；engine 元数据同为此提交，`modified=false`。

完整本地门禁 **966 项 Kotlin 测试、173 份 XML，零失败、错误和跳过**；86 项 Python 检查通过。Go engine 本轮没有改源码；固定 Go 1.27.3 的无缓存 race（12 个包）与 vet 已通过。最初错误地显式设置 `GOWORK=off` 的失败也保留，未改变生产语义来获得通过。

实际使用全新隔离配置，只先打开调用方 `main.go`：

1. 从未在 IDE 打开的外部函数 `Message` 改为 `Greeting`，调用方字节不变，自动显示 undefined；只恢复外部函数后错误自动清除，没有手动 Reload 或重启 gopls。
2. 再次外部改名，Basic 将 `Gree` 自动补成唯一的 `Greeting`。未保留补全弹窗，不能声称已验收 popup 外观。Cmd+P 实际显示 `<no parameters>`，未再发生旧 NPE。
3. 保存后 Run 输出 `外部-v1|workspace-two|default|中文😀`、exit 0；Cmd+B 跳到项目外的 `Greeting` 定义。
4. 原生 Go Test 配置填写 `-count=1`，实际命令带该参数，显示 1 passed / PASS / package 0.689s / exit 0，未使用结果缓存。
5. 最终深浅主题图标均已核对。正常退出后四个已知 PID 消失；整个本次日志无 SEVERE/ERROR，签名结构检查 exit 0。586 输入、manifest、插件和启动器摘要不变，自有样例的 15 个源文件已恢复。

[详细原生记录与逐阶段证据](../../reference/cold-external-refresh-20261113.zh-CN.md)区分失败的 candidate1/2/3 与最终 candidate4。中间门禁 932/959/962 项各自绑定不同阶段，不能混成最终测试结果。

[新版真实运行 GIF](../../demo/native-go-workflow-20261114.gif)由 Run、外部定义跳转、无缓存测试三张实际窗口截图组成，共 12 秒；[录制记录](../../demo/native-go-workflow-20261114-recording.json)保留原始 SHA、REA 身份和版本。它不是连续录屏，也不新增调试验收结论。中英文 README 已将此图作为主要示例，历史调试片段保留原版本说明。

## Windows 失败后的窄修与发布继续点

[来源43517336656](https://github.com/shubolab/opengoland/actions/runs/43517336656) 的 C1 Mac attempt1 已完成：173 份 XML / 966 项全部通过、586 输入匹配，实际 DMG 为 1,023,853,737 字节，SHA `a0710f86a4ff1439f39332498a61ee7ee53ca9b9e0d5e59711857c8f88061acd`。单份 Mac ZIP已下载并核对 API SHA，不将这个单平台成功写为双平台交付。

Windows attempt1 的 hosted runner 于 23:18:32Z 失联，原日志不可取，原因未知。一次 failed-only 重跑后，attempt2 job `132233144372` 提供了真实失败日志：**966 项、2 失败、零错误和跳过，没有 EXE**。一个是 VFS/native 路径字符串斜杠比较导致误判，原始内容事件实际已发生；另一个是生产 metadata loader 正常收尾时从 `process.descendants()` 拿到当前测试 JVM，尝试终止它，被 JDK 拒绝。不能把后一问题断言为第一次 runner 失联的原因。

[失败记录与两份原始 XML](../../verification/windows-build-context-process-20261114/REPORT.zh-CN.md)保存确切绑定。Mac 原成功 job 被 attempt2 API 复用成别名，不是第二次执行；原 artifact `14083677862` 保持不变。冗余 tag run `43523430835` 已精确取消。原 C1 tag `v0.1.0-preview.20261114.1` 不移动，也不再重跑它来掩盖源码缺陷。

当前窄修只有三个源文件：

- 冷 VFS 测试保留真实内容事件断言，用 `Files.isSameFile` 比较实际文件身份。
- Windows metadata 命令复用 Go engine 既有的原子 Job 所有者，保留选定 SDK 的环境、目录和参数；删除后代快照清理。仅终止本次直接启动的 owner，由 Job 回收真实后代。清理异常保留原始失败。
- 新增真实进程控制：正常 launcher 退出后遗留子进程、首次循环检查前取消、独立 sentinel、私有环境/工作目录/UTF-8/非零退出。没有改 Go engine 源码。

五类针对性检查先通过38项，但 C3 `e6264e4` 的本机完整门禁随后为 **969 项 / 174 报告，1 失败**：新增 orphan 夹具在命令返回后读取可选进程身份信息，遇到 `isAlive=true`、command缺失。具体OS快照原因未知，不能据此定性生产回归。没有生成App；root主动取消同源云端 `43532637816`，两端为cancelled，不是云端测试失败，未创建14.2 tag。完整原XML和来源见[本机夹具失败记录](../../verification/windows-build-context-process-20261114/REPORT.zh-CN.md)。

夹具已收紧为：父进程发布PID记录后等release；第二次检查中先验证父子都存活、记录可执行文件和出生身份，再允许父正常退出；随后只断言已捕获句柄回收，独立sentinel继续运行。清理复用已知身份，未知活进程不冒险终止，不跳过用例或删除断言。修改后的五类针对性检查再次 **38/38通过、零跳过**。首轮新夹具的Unix PTY换行预期失败也单独保留，生产输出解码没有改变。

## C4 / candidate6（本机通过，Windows 安装后启动检查失败）

固定提交 **`07bb8eb35f48f11564bac5b076fa7752ff03466a`** 已推送。587个输入摘要
`9303eee51d0cf4d6efab26b4d5207d83b8e08dfca09a9fcfe690850c90b3bd3d`
逐项匹配Git；engine同提交、`modified=false`。完整打包成功：**969项 /174报告，零失败、错误、跳过**。

candidate6 使用全新隔离配置，先仅打开调用方。两次外部改名均自动被感知，恢复外部文件后自动清错；
Basic将Gree补成唯一Greeting，Cmd+P显示 `<no parameters>`，Run与外部定义跳转均成功。
原生Go Test配置实际带 `-count=1`，1 passed、package0.587s、exit0。最终深浅主题图标均实机检查。
正常退出后四个已知PID消失，整个本次日志无SEVERE/ERROR，签名结构检查exit0；587输入及产品摘要不变，
15个自有源文件全部恢复。记录范围仍限于这些具体操作和已采样身份。

[新的14.2真实GIF](../../demo/native-go-workflow-20261114-2.gif)及
[录制记录](../../demo/native-go-workflow-20261114-2-recording.json)使用本次三张新原图；
旧14.1图不改标。中英文README主图已换为14.2，下载区仍指向公开13.3。

本轮 [producer43536268282](https://github.com/shubolab/opengoland/actions/runs/43536268282)
于00:45:03Z以C4、14.2、`publish=false`启动，attempt1：Windows job `132265950724`，
Mac job `132265950662`。两端已结束：**Mac success，Windows failure，整个 run 为 failure，publish skipped**。
Windows 已通过 Gradle 门禁、创建 EXE 并完成安装。首次真实 `SEVERE` 出现在安装副本的 **`-version` 进程**，
后续 GUI 烟测检查共享启动日志时立即拒绝；不能写成已经证明 GUI 进程本身触发该错误。
因此这轮 Windows 安装验收未通过，生成 EXE 与本机 C6 成功都不构成双平台交付。

当前观察到的错误链经过 `GoTerminalCompletionDefaults.applyOnce:38` 与
`ApplicationInitializedListener:48`，触发 `TerminalEngine` enum 的 `<clinit>`，并沿
`TerminalBundle`、`LocalizationStateService` 访问 `FeatureUsageSettingsEvents`。
这里记录的是安装后版本查询/启动阶段的真实错误，不推断 GUI 一定崩溃。
[原始启动日志与局部 ZIP 取证](../../verification/windows-build-context-process-20261114/c4-installed-startup/README.md)已落盘，保留精确调用链、CRC/SHA 和整包未验证的边界。

当前窄修在迁移条件确认后、首次访问 `TerminalOptionsProvider.instance` 前，
先调用公开 `DynamicBundle.getLocale()` 做只读语言状态初始化；现有3类终端测试 **16项通过，零失败、错误和跳过**。
该窄修已保存为下面的C5源码；C4的失败保持原记录，不能由后续Mac通过推断Windows已消除该SEVERE。

C4产物不能推广；既有 `*-release14-2-final-*` 工具仍固定该失败的C4/run，保持归档未应用。
C5另有新producer；仍需两端实际成功、安装器和原始证据核对、私有/公开审计后才交付。
C3取消的小型证据、C1失败产物和本机 C6 画面分别保留，不复用成新的 CI 安装验收。
旧14.1/C3工具继续归档，历史 GIF 的版本、日期与源码不改标。

## 当前C5源码 / candidate7

固定源码 **`07ae69f3b2bbc36f3221ff08e8fe9046f199b624`**，587输入摘要
`6a6111a516d49a65aee5292ac75d523bc96cc1b1b06500596f1a7a257b8d8655`。
C7完整组装、签名和969项/174报告均通过，零失败/错误/跳过；所有587源输入逐项匹配Git，engine同源且clean。
manifest `5dfd710b…48cfce`、JAR `8ec58a99…98942e`、启动器 `b928f4e8…d9fd07`。

实际fresh-profile冷 `-version` 退出0、无ERROR/SEVERE（有WARN，stdout为平台2026.1.4），
GUI Run输出中文emoji并exit0。当前macOS真实终端 `go tes` 补成 `go test`，`go test -r` 的
`-run`/`-race`候选中接受`-run`；Go1.26.10，终端无缓存测试成功0.450s。
原生Go Test另设 `-count=1`，实际1passed/package0.344s/907ms/exit0；初次cached结果不计入该证明。
正常Exit后AX读取超时，inventory随后明确not-running；5个已采样自有PID消失，15源字节未变，
产品/源摘要未变、codesign exit0、整个本次日志零ERROR/SEVERE。

[新的startup-fixed GIF](../../demo/native-go-workflow-20261114-2-startup-fixed.gif)与
[录制记录](../../demo/native-go-workflow-20261114-2-startup-fixed-recording.json)使用C7三张新实际截图；
[原生记录](../../reference/cold-external-refresh-20261113-evidence/candidate7/native-summary.json)绑定来源与边界。
主README先换为新图，公开发布后下载区也已同步14.2。C6的外部刷新/主题与旧GIF仍是C4证据，不改标为C7；C7本机记录没有新增Windows交互或完整矩阵验收。

新producer [43543358207](https://github.com/shubolab/opengoland/actions/runs/43543358207) attempt1，
version job132288775707、Windows132288803301、Mac132288803355，已核对14.2/publish=false/workflow_dispatch。
两端现均为 **success**：Mac于02:36:58Z完成，Windows于02:48:16Z完成；publish按 `false` 跳过。
两份实际ZIP已各下载一份、核对完整API SHA，原始XML两端均969项/174报告全0，587输入逐blob匹配C5。
Mac actual DMG 1,022,010,762B / SHA `dad7cfb9e1c0b5bdd6d2fd1819de4f3e4eecb21aec0a59fc069462952e9d560e`；
Windows actual EXE 871,230,954B / SHA `02dc6790866a6b541b5a71907eb570b488a219167d6742ea918ad8516be93515`。
Windows实际安装/原生项目窗/卸载均退出0；原故障点的冷 `-version` 与共享日志均零ERROR/SEVERE。
严格C5 assembler已生成恰好五个资产，位置为 `.tools/continuation-20261113/workspace-consistency/runtime/release14-2-c5-assembled/assets`。

annotated tag `v0.1.0-preview.20261114.2` 已新建并推送，固定C5；自动触发的冗余tag run `43548847135` 已精确取消并确认cancelled，原成功producer不变。
发布过程曾创建 private release `444970702`，当时仅三份小校验/证据文件已上传；这是早期传输阶段记录，最终公开结果见下面。
本机直传实测约0.43MB/s，已有代理通道的4MB探测超时；仅本次命令试用，没有改系统网络设置。
root已精确中断自己的慢传 `gh` PID37796，原始安装器和tag未变，没有遗留本机上传。
随后修复现有 `publish-tested-installers.yml` 所用control-plane Python，使GitHub服务器复用上述已验收产物上传，避免重跑编译。
不修改workflow、不改目标C5二进制来源；新controller必须保留原始校验、区分CI安装证据和C7本地原生证据，并在真实五资产的独立私有审计通过后才公开。
控制层实现及独立审查已通过，完整Python检查为 **102项全绿**（其中发布器14项、独立审计9项）；实际C5只读组装也确认五资产字节精确一致。控制层提交 `067c633dc8b97f631c2063b955bb503c3062f64c` 的实际推广 [43552785200](https://github.com/shubolab/opengoland/actions/runs/43552785200) 已 success，03:41:02Z公开五资产。新CI模式只允许上述已复核C5 cohort；未知新版本必须先复核并增加精确来源约束，旧手工完整草稿的重试路径保留。

C5文档updater `.tools/continuation-20261113/update-release14-2-c5-docs.py` 已具备真实检查/写入实现，默认只检查；
已用实际公开状态、C5来源、双端证据和私有/公开双审计执行 `--check` / `--apply`；中英文README下载区已同步14.2，不启用C4旧推广工具。
云端真实私有审计回执仅29KB，完整API SHA已核对；本机同一新auditor重新读取五实际文件并与公开API比对，两审计的平台对象和资产字节完全一致。匿名公开下载的426B校验文件也与API SHA匹配。[正式发布证明](../../verification/releases/0.1.0-preview.20261114.2.json)与[控制层接力证明](../../verification/releases/0.1.0-preview.20261114.2-publication.json)区分两种源码来源。历史生成物已按下述精确范围退役。

## 完整目标还剩什么

完整矩阵仍有 **36 条 reference_runtime、36 条 reconstruction 为 unverified**；本轮只补充 PROJECT-02、LANG-02、LANG-04 的局部证据。继续验证真实工程、环境/构建标签、重构生成、终端/Git/插件/中文界面和双平台交互。Windows 交互与本机 macOS Developer Mode 调试闭环尚未完成。整个外部根删除重建、深层新增目录和资源上限之外的工程也不能由本次已有文件改名验证代替。

安装启动检查、本机特定操作通过和完整产品对齐是不同证据。不要因本次图标与刷新修复发布而将 goal 标记完成。

## 本机资源

本段清理结束时，仅保留 C7/C5 本机验收 App（manifest `5dfd710b…48cfce`）。开始后续候选前，已在无进程/FD引用时将它原子保留到 `build/product-verified-c5-20261114.2/open Goland.app`；canonical 路径随候选重建，不能再把当前 canonical 字节当成 C7。真实EXE、DMG、两份原始证明及校验值保留在 `.tools/continuation-20261113/workspace-consistency/runtime/release14-2-c5-assembled/assets/`；审计目录的大文件为同inode硬链接，没有另复制一份安装器。

本轮精确移除三个旧App、C1与C5下载ZIP、两份已完成的解压证明目录、C6/C7已结束system及test system-test，共11个明确目标及三个空父目录。旧路径现在已经不存在；不要继续把历史来源记录理解成文件仍在。[退役记录](../../verification/generated-artifacts-retired-after-14.2-20261114.json)保存原始来源/摘要、两次进程及FD检查、真实公开tag/资产验证和执行回执。

删除前已把80,011,056字节的原始XML、源/配置/日志文字及旧App完整manifest等紧凑事实归档并逐entry核对，含1,145份XML，归档SHA `deefd5e031493d09767fbb5449908673f39cdeaeced7e59063108d7e93a87a68`。profile/config、profile/log、原始JUnit tar、共享SDK/缓存、源码、worktrees、参考应用及GitHub公版资产均保留。删除后再次确认最新App与实际五资产全部未变。

本轮操作窗口的实际可用空间增加约 **5.64 GiB**，结束时约 **52.6 GiB可用**，高于20GiB保留线；APFS共享及同期写入使du约11.82GiB不能当成实际释放量。此前3.83、1.64和0.54GiB各轮记录独立，不重复相加。大构建前仍应重测空间并先清已结束的自有输出。

前序完整交付与 HTTP/Git 记录见 [11 月 13 日快照](../2026-11-13/README.zh-CN.md)；原始工作记录在 `.tools/continuation-20261113/`，profile、完整日志和 AX 仅留在 ignored 工作区，不提交。

## 14.2 发布后的继续推进：外部依赖根恢复

已在相同 C5 本机 App 复现完整外部根移走、原路径新 inode 重建后的编辑器/真实编译不一致，三张 REA 原图、Go 控制输出、记录过程缺口和退出恢复均见[独立记录](../../reference/external-root-recovery-20261114.zh-CN.md)。原始已有文件改名成功不能替代整根生命周期。

工作树已修复固定路径重新绑定、安全有界物化、目录通知重试及旧连接/旧所有者隔离；最终37项定向检查通过。两轮中间失败和第三轮36绿均封存，未用最终绿结果覆盖旧失败。新完整构建、真实 App 重放以及后续 EXE/DMG 尚待完成；当前公开安装包仍为14.2，不能把工作树修复当成已发布。首次缺根后创建、稳态深层新目录、双平台交互及完整矩阵仍开放。

## 后续候选 C8：完整门禁通过，原生恢复仍失败

源码 `89dc532` 已提交推送；本机独立 C8（版本字符串 `.20261114.3`，没有 tag/CI/公开发布）实际通过977项/175报告，590输入摘要 `7c2ec4c0…0816e8` 与Git逐个blob匹配，engine同源码/modified=false、签名验证通过。Go引擎的123源文件与C5相同，本轮无缓存race另有349测试通过、12包通过、cmd包无测试；这不把任何Go测试跳过当作通过。

真正App复验却发现：旧根消失能自动报BrokenImport，新inode根恢复后仍停旧错误，直到额外修改新根源文件才恢复；全部调用方字节未动、gopls PID未变、没有Reload或重启。原始运行与带官方rpc.trace的同C8重放分别保留，后者实际输入目录URI在存在时多尾slash、缺失时无slash。独立真实gopls只改变通知地址尾slash、不改磁盘/版本即可复现错误与恢复；先前统一无slash的协议控制不能证明这条链。

下一修复工作树已改用既有词法稳定 `GoDocumentSync.fileUri`，并修复单独红测证实的目录通知绕过VFS dispatcher FIFO；旧连接/owner在实际dispatch时再次校验。最终41定向全部绿，原排序和URI红测均封存，见[受控结果](../../reference/external-root-recovery-20261114-repair-controls/summary.json)。仍需固定新源码完整打包、新原生复验、真实双平台安装器与新公开版本。C8尚未交付，不用其绿门禁覆盖原生失败，完整目标继续active。


## C9：固定源码后的真实根恢复闭环

固定源码 `643480a3d9d01c526210bdeba16df8431c1f4d75` 的 C9（版本 `.20261114.3`）已经完整打包，本机 **981 项／175 报告通过，0 失败、错误或跳过**；590 个封存输入逐个匹配 Git，摘要 `895ae89d…08644`。engine 绑定该源码、modified=false，签名与关键载荷核验通过。41 项定向控制及旧 C8 原生失败证据继续保留。

使用 C8 自有配置种子、全新 system/plugins/log 和默认内置 gopls 的实际 macOS App 已验证：原外部根移走后自动报缺失；原路径以不同 inode 新建、仅定义 `Greeting` 后自动改报 `undefined Message`；只恢复外部 `Message` 后自动清错。全程不改调用方、不重启服务、不手动 Reload；接着通过原生 `-count=1` 测试和含中文/emoji 的 Run。最终15个源文件及原目录 inode 均恢复，正常退出后四个已记录自有进程均不存在，日志0 ERROR/SEVERE，App载荷不变。见[原始截图与摘要](../../reference/external-root-recovery-20261114-candidate9/native-summary.json)。

新的[真实 GIF](../../demo/native-go-workflow-20261114-3.gif)按“恢复后编辑器 → 无缓存测试 → 中文 Run”三个阶段组成，12秒、1200×857；[录制记录](../../demo/native-go-workflow-20261114-3-recording.json)明确本机 C9 来源，不能当作云端安装器字节相同或连续录屏。

本段记录时已推送 annotated tag `v0.1.0-preview.20261114.3`，精确指向 `643480a`；正常 tag 工作流 [43564600307](https://github.com/shubolab/opengoland/actions/runs/43564600307) 的 Windows 与 macOS 实际安装器仍在构建，尚未核验公开五资产。本机通过不代表云端已经交付。C9当前App与最新已验证14.2实际安装器继续保留；旧候选清理必须先归档原文本/XML并复核进程、FD与身份。首次缺根、稳态深层新目录、Windows交互、调试权限和36×2完整矩阵仍开放，goal继续active。

## C9 之后的阶段 A 资源退役

已完成精确的阶段 A 清理：失败 C8 App、C8/trace 共用 `profile/system`、已结束的 `system-test` 及一个实际空 App 父目录现在均不存在。两次新鲜全机进程/FD 检查均为零引用；C9 和最新已发布 C5 App、C8 原 config/log、C9 profile 保持不变。操作窗口的实际可用空间增加 **1,326,170,112 字节（约 1.235 GiB）**，结束为 **49,352,327,168 字节（约 45.96 GiB）**，不把 du 或八个硬链接名称重复计作释放量。

[阶段 A 执行选取记录](../../verification/generated-artifacts-retired-during-14.3-20261114.json)保留原始报告/执行后封存脚本/计划 SHA 和证据去向。删除前归档 288 个原字节文本/XML 条目共 636,567 字节（含 27 XML），逐 entry 回读 SHA 一致；C8 完整原 manifest、engine/构建信息、原始 JUnit tar、原配置/完整日志、失败截图、trace 和收尾记录仍在目标之外。归档目录为 `.tools/continuation-20261114/root-recreation/retirement14-3-preservation/`，它是紧凑证据归档，**不是完整 C8 App 备份**。

阶段 B 的 C5/14.2 App 和八个旧 14.2 安装器硬链接名称仍为 HOLD，尚未删除；需实际公开 14.3 五资产、双安装器与独立审计通过后再检查。用户数据、源码、共享 SDK、参考应用和 GitHub 公版资产零触及，完整目标和前文未验范围继续开放。

## C9 后续独立探针：首次缺根仍未恢复

同一固定 C9 源码 `643480a` 的新隔离工程，首次打开时 `replace ../dep` 目录不存在。随后原子创建定义 Greeting 的目录，CLI 已改报 undefined Message，但原生等待约两分钟仍停在旧 BrokenImport；只改外部源码回 Message 后，CLI exit 0 并输出中文/emoji，原生仍未清错。调用方三个文件不变，无 Reload、重开或服务重启，gopls `7005`/`7006` 身份不变。此结果单独见[首次缺根失败记录](../../reference/initial-absent-dependency-20261114.zh-CN.md)，不覆盖前文已存在根删除重建的成功，也不把源码推断当成已证根因。

本轮未做原生 Run/Test 或重开对照，没有已验证的 workaround。正常退出后四个已采样自有 PID 消失，调用方原 SHA 未变，外部路径恢复原缺失状态，新建依赖目录的最终 Message 原字节另行保留；App 关键载荷与签名通过，完整日志零 ERROR/SEVERE。三张原 PNG、规范化选取、来源 SHA 和夹具已封存；Greeting 阶段原文件后来被覆盖，不声称仍存在。矩阵只加局部失败证据，36×2 状态仍全部 unverified，完整 goal 继续推进。


## 14.3 正式交付与下次续接入口

真实公开 Release ID `445091099`，于 **2026-11-14 06:59:12Z** 公开，源码 `643480a`、tag `v0.1.0-preview.20261114.3` 不移动；正常 producer [43564600307](https://github.com/shubolab/opengoland/actions/runs/43564600307) attempt1 全部 success。Windows job `132355573686`、Mac job `132355573716`、publish job `132368559622`。两个平台各981项/175原始XML报告无失败、错误或跳过，fresh Go race 分别347/349项通过，无测试跳过；两个实际安装器各自的安装/启动/工具检查通过，范围见[正式记录](../../verification/releases/0.1.0-preview.20261114.3.json)。

五个公开原文件已实际下载，源码590输入摘要 `895ae89d…08644`、Git原blob、两端原始测试身份和API大小/SHA经过独立核对；[原审计字节](../../verification/releases/0.1.0-preview.20261114.3-independent-audit.json)与新的中英文 README/真实 C9 GIF 已同步。本版使用正常 tag 工作流，不复用 C5 推广器或称作新的独立私有草稿审计。最新实际 EXE/DMG 与证明保存在 ignored `root-recreation/release14-3-audit/`；本机 C9 App 仍与 CI 载荷分别绑定，不能宣称逐字节相同。

下次优先修复[首次缺根失败](../../reference/initial-absent-dependency-20261114.zh-CN.md)。受控 Go 元数据与安全设计位于 `.tools/continuation-20261114/root-initial-absent-c9/cli-metadata-control-001/`：失败包没有 Module，精确 selected-module 查询可返回 local Replace.Dir；这只是 CLI/源码控制，不是已修复的生产实现或新原生通过。先补实际红测，再做有界 pending/stat 与独立 recovery contribution，并保持已有完整贡献、FIFO/稳定 URI、SDK/owner生命周期守卫。随后验证稳态深层新增、真实工程/重构/生成/调试/终端/Git/插件与双平台交互。完整36×2状态仍 unverified，goal保持active。

## 14.3 公开核验后的阶段 B 退役

在实际公开 14.3 的五个原文件、双平台安装器证明及独立公开审计通过后，已精确删除旧 C5/14.2 App、它的实际空父目录，以及旧 14.2 EXE/DMG 的八个本机硬链接名称；这些旧路径现在已不存在。[阶段 B 选取记录](../../verification/generated-artifacts-retired-after-14.3-20261114.json)保存原执行报告、固定计划、执行脚本及本阶段授权 SHA，明确使用正常 tag 工作流的公开核验，不声称有新的独立私有草稿审计。阶段 A 当时的 HOLD 原文继续作为历史保留，由本段记录后续条件满足与实际执行。

删除前两次新鲜进程/FD 检查均为零引用；结束后当前 C9 App 与新 14.3 五个实际文件的摘要保持不变。操作窗口实际可用空间增加 **2,697,654,272 字节（约 2.512 GiB）**，结束时 **43,936,346,112 字节（约 40.92 GiB）**，高于 20 GiB 保留线；不按八个硬链接名称重复相加，也不与旧 du 估算混算。

C5 完整原始 manifest 仍在目标外的 `candidate7-product-manifest.json`，旧安装器的证明 tar、校验值、原配置/日志和本机失败记录继续保留；阶段 A 的原字节文本/XML 归档亦未删除。这些是可追溯的紧凑证据，不是旧 App 的完整二进制备份。最新实际 EXE/DMG、证明及校验文件保留在 `.tools/continuation-20261114/root-recreation/release14-3-audit/`。退役后另取的旧 14.2 公开 API 也确认五个远端资产的 ID、大小、SHA、状态与发布时间均和原记录一致；本轮没有修改远端 Release。

源码、worktrees、用户数据、共享 SDK、需要的工具缓存和参考应用均未触及。首次缺根已知失败及完整 36×2 矩阵继续开放，完整 goal 保持 active；清理完成不代表产品验收完成。
