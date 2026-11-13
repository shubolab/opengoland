# 内置 HTTP 请求执行器 / Built-in HTTP runner

当前源码在 `.http` 高亮基础上加入了实际请求执行。打开请求文件，点击请求行左侧的
运行图标，或在请求内选择右键菜单 **Run HTTP Request**。原生 **Run** 面板显示状态码、
耗时、响应字节数、响应头和文本响应体；**Stop** 取消正在进行的连接或响应读取。
关闭项目也会取消该项目拥有的请求。请求使用点击时的编辑器内容，无需先保存文件。

可直接运行的本地示例见 [fixtures/http-client](../../fixtures/http-client)。

## 支持范围

- 绝对 `http://` / `https://` URL；GET、POST、PUT、PATCH、DELETE、HEAD、OPTIONS。
- `###` 分隔多个请求，按光标或所点击的 gutter 选择其中一个。
- `Header-Name: value`，空行后的 UTF-8 文本请求体。
- 请求体两端的空白行只用于分隔请求，不会发到服务器；正文行的前导空格、
  尾部空格和 Tab 保留。六组参考端真实请求的原始字节已用于回归。
- `@name = value` 文件变量，以及 URL、请求头值和请求体中的 `{{name}}`；
  变量按请求之前的声明生效，支持有限深度的引用。
- 显示包括 4xx/5xx 在内的真实 HTTP 响应；文本按响应声明的字符集解码，默认 UTF-8。
  二进制响应仅显示字节数，不提供保存功能。
- 整个请求默认 30 秒上限，包含响应体读取；请求体和响应体各最多 1 MiB。
  不会因缺少 Content-Length 而无限积累流式响应。
- URL 编码后最多 16 KiB，展开后的请求头合计最多 64 KiB、200 个；
  请求头值支持 ASCII/Latin-1，中文等内容请放入 UTF-8 请求体。
- 默认不跟随重定向，不保存或自动复用 Cookie；HTTPS 使用 JVM 默认的证书校验。

## 尚未支持

这不是完整的 JetBrains HTTP Client 实现。环境文件、动态变量、前置/响应脚本、
文件上传/下载、multipart 文件语法、OAuth 和凭据库、请求历史、断言与测试运行器、
WebSocket、gRPC、GraphQL 专用语法，以及 IDE 代理设置的独立集成仍未实现。
不支持的脚本、文件体和指令会在网络请求开始前报错；不会把它们作为普通请求体发送。
URL 中的用户名/密码不接受，可明确填写 Authorization 请求头。
Host、Content-Length、Connection、Expect 和 Upgrade 由传输层管理，不能手动设置。

请求与响应显示在当前 Run 控制台；没有另外写入 HTTP 历史文件。
响应不会自动美化 JSON，也不代表 SQL、Docker 或 Go Template 执行能力已完成。
Windows 原生交互验收、GoLand 参考端对照和完整 `TOOLS-04` 矩阵仍保持开放。

## 验证

针对性验证使用真实 loopback HTTP 服务：UTF-8 POST、请求头、4xx 响应、302 原始响应、
Cookie 不复用、分块响应超限、正在读取的连接取消、响应头之后的超时，以及原生
ProcessHandler 的结果与 Stop。另有平台 gutter 分发、未解析/循环变量、请求头注入、
脚本拒绝和展开大小/复杂度边界测试。

具体源码摘要、完整测试数和桌面观察结果见本次的
[运行记录](http-client-native-20261113.json)。自动化和桌面证据分别列出，不能互相替代。

## 与 GoLand 参考端的实测差距

同一份 UTF-8 POST 最初在本仓库发出 41 字节，GoLand 发出 39 字节；差异来自
正文后的两个分隔换行。现已按六组参考请求修正边界解析，并用真实网络回归验证。
原始样例、参考端返回的正文与截图见 [参考记录](http-client-reference-20261113.json)。

| 项目 | GoLand 2026.1.4 本机观察 | 本仓库当前范围 |
| --- | --- | --- |
| 请求体边界 | 去除空白边界行，保留正文行的空格/Tab | 六组原始字节已通过自动化对照；修正版桌面记录单独绑定 |
| 结果展示 | Services 中有命名请求条目，响应 JSON 有高亮和格式化，提供响应文件链接 | Run 控制台显示原始文本，没有对应 Services 分组或响应文件保存 |
| 批量执行 | 原生 Run All 实际执行了六组请求 | 当前只运行所选单个请求 |
| 环境/历史/cURL | 编辑器有对应入口；本轮未验收其完整流程 | 未实现环境文件、历史和 cURL 转换 |

该对照只覆盖本机 HTTP 工作流，没有关闭整个 TOOLS-04 或双平台对齐矩阵。

The built-in runner sends the selected `.http` request from the current editor
snapshot and displays the response in the native Run console. It supports basic
HTTP methods, headers, UTF-8 bodies and bounded file-variable substitution.
Stop/project close cancel owned requests. Redirects and cookies are not followed
or retained automatically. A 30-second whole-response deadline and 1 MiB body
limits apply. Environment files, scripting, file transfers, credential management
and complete JetBrains HTTP Client compatibility remain open.
