# 插件兼容与简体中文

open Goland 使用原生 IntelliJ 插件系统。此次恢复 Marketplace、Installed、从磁盘安装、
自定义仓库、HTTP 代理和普通插件更新，没有把产品版本伪装成 GoLand，也没有放宽插件的
版本、依赖或签名检查。原生文字搜索请求适用于当前宿主的插件，避免推荐要求切换到另一
IDE 产品的候选；这不表示任何搜索来源、推荐列表或插件都已验证兼容。

普通第三方插件可以按原生流程安装和更新。Go 集成和 LSP4IJ 有本产品所需的固定修复，
因此这两个组件随 open Goland 整包升级，不能被普通插件更新替换。IDE 自身没有账号、
计费或订阅；第三方插件自己的许可、收费与账号要求，以及 Git 等开发服务认证仍保留。

## 截图中的中文包为什么安装失败

用户尝试的语言包是 **232.322**，支持平台 **232.0—232.***。本产品的实际平台为
**IC-261.26222.65**，所以原生安装器拒绝它是正确的兼容性判断。232、261 不是 Go 版本，
也不能通过改 ZIP 内的版本限制解决平台接口和资源版本差异。

新入口从 JetBrains 官方取得与当前平台精确匹配的 **261.26222.65** 中文资源，随后交给
原生插件安装器确认。它不把旧包改成“兼容”，也不改变 IDE 的真实产品代码。

## 安装方法

1. 打开 **Help / Tools > 安装简体中文语言包… / Install Chinese Language Pack…**，
   或通过 **Find Action** 搜索这个名称。
2. 选择从官方获取，或提供匹配的完整官方 IDEA ZIP。
3. 获取和校验结束后，在原生文件窗口选中 **localization-zh.zip**，确认安装。
4. 重新打开 IDE，使用 **Enable Chinese and Restart**；也可以在 **Settings >
   Appearance & Behavior > System Settings > Language and Region** 选择简体中文并重启。

联网获取约 3.6 MB；本地导入要求完整、匹配的官方 ZIP，约 1.6 GB。联网失败或官方
服务器不支持需要的 Range 响应时会报错，不会悄悄改成下载整个大包或加载未经验证的资源。
下载、校验和解压可取消，均在后台进行；原生安装窗口保留平台检查。

语言包主要翻译平台界面，部分新增 Go 对话框与第三方插件仍为英文。
仓库及安装器不附带翻译资源，资源由用户主动从 JetBrains 官方获取并个人安装，
入口提供来源与条款链接。这里不据此推断独立再分发授权。

官方说明：[语言与区域设置](https://www.jetbrains.com/help/idea/2026.1/language-and-region.html)、
[Community 平台语言包讨论](https://intellij-support.jetbrains.com/hc/en-us/community/posts/31963256883730-IntelliJ-IDEA-2025-3-No-Chinese-Language-Pack)。

## 已验证什么

最终源码为 `ede8e30bbb2b6e1fdf984e7c9136aa0f7d51acbc`，480 个构建输入的摘要为
`712d7e4153052f54507e2446e78e7f82bc3dc1109c65bca810465cb59862b87f`。
具体安装副本、哈希与日志计数见[原生记录](plugin-compatibility-native.json)。

本地最终 DMG 经过实际挂载、复制和签名检查，并使用全新配置完成：

- 从官方联网取得语言包，原生文件窗口选择并确认，Installed 显示精确构建号。
- 正常退出、重新打开，再启用中文并自动重启；新进程显示中文菜单与工作台。
- 中文设置及“意图”搜索正常，没有本地化名称导致的说明目录错误。
- Marketplace 搜索并安装 StringManipulation 9.16.0，实际执行大写转换，再用原生 Undo 恢复逐字相同的 Go 原文。
- 使用安装包内的 Go SDK 运行 Unicode 示例，退出码 0；原生测试树显示 TestGreeting 通过。
- 正常退出后，已记录的应用和子进程全部消失。整轮日志 0 Error / 0 Severe，保留 56 条 Warning。

本地完整平台回归为 667 项 / 127 份 XML，0 失败、0 错误、0 跳过，Python 打包控制 58 项通过。
Go 引擎竞态检查和 go vet 使用精确输入、工具、环境与日志指纹复核后的新近记录，
没有把缓存结果写成另一次新测试。最终产品引擎重新构建，内嵌的 Git 版本与上面的干净源码一致。

首次候选安装曾触发 TransactionGuard 的写不安全上下文错误，因此未发布。
修复保留动作和模态窗口原有的安全上下文；相同测试在旧源码精准复现两项失败，
修复后的 26 项中文相关 SDK 控制通过，并由上述全新安装副本再次验证。

[公开 .3 发布记录](releases/0.1.0-preview.20261110.3.json)已包含两端真实安装器、大小与 SHA-256。
两个系统各有 667 项 / 127 份平台测试：macOS 667 项通过；Windows 655 项通过、12 项既有系统相关跳过，均无失败或错误。
两端 Go 竞态检查与 go vet 为该发布重新运行的 fresh 记录。

公开 DMG 已从 Release 匿名完整下载、校验并挂载复制；15,135 个插件/工具文件和全部 672 个 Go 插件类已复核，
平台与 LSP 修复资源也与本地最终候选一致。[公开安装副本的原生检查](plugin-compatibility-public-native.json)
再次完成中文获取/确认安装/自动重启、中文意图搜索、第三方插件编辑与撤销、内置 Go Run/Test。
正常退出后四个已记录的应用和子进程消失，整轮 0 Error / 0 Severe，保留 38 条 Warning；退出后的签名与文件检查再次通过。

Windows EXE 由实际 Windows CI 完成安装、启动、内置引擎测试与卸载检查；本地公开校验读取了 EXE 的 MZ/PE 头，
完整 EXE 的大小与 SHA-256 来自发布 API、SHA256SUMS 和 CI 产物记录，没有将其写成在本机完整下载或交互运行过。
这些局部验证不表示所有第三方插件、所有语言环境或完整 GoLand 功能已经验收。
调试控制、性能与其他剩余项目仍以[完整验收矩阵](../reference/acceptance-matrix.json)为准。
