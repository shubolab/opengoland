# 安装 open Goland

在 [GitHub Releases](https://github.com/shubolab/opengoland/releases) 下载对应系统的安装包。
源码压缩包不是安装程序。

| 系统 | 安装包 | 安装方式 |
| --- | --- | --- |
| macOS，Apple silicon | `open-Goland-版本-macos-arm64.dmg` | 打开镜像，将 `open Goland.app` 拖到 Applications，然后打开应用 |
| Windows x64 | `open-Goland-版本-windows-x64-setup.exe` | 运行安装程序，完成向导，从开始菜单启动 |

安装包包含 JVM、Go 1.26.10 SDK、gopls 0.24.0 和 Delve 1.27.2。
基本 Go 编辑、构建和测试无需另装 Java、Go 或语言服务工具。
新项目默认使用内置工具；在 Settings > Go 中明确选择的 SDK 和工具路径优先。
Git、GitHub 和私有仓库保留各自的开发凭据认证。
IDE 本身不要求账号，没有计费、订阅、试用倒计时或付费功能门槛。

第一次启动后，点击欢迎页 **New Project**，选择项目位置和 module path，
即可创建带示例与测试的 Go 项目。已有项目使用 **Open** 或 **Clone Repository**。
新建项目和 **Settings > Go** 使用同一个 SDK 选择器：可以使用内置 SDK、
通过 **Add Local SDK** 选择已安装的 Go 目录，或通过 **Download Go SDK**
下载当前稳定版本。下载会验证大小、SHA-256 和 SDK 本身；完成后自动选中。
在向导中设置的 SDK 和环境变量随项目保存，正常退出后重新打开会恢复这些设置。

当前是预览版本，完整 GoLand 功能对齐仍在进行，详见
[验收矩阵](reference/acceptance-matrix.json)。macOS 包采用临时签名，尚未公证；
Windows 安装器尚未使用发行者证书签名。安装时系统可能要求对该应用进行确认。

macOS 调试当前使用 Apple 的 `debugserver`，需要安装 Command Line Tools 或 Xcode。
纯 Go 构建、测试和语言服务使用内置 Go SDK。包含 cgo 或外部依赖的项目，仍需其自身的
C 工具链、依赖源及访问凭据。
自行选择或下载的 SDK 首次调试时，`test2json` 工具可能尚未准备完成，
这一冷启动场景仍有已知限制。
标准 Pause 尚不能暂停；首次切换 goroutine 后的步进/继续也仍有待修复项。
具体证据与范围见[调试器当前限制](verification/debug-session-controls.md)。

每个版本同时发布 `.exe`、`.dmg`、`SHA256SUMS` 和构建验证记录。
自动发布流程要求 Windows 与 macOS 两端构建及安装检查成功。
安装包、校验值和验证记录上传完整后，Release 才会公开；已公开版本的安装包不覆盖更新，
后续修改使用新版本号。

Windows 启动子进程仍受系统工作目录长度限制；项目根目录超过 `MAX_PATH` 时，
可能出现 `CreateProcess 267`。当前尚未验证任意超长项目路径支持，建议将项目放在较短路径下。
构建测试仅缩短框架重复生成的目录前缀，保留中文、空格、文件内容及相对路径验证。
参见 [Microsoft 的工作目录说明](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-setcurrentdirectory#parameters)。
