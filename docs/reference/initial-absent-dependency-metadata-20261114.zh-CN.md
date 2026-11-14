# 首次缺失本地依赖：Go 元数据控制与待实现方案

**已找到一条可靠的发现路径：依赖目录还不存在时，Go 的包查询没有给出模块位置，但查询已选中的模块仍能给出本地 `replace` 的目标目录。** 这能指导修复首次缺根恢复；产品修复和相关 Kotlin 回归还没有实施，也没有新的原生通过结论。

本文承接 [C9 首次缺根的原生失败记录](initial-absent-dependency-20261114.zh-CN.md)。那个实验中，工程首次打开时 `../dep` 不存在；后来放入依赖，甚至恢复可运行的 `Message()` 后，编辑器仍停在旧导入错误。这里另外创建了很小的 **CLI 专用夹具**，查询真实 Go 元数据，没有操作原 App、profile 或原生夹具。

## 控制结果说明了什么

产品源码固定为 `643480a3d9d01c526210bdeba16df8431c1f4d75`，版本为 `0.1.0-preview.20261114.3`。CLI 使用这份 C9 App 中的真实 Go `1.26.10 darwin/arm64`，GOROOT 与该二进制对应。调用方三个文件按 SHA 复制自[公开原生夹具](initial-absent-dependency-20261114-evidence/fixture/README.zh-CN.md)；原件和基础控制副本前后 SHA 一致。没有把依赖加入 workspace module。

| 独立 CLI 状态 | 包查询的实际结果 | 精确模块查询的实际结果 |
| --- | --- | --- |
| 本地替换目录不存在 | 失败 dep 有 `ImportPath`、`Error`、`Incomplete`，没有 `Module`、`Dir` | 返回选中版本 `v0.0.0`、本地 `Replace.Dir` 和 `GoMod` 绝对路径，没有 Error |
| 只有目录，没有 `go.mod` | 失败 dep 仍没有 `Module`、`Dir` | 仍返回上述替换路径 |
| 有 `go.mod`，还没有 Go 源文件 | 失败 dep 仍没有 `Module`、`Dir` | 仍返回上述替换路径 |
| 完整放入只提供 `Greeting()` 的依赖 | 包元数据出现 `Module.Replace` | 继续返回相同模块与替换根 |

这里的包查询包括 `go list -e -deps -json .`，以及当前 loader 使用的 `go list -deps -e -test -json=ImportPath,Dir,Module,Error,DepsErrors,Incomplete .`。精确模块查询是：

```sh
go list -m -e -json example.invalid/accept/dep
```

**模块名后不能加 `@version` 或 `@latest`。** 目标是查询当前构建上下文已选择的模块与 replacement，不能改成另一次版本查询。`go list -m -e -json all` 在这个小夹具里也返回了目标，但不能据此假定任意不完整模块图都能完整列出 all。

这些命令均 exit 0，stderr 为空；`-e` 把失败放进 JSON，exit 0 不代表查询完整。`go list` 也没有替本轮检查 `dep.Message()` 是否存在，所以 Greeting 阶段的元数据成功不等于调用方能编译。原生失败记录中的 CLI 编译/运行控制与这里的元数据查询是不同证据。

两个阴性控制也很重要：

- **被 require、却没有实际 import 的模块**，单独查询时同样返回本地替换根。因此不能把所有模块或全部 `replace` 声明都变成 watcher。
- **仅声明 replace、没有被 require 的模块**，以及未知模块路径前缀，在这次批量 `-m -e` 查询中被省略，实际返回对象数少于参数数。不能假定一参数对应一个 JSON 对象，也不能把省略项当作成功或把本批结果当作已穷尽所有可能 provider。

[规范化选取 JSON](initial-absent-dependency-metadata-20261114-evidence/selected.normalized.json)保留项目包、模块与错误字段，排除整份标准库元数据。它是**选取并替换路径的衍生文件**，不是原始 stdout。`$BASE_APP`、`$BASE_DEP` 等是词法路径别名，定义在 JSON 中，不是实际环境变量。

