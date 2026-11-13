# 本地 HTTP 示例 / Local HTTP example

在这个目录运行 `go run .`，或在 open Goland 中运行 `main.go`。服务仅监听
`127.0.0.1:18081`，没有外部依赖。打开 `requests.http`，点击请求行左侧的运行图标，
或将光标放入请求并选择右键菜单 **Run HTTP Request**。

新增的批量与 Services 响应界面目前在后续开发源码中，公开 `.20261113.2`
安装器仍使用单请求 Run 控制台；以仓库首页下载区与版本记录为准。

- `batch.http`：点击编辑器顶部 **Run All Requests**，依次查看三条命名结果、
  格式化 JSON 和响应头；中间的真实 503 不会阻止最后一条中文 POST。
- `batch-errors.http`：中间一条因缺少变量而失败，但前后两条照常发送。
- `batch-stop.http`：第一条等待时点击 **Stop All**。服务日志应出现
  `slow: client disconnected`，且不应出现后续 `unavailable` 请求。
- `binary.http`：选择 **Save Response…** 保存原始八字节响应。保存的是原始字节，
  不是界面中格式化后的文字。

- `/echo` 返回真实请求方法、请求体和中文 JSON。
- `/slow` 等待 25 秒；点击原生 Run 面板的 **Stop** 后，服务日志应出现
  `slow: client disconnected`。新 Services 界面中对应操作是 **Stop All**。
- `/redirect` 返回 302；内置执行器显示原始响应，不自动跟随跳转。

验证后正常停止示例服务。端口被占用时可用 `go run . -listen 127.0.0.1:其他端口`，
并修改对应 HTTP 文件顶部的 `@base`（`binary.http` 使用完整 URL）。

Run `go run .` here, then open `requests.http` and use its gutter or
**Run HTTP Request** context action. Responses appear in the native Run console.
Later development source adds **Run All Requests**, named results in native
Services, per-request errors, **Stop All** and original-byte **Save Response…**.
The published `.20261113.2` installers still use the single-request Run console;
check the repository download section for the current delivery version.
Stop the example server when finished. See [supported syntax and limits](../../docs/verification/http-client.md).
