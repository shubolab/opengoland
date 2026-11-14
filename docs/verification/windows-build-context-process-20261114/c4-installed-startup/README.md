# C4 Windows 安装后启动失败证据

本目录只保留 C4 的失败事实：[构建43536268282](https://github.com/shubolab/opengoland/actions/runs/43536268282)，源码 `07bb8eb35f48f11564bac5b076fa7752ff03466a`，版本 `0.1.0-preview.20261114.2`，`workflow_dispatch`、`publish=false`。

Windows 的 Gradle 构建通过，真实 EXE 已生成并成功安装；失败发生在安装后的启动验收。`TerminalEngine.<clinit>` 请求 `FeatureUsageSettingsEvents` 服务，触发平台的“类初始化不得依赖服务”SEVERE；实际调用经过 `TerminalOptionsProvider`，进入 `GoTerminalCompletionDefaults.applyOnce(GoTerminalCustomizer.kt:38)` 与 `GoTerminalCompletionInitializer.execute(:48)`。该 SEVERE **已经出现在先行的 `-version` 进程 stderr**；后续 `gui_startup` 检查包含这条旧记录的 `idea.log` 后拒绝继续，`native-gui-windows=[]`。不能据此声称 GUI 进程自身首次触发错误，或已进入项目窗口；真实启动错误仍必须修复，不能清日志绕过。

macOS job 成功不代表双端已经可发布：Windows job 失败，整个 producer 为 `failure`，发布步骤跳过。Mac artifact `14091428061`（940933995B），Windows artifact `14091462141`（889830320B）；这些是产物元数据，不是双端验收通过证明。

`idea.log` 是未改字节的 CI 隔离日志；`call-chain.json` 保留实际关键调用链及阶段澄清；`range-receipt.json` 保留有界 HTTP Range/ZIP 条目校验记录，并清除私密下载 URL。仅读取中心目录和指定小诊断条目（取证总传输约211287B），没有下载或校验完整889MB ZIP；条目 CRC/SHA 与 API 给出的整包 digest **不能替代完整 ZIP SHA 校验**。未复制第三方源码、配置或环境 dump。`SHA256SUMS` 仅校验本目录提交文件。