17 条命令的原始 stdout/stderr 合计 **356424 字节**：基础 missing/present 控制 281137 字节，未使用模块阴性控制 25270 字节，部分创建目录控制 50017 字节。原字节 gzip 留在原机器的 ignored 目录，不提交；[原始输出摘要](initial-absent-dependency-metadata-20261114-evidence/raw-capture-hashes.json)记录每条命令、原始及 gzip 大小/SHA。[清单](initial-absent-dependency-metadata-20261114-evidence/manifest.json)区分原始哈希引用、规范化文件和源码锚点。换机器复现时绝对路径不同，不能要求原始输出 SHA 相同，应比较语义字段与路径别名。

## 当前实现为什么接不上

以下位置按源码 `643480a` 阅读，不代表已经修复：

| 位置 | 现在的行为 | 首次缺根时的结果 |
| --- | --- | --- |
| [GoRunBuildContextLoader，473–546 行](https://github.com/shubolab/opengoland/blob/643480a3d9d01c526210bdeba16df8431c1f4d75/src/main/kotlin/dev/openidea/goide/run/GoRunBuildContextLoader.kt#L473-L546) | 从 package 的 `Module.Replace` 收集根，还要求 package Dir 位于根内且目录存在 | failed package 没有 Module，无法产生根 |
| [同文件，371–418 行](https://github.com/shubolab/opengoland/blob/643480a3d9d01c526210bdeba16df8431c1f4d75/src/main/kotlin/dev/openidea/goide/run/GoRunBuildContextLoader.kt#L371-L418) | 已有选定 SDK/env、私有 manifests、overlay 和有界元数据查询 | 可复用执行通道，不能另起一个会写用户 manifests 的模块查询 |
| [GoDependencyScopeCoordinator，78–90 行](https://github.com/shubolab/opengoland/blob/643480a3d9d01c526210bdeba16df8431c1f4d75/src/main/kotlin/dev/openidea/goide/lsp/GoDependencyScopeCoordinator.kt#L78-L90) | 不完整结果保留旧 verified contribution | 保护旧根是对的，但首次没有旧贡献，desired 仍为空 |
| [GoExternalFileWatches，100–131、238–239 行](https://github.com/shubolab/opengoland/blob/643480a3d9d01c526210bdeba16df8431c1f4d75/src/main/kotlin/dev/openidea/goide/lsp/GoExternalFileWatches.kt#L100-L131) | 首次不存在的目录装不上 NativeRoot；实际 roots 为空时不保留定时任务 | 已存在根的重建恢复机制不能替代首次缺根发现 |
| [GoExternalDependencyScopes，89–156 行](https://github.com/shubolab/opengoland/blob/643480a3d9d01c526210bdeba16df8431c1f4d75/src/main/kotlin/dev/openidea/goide/lsp/GoExternalDependencyScopes.kt#L89-L156) | 有 400ms 合并延迟、当前 settings/editor generation 和关闭生命周期 | 适合承接独立的等待目标出现的意向 |

这些源码断点和 CLI 控制支持下面的设计，但本轮没有保存原失败现场的 LSP 消息，不能把源码推理写成那次原生失败的完整协议根因证明。

## 建议的最小安全闭环

把“Go 指出了一个值得等待的目标”和“已经装上真实文件监视”分成两个状态；保留当前 verified contribution 的规则。

1. **先从实际失败 import 缩小范围。** 对当前 deps JSON 中失败、缺 Module 的真实 `ImportPath=P`，枚举合法模块路径段前缀，排除 synthetic test 名称，限制候选数和长度。再批量执行精确、无版本后缀的 `list -m -e -json M1 M2 …`。前缀只用于查询，不能直接当根；也无需自己重现 go.work/go.mod 的 replacement 优先级。
2. **只采 Go 真正返回的选择。** 要求返回 Path 精确命中候选、非 Main、有选中 Version、Replace.Version 为空、Replace.Dir 为安全绝对路径，且没有使选择不确定的错误。保存 `(失败 import、模块、选中版本、目标目录、上下文代次)`。多个前缀可能是失败 import 的候选 provider，不能自己把最长前缀当作唯一答案。查询报错或省略不能变成“成功空图”。
3. **增加独立 recovery candidates。** 它们不等于 verified loaded roots，也不改变 `complete=false`。候选已经存在时，要纳入原有有界 module metadata closure，消费其 dirty go.mod；不存在时只记录明确的文件缺失、路径别名与根存在/身份 guard，不能对不存在目录调用 `guards.directory`。SDK/env、workspace、编辑器输入变化后重新证明。
4. **缺失时仅检查明确路径。** 后台每 2 秒至多检查 128 个去重根的 `readAttributes(..., NOFOLLOW_LINKS)`，必要时再检查各自明确的 `root/go.mod`。总根预算与现有监视统一，不扫描 children，不监听父目录/home/文件系统根。即使实际 lease 为零，只要仍有 pending 候选，定时器就应存在；一直缺失且状态不变时，只 stat，不反复运行 Go 或发 LSP 通知。
5. **出现后重新查询，再安装真实监视。** 新增根存在/身份 guard，让旧 missing snapshot 在状态变化后失效。fresh 查询再次证明目标且目录实际存在后，可以用独立 recovery contribution 安装真实、有界 lease，即使 package graph 仍不完整。这样目录、go.mod、深层源码分批到达时，后续真实 VFS 事件仍能推进。不能放宽 `requireCoverage`，把不存在路径或装载失败冒充成功；也不能用这份 partial contribution 替换旧 verified 根。
6. **安装成功后复用已验证的通知链。** 实际 matcher 与 bounded lease 成功后，沿 [GoLanguageClient 现有 reconciliation](https://github.com/shubolab/opengoland/blob/643480a3d9d01c526210bdeba16df8431c1f4d75/src/main/kotlin/dev/openidea/goide/lsp/GoLanguageClient.kt#L85-L108) 发送一次目录 Changed：保持同一 FIFO、`GoDocumentSync.fileUri` 稳定 URI、expected owner/server 检查。成功接管后才移交 pending 意向；安装/通知失败保持可重试状态和退避，不能丢失变化。继续遵守 128 根、20k 节点、64 层及 symlink/physical safety 限制。

所有意向、查询、lease 和通知都必须有 owner 与代次：关闭一个包只移除它的贡献，共享根仍由其他包持有；切 SDK/env/workspace、stop 或 dispose 后，旧 stat/query/registration/FIFO 回调不能替新 server 装监视或发通知。Go 进程、stat、VFS 物化都在后台，不能在 EDT 做 IO 或持生命周期锁等 future。

仅检查 root/go.mod，等到 package graph complete 后才装监视，是一个更窄的方案，但会漏“目录和 go.mod 到了，深层源码稍后才到”的慢 checkout。本次真实 partial-root 控制正是要求独立 recovery lease 的理由。它只证明目标值得装恢复监视，不证明图已完整、编译已通过或 provider 已唯一确定。

## 下一步应先写并封存的六组 RED

1. **真实 Loader 元数据：** imported required missing replace 产生一个相关 recovery candidate；required-but-unused、unrequired、未知前缀不扩大监视。覆盖 batch 省略、多前缀、错误、dirty go.work、vendor 与私有 manifests 防写。保持 complete=false 和既有 verified roots 合同。
2. **零实际 lease 的等待：** 连续 missing tick 只检查最多128明确根，没有重复 Go 查询、通知或假 coverage；共享路径去重，超限明确拒绝，最后 owner 关闭停 timer。
3. **真实 VFS 初次创建：** 从未存在/未打开的外部根、明确 clean 的父缓存，原子创建完整 Greeting 树；仅生产 pending probe → fresh query → 真实 matcher/lease → FIFO 稳定 URI。调用方字节不变，等待完整 refresh/notification future，禁止测试人为刷新父目录或全局。
4. **慢 checkout：** 目录、go.mod、深层 Go 源分批创建；graph partial 时已安装真实 recovery lease，随后源和 metadata 创建事件可推进，不能把等待意向提前丢掉。
5. **关闭和迟到结果：** 分别 hold stat/query/install/FIFO，再切 SDK/env/workspace、关闭包或 stop/dispose，并启动同路径新 server；旧代不得安装或通知，新 owner 的根不被旧释放。
6. **保留与安全失败：** 旧 verified A 加 partial pending B 时保留 A；B 成功不把 A 当空图清掉，当前完整图确认移除才收回。unsafe/symlink/超预算/过深只失败并退避，不扫描父目录、无限 prime 或虚报 recovered。

这六组测试还没有编写或执行；通过后仍须复跑最初的原生缺根场景和 Windows 对应场景，不能把 headless VFS 或 CLI 结果当作安装产品验收。

## 在新目录复现元数据控制

以下是 macOS/POSIX shell 的输入与命令示意，不启动 IDE。`GOROOT` 由操作者指向真实 Go1.26.10 安装；不要复制或改动 SDK、原生 profile、原生夹具。先在仓库根目录新建自己的临时控制目录，并复制公开夹具的三个 caller 文件：

```sh
export GOROOT=/path/to/go1.26.10
FIXTURE="$PWD/docs/reference/initial-absent-dependency-20261114-evidence/fixture"
CONTROL="$(mktemp -d)"
mkdir -p "$CONTROL/basic/app"
cp "$FIXTURE/app/go.mod" "$FIXTURE/app/main.go" "$FIXTURE/app/main_test.go" "$CONTROL/basic/app/"
go_probe() {
  env -u GOWORK GOROOT="$GOROOT" GOENV=off GOTOOLCHAIN=local GOFLAGS= \
    GOPROXY=off GOSUMDB=off GOVCS='*:off' GOTELEMETRY=off CGO_ENABLED=0 \
    "$GOROOT/bin/go" "$@"
}
```

在 `basic/app` 下先保持 `../dep` 不存在，记录 stdout、stderr 和 exit code，执行 `go_probe version` 与下面五条命令。然后在自己的 `basic/dep` 放入公开 `retained-created-dep` 中的 go.mod/dep.go，并仅在副本中把 `func Message()` 改为 `func Greeting()`，再次执行同样五条：

```sh
go_probe list -e -json .
go_probe list -e -deps -json .
go_probe list -m -e -json all
go_probe list -m -e -json example.invalid/accept/dep
go_probe list -deps -e -test -json=ImportPath,Dir,Module,Error,DepsErrors,Incomplete .
```

其余六条控制各用另一份新建的 caller 副本，不改变 basic 控制或公开 fixture：

- 阴性副本不创建任何依赖目录；在 go.mod 末尾增加 `require example.invalid/accept/unused v0.0.0`、`replace example.invalid/accept/unused => ../unused`、`replace example.invalid/accept/notrequired => ../notrequired`。执行上述 loader-fields 命令，再执行 `go_probe list -m -e -json example.invalid example.invalid/accept example.invalid/accept/dep example.invalid/accept/unused example.invalid/accept/notrequired`。返回 dep 与 unused，其他参数在本控制中省略；实际待监视候选仍必须先受失败 import 的路径前缀约束，不能直接采 unused。
- partial 副本先只创建空 `../dep`，执行精确 dep 模块查询和 loader-fields 查询；再只放入公开依赖的 go.mod、仍不放 Go 源，重跑这两条命令。

这样共17条命令。各次 stdout/stderr 应分别按原字节保存（可 gzip），设20MiB合计输出上限与超时，保留命令回执；不要提交全部标准库输出或个人环境。模块查询在其他上下文可能更新 manifests，产品实现必须继续使用现有私有 staging、防写 guards 和取消通道。
