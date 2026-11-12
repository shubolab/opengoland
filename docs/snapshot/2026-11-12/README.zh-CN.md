# 2026-11-12 续接快照：open Goland

**完整目标仍是 active，尚未完成。** 本快照记录本轮已经落地的 Windows 进程所有权修复、`.6` 双平台安装包发布、当前工具状态和下一步工作。

## 当前权威状态

- 产品源码提交：`585964bb7e53ab2066c6ea079c3f78ba5469e602`
- 源码摘要：`03f6d4612239c0ae95497da5f2519b4ed72dafdbb83fa9c9c040cc45381b2297`
- 源码输入：512 个文件，已随两平台 provenance 发布
- 公开版本：[open Goland 0.1.0-preview.20261110.6](https://github.com/shubolab/opengoland/releases/tag/v0.1.0-preview.20261110.6)
- 构建运行：[GitHub Actions 43129280667](https://github.com/shubolab/opengoland/actions/runs/43129280667)

## 本轮完成的代码修复

Windows 上有限时长的 Go module/SDK 命令现在通过本仓库 Go 引擎的私有 owner 模式启动。目标进程在启动前被原子加入一个不继承的 Windows Job Object，并设置 `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`。因此取消、超时、IDE 退出，甚至启动器正常结束后仍留下后代进程时，整个命令树都会随 owner 关闭而结束。Unix 继续使用原有的独占 PTY 进程组。

这不是只改一个测试超时：

- 新增 `engine/internal/processowner/`，包含 Windows Job Object 实现和平台边界测试。
- Windows 真机 7 个 `processowner` 测试全部 PASS。
- 原 30 秒 helper 自然退出不再可能伪装成及时清理；取消控制要求在 5 秒门限内结束。
- 参数、环境、工作目录、UTF-8 标准输出/错误流和原始退出码均有真机控制。

机器可读记录：[windows-owner-release.json](evidence/windows-owner-release.json)。

## 测试与发布结果

| 门禁 | 结果 |
| --- | --- |
| 本机 Kotlin | 798/798，143 个报告，0 失败、0 错误、0 跳过 |
| 本机 Go race | 349 个测试事件，12 个有测试包通过，无失败、无缓存 |
| 本机 Go vet | 通过 |
| Windows 发布运行 | Kotlin 798/798，0 失败/错误/跳过；7 个新 Windows 所有权测试 PASS |
| macOS 发布运行 | Kotlin 798/798，0 失败/错误/跳过；2 个可移植 owner 测试 PASS |
| 安装验证 | Windows 实际安装、启动、测试、卸载；macOS 实际 DMG 挂载与安装包验证 |
| 发布 | Windows EXE、macOS DMG、SHA256SUMS 和两平台 provenance 均已上传 |

安装包摘要：

- Windows：`50602b5a7c63d526270feea1e4947704404d02d51ba012a819f7fc2df3475408`
- macOS：`b999d41b339bf72b129f33deb922a24af2577cf0960e9d451738e89f98d0bdc5`

## 工具状态

Computer Use 已实际读取 macOS 应用清单和 Hopper 窗口，工具可用。

REA MCP 注册、技能版本 42 和 207 个工具目录已对齐。本轮实际打开 `/bin/echo`，完成 38 项 Mach-O 结构分析并关闭目标，内置 REA 分析链路可用。

Hopper 已安装到：

`/Users/wangshubo/Applications/Hopper Disassembler.app`

REA 配置已写入对应 `HOPPER_LAUNCHER_PATH`。但 Hopper 第一次启动仍停在官方“Demo 或许可证”窗口；该本地法律选择必须由用户在执行时明确确认。确认后还需重连正在运行的 Codex/REA 进程，才能让原生 Hopper provider 读取新路径。

## 仍未完成

- 36 × 2 GoLand 对比矩阵绝大多数仍是 `unverified`，不能因自动化测试变绿而视为对齐完成。
- 原生桌面工作流仍需继续：未缓存测试、Run 输出、SDK 注册与恢复、反向面板刷新、正常退出和进程消失。
- 官方中文语言包安装、通用插件兼容、所有专有 JetBrains 插件边界仍需持续实测。
- Windows 和 macOS 的真实大型项目、输入法、多光标、重构、调试、性能、Git、终端和插件工作流仍开放。
- 当前 Windows EXE 未做发行者签名，macOS App 使用临时签名且未公证。
- Hopper Demo/license 一次性选择尚未确认，需重连后才能完成原生 provider 验证。

## 续接顺序

1. 先读取本文件和 `docs/verification/releases/0.1.0-preview.20261110.6.json`，再核对当前 Git、GitHub Release、进程和磁盘。
2. 确认 Hopper 的 Demo 或许可证选择，重启/重连后在兼容 Mach-O 上完成 open、分析、关闭闭环。
3. 恢复 `.6` 或后续源码的本机原生验收，不把发布前 `8dfdbe0` 的缓存测试冒充当前结果。
4. 按 36 × 2 矩阵继续找真实缺口，每一版仍执行双平台测试、安装完成后再发布，并保持已公开版本不可变。
