# 本地 HTTP 示例 / Local HTTP example

在这个目录运行 `go run .`，或在 open Goland 中运行 `main.go`。服务仅监听
`127.0.0.1:18081`，没有外部依赖。打开 `requests.http`，点击请求行左侧的运行图标，
或将光标放入请求并选择右键菜单 **Run HTTP Request**。

- `/echo` 返回真实请求方法、请求体和中文 JSON。
- `/slow` 等待 25 秒；点击原生 Run 面板的 **Stop** 后，服务日志应出现
  `slow: client disconnected`。
- `/redirect` 返回 302；内置执行器显示原始响应，不自动跟随跳转。

验证后正常停止示例服务。端口被占用时可用 `go run . -listen 127.0.0.1:其他端口`，
并修改 `requests.http` 顶部的 `@base`。

Run `go run .` here, then open `requests.http` and use its gutter or
**Run HTTP Request** context action. Responses appear in the native Run console.
Stop the example server when finished. See [supported syntax and limits](../../docs/verification/http-client.md).
