# 构建标签与补全示例

这个小项目演示两件事：Go 构建标签决定哪些文件参与当前构建；代码补全可以同时补齐类型名和导入语句，并一起撤销。

这四个源文件与 [README 补充动图](../../docs/demo/native-build-context-completion.gif)录制时临时项目的对应文件逐字节一致。动图来自 `0.1.0-preview.20261110.6` 开发候选版，使用 macOS 27.2 arm64；它不是已发布安装包的完整验收证明。需要 Go 1.26 或更新版本，没有第三方依赖。

## 构建标签

在此目录打开终端：

```sh
go test .
go test -tags native_extra .
```

第一条运行 `TestDefaultContext`，第二条运行 `TestExtraContext`。两个测试文件顶部的 `//go:build` 条件互斥，因此每次只应运行其中一个。

在录制的 IDE 中：

1. 打开这个目录，在 `default_test.go` 的测试函数上运行测试，查看中文输出和原生测试树。
2. 在 **Settings > Go > Build tags** 填入 `native_extra` 并应用。
3. 重跑原来生成的精确测试配置，应提示该文件被当前构建上下文排除，而不是把旧的通过结果当作本次成功。
4. 打开 `extra_test.go`，在测试函数上启动调试，查看另一项测试通过。

录制时，打开被排除的文件还会出现语言服务错误通知；这是当时仍在修复的问题，不是构建标签失效。完整记录见[原生观察](../../docs/reference/go-build-context-completion-native-20261111.json)。

## 补全与撤销

在 `completion.go` 的包声明之后粘贴：

```go
func use() {
    _ = "中文😀"; var b bytes.Buf
    _ = b
}
```

将光标放在 `bytes.Buf` 末尾，触发 **Code > Code Completion > Basic**，接受 `Buffer`。应得到 `bytes.Buffer` 和一条 `import "bytes"`。执行一次 Undo，应同时移除该导入并还原成 `bytes.Buf`。

撤销后的代码本来就尚未完成，出现未定义名称提示是正常的。继续运行测试之前，撤回刚才粘贴的函数并保存，让文件恢复为原来的包声明。

录制使用隔离的 IDE 配置。由于 macOS 快捷键冲突，测试配置为标准 Basic 动作临时增加了 `Control+Option+K`，再按回车接受；这不是产品的默认快捷键。动图由四张真实窗口截图按操作顺序播放，压缩了空闲等待，没有伪造补全候选列表或裁掉平台提示。
