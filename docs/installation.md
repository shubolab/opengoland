# 安装 open Goland

在 [GitHub Releases](https://github.com/shubolab/opengoland/releases) 下载对应系统的安装包。
源码压缩包不是安装程序。

| 系统 | 安装包 | 安装方式 |
| --- | --- | --- |
| macOS，Apple silicon | `open-Goland-版本-macos-arm64.dmg` | 打开镜像，将 `open Goland.app` 拖到 Applications，然后打开应用 |
| Windows x64 | `open-Goland-版本-windows-x64-setup.exe` | 运行安装程序，完成向导，从开始菜单启动 |

安装包包含 JVM、Go 1.26.10 SDK、gopls 0.24.0 和 Delve 1.27.2。
基本 Go 编辑、构建和测试无需另装 Java、Go 或语言服务工具。
新项目默认使用内置工具；在 Settings > Go 中明确选择的路径优先。
Git、GitHub 和私有仓库保留各自的开发凭据认证。
IDE 本身不要求账号，没有计费、订阅、试用倒计时或付费功能门槛。

当前是预览版本，完整 GoLand 功能对齐仍在进行，详见
[验收矩阵](reference/acceptance-matrix.json)。macOS 包采用临时签名，尚未公证；
Windows 安装器尚未使用发行者证书签名。安装时系统可能要求对该应用进行确认。

macOS 调试当前使用 Apple 的 `debugserver`，需要安装 Command Line Tools 或 Xcode。
纯 Go 构建、测试和语言服务使用内置 Go SDK。包含 cgo 或外部依赖的项目，仍需其自身的
C 工具链、依赖源及访问凭据。

每个版本同时发布 `.exe`、`.dmg`、`SHA256SUMS` 和构建验证记录。
自动发布流程要求 Windows 与 macOS 两端构建及安装检查成功。
