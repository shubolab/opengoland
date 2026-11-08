# Change Signature evidence

这是 LANG-07 的研究记录，不是产品实现或桌面验收。

- `gopls-probes.json`：12 个 gopls v0.21.1 真实 CLI 请求，完整 before/after 源码、返回 WorkspaceEdit、编译诊断和运行 trace。所有基线先编译通过。
- `lowering-probes.json`：9 个独立手工候选 rewrite 形状的真实 Go 执行，包括保持效果的 adapter、defer 直接 hoist、非内联 deferred adapter 的 recover 反例、结果和接口关系。
- `sources.json`：五份固定官方 Go OSS 源文件的 URL、SHA-256、本地原始路径与保存路径。下载的 pinned remote bytes 与 module cache 完全一致。
- `source/`：上述完整原始字节，以 `.go.txt` 防止被当成项目 Go 包编译；保留 Go Authors 的 BSD 许可。仅用于证据，不进入产品代码。没有商业 GoLand 实现。

本机可重新执行 `.tools/change_signature_probe.py` 和 `.tools/change_signature_lowering_probe.py`；两脚本只使用自己创建的临时 fixture，固定本机工具路径，设置 GOTOOLCHAIN=local/GOPROXY=off/GOTELEMETRY=off，不改用户项目。报告里的临时 file URI 是历史证据，目录在探针完成后已删除。

仓库内的可移植重放入口使用已保存的完整 fixture，并核对 gopls 版本及语义/编译结果，不依赖 `.tools`：

```sh
python3 docs/verification/change-signature-evidence/replay.py --output /tmp/opengoland-signature-replay.json
```

可用 `--go /path/to/go --gopls /path/to/gopls` 选择已安装工具；不会安装或升级它们。它只允许对自己临时 fixture 中预先列出的文件应用返回编辑。

所需 Native UI/Undo 验证尚未执行。GoLand 参考能力来自 2026.1 官方文档声明；本轮 REA 路径读取被无 active app 的限制阻挡，且严格未激活参考应用。
