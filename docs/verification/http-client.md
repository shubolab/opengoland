# 内置 HTTP 请求执行器 / Built-in HTTP runner

**版本范围：**当前公开安装版 `.20261113.3` 已提供下文的批量执行、Services
响应界面和保存功能；[双平台发布记录](releases/0.1.0-preview.20261113.3.json)绑定
安装器、原始测试和源码。此前 `.20261113.2` 使用单请求 Run 控制台，其历史记录保留不变。

可直接运行的本地示例见 [fixtures/http-client](../../fixtures/http-client)。

## 如何使用

1. 打开 `.http` 文件，使用请求行旁或右键菜单里的 **Run HTTP Request** 运行当前请求。
   编辑器顶部和右键菜单中的 **Run All Requests** 按文件次序执行所有请求。
2. 在原生 **Services** 面板中查看 HTTP 批次结果。单请求直接显示响应；
   多请求可以选择各自的命名结果，查看状态码、耗时、响应字节数、正文和响应头。
   Run 仍保留执行日志与平台的进程停止入口。
3. 连接失败、未定义变量等错误只影响相应请求，后续请求继续执行。
   4xx/5xx 是实际收到的 HTTP 响应，仍显示状态码和正文。
4. **Stop All** 用于取消当前请求和本批尚未发送的请求；关闭项目会取消其拥有的请求。
   **Run Again** 重放这一次运行捕获的内容。要发送刚修改的内容，请从编辑器重新运行。
5. **Save Response…** 将当前仍在缓存中的响应正文保存到用户选择的位置，包含二进制正文。
   保存的是原始响应字节，JSON 美化和界面换行处理不会改变保存内容。

每次运行使用点击时的编辑器快照，无需先保存 `.http` 文件。
这些是当前源码提供的入口；自动化覆盖与实际桌面验收分别记录，不能互相替代。

## 语法和显示范围

- 绝对 `http://` / `https://` URL；GET、POST、PUT、PATCH、DELETE、HEAD、OPTIONS。
- 使用 `###` 分隔请求。`# @name 名称` 或 `// @name 名称` 优先于 `###` 后的标题；
  同一请求出现多条显式名称时使用第一条。名称可以重复，各请求仍保留独立结果。
- `Header-Name: value`，空行后的 UTF-8 文本请求体。
- 请求体两端的空白行用于分隔，不会发到服务器；内容行的前后空格和 Tab 保留，
  正文内部的 CRLF 保留。六组参考端真实请求的原始字节已用于回归。
- 在请求行前声明 `@name = value` 文件变量，在 URL、请求头值和正文中使用 `{{name}}`。
  **唯一声明在整份文件中可见，允许前向引用。** 同名变量声明多次时，引用该名字的
  请求明确失败；没有引用它的请求继续执行。嵌套引用有深度和复杂度边界。
- 有效 JSON 可以格式化并着色；其他文本按类型使用只读编辑器显示。
  HTML 和脚本响应作为文字展示。无效或超出格式化边界的 JSON 保留原文。
- 响应按声明的字符集解码，默认 UTF-8；不支持的字符集或无效字节会给出显示提示。
  二进制响应显示字节数，可通过 **Save Response…** 保存。

命名目前用于识别请求和结果；引用先前响应、脚本变量、动态变量与环境文件仍未实现。

## 运行与缓存边界

- 每条请求有 30 秒总时限，包含响应体读取；批量请求各自计时。
  请求体和响应体各最多 1 MiB，分块响应也受限制。
- 文档最多 2,097,152 个 UTF-16 单元；URL 编码后最多 16 KiB。
  展开后的请求头合计最多 64 KiB、200 个，值使用 ASCII/Latin-1。
- 响应头与文本显示有独立上限，超限会提示；Run 日志达到上限后省略后续日志，
  请求执行继续。显示省略不会把不完整文字当作原始字节保存。
- 项目响应缓存按原始字节及显示文本的计量预算最多保留 16 MiB，并保留最多
  20 个已完成批次；活动批次单独保留。旧正文被淘汰后，状态仍可查看，
  查看或保存原文需要重新运行。这个预算不等于整个 IDE 的内存用量。
- 响应缓存位于内存中，不自动往项目里写 HTTP 历史或响应文件。
  只有明确选择 **Save Response…** 并指定路径时才保存文件。
- 默认不跟随重定向，不保存或自动复用 Cookie；HTTPS 使用 JVM 默认的证书校验。
  URL 中的用户名/密码不接受，可明确填写 Authorization 请求头。
  Host、Content-Length、Connection、Expect 和 Upgrade 由传输层管理。

## 尚未支持

环境文件、动态变量、前置/响应脚本、响应引用与断言、`< ./file` 文件请求体、
multipart 文件语法、自动按指令保存响应、OAuth 和凭据库、持久请求历史、
WebSocket、gRPC、GraphQL 专用语法，以及 IDE 代理设置的独立集成仍未实现。
不支持的脚本、文件体和指令会在相应请求发出之前报错。

HTTP 功能不代表 SQL 数据库连接、Docker 执行或 Go Template 语义分析已经完成。
Windows 原生交互和完整 `TOOLS-04` 矩阵仍保持开放。

## 验证与参考端对照

已公开 `.13.2` 的正文边界与桌面证据见
[原生执行记录](http-client-native-20261113.json)和
[正文参考记录](http-client-reference-20261113.json)。
当前新增语义的原始请求与人工预期保存在
[`http-plan-reference-20261113.json`](../../src/test/resources/http-plan-reference-20261113.json)。
它来自实际 GoLand 2026.1.4 的运行观察，包含命名、顺序、独立失败、前向引用和重复声明。
[批量参考记录](http-batch-reference-20261113.json)保留了冻结的服务端事件和截图，
只证明其中列出的参考端行为，不代替新产品原生验收。

| 项目 | GoLand 2026.1.4 本机观察 | 本仓库 `.13.3` 范围 |
| --- | --- | --- |
| 正文边界 | 去除空白边界行，保留正文行的空格/Tab | 六组真实字节向量已用于回归；修复已进入公开 `.13.2` |
| 命名 | 首条显式 `@name` 优先于分隔标题 | 使用相同优先级，同名请求保持独立身份 |
| 批量运行 | 503、连接错误和缺失变量之后，后续请求仍实际发送 | 按序执行、逐项记录响应或失败；传输与 UI 分别验证 |
| 文件变量 | 唯一声明允许前向引用；重复名字的引用失败 | 使用相同的全文件声明规则，错误不污染无关请求 |
| 结果展示 | 单请求直接显示格式化响应，多请求显示命名请求树 | 原生 Services 命名结果、只读正文/响应头、JSON 美化和手动保存原文 |
| 停止 | 本轮两次 Stop 的 UI 标记终止，但服务器仍收到下一条，首条到 25 秒自然结束，未见断开 | 明确的 Stop All 取消当前与未发送队列；必须另证实际连接断开和后项不发送 |
| 环境/历史/cURL | 有对应入口，本轮未验收其完整流程 | 未实现环境文件、持久历史和 cURL 转换 |

参考端 Stop 的两次有限观察按差异保留，没有写成成功取消，也不推广为其所有版本或场景的结论。
完整 GoLand 对齐和双平台原生矩阵继续开放。

The published `.20261113.3` installers include **Run All Requests**, named results in
native Services, per-request failures, formatted read-only response viewers,
**Stop All** and original-byte **Save Response…**. The two-platform release,
source inputs and original test records have been independently verified.
Unique file-variable declarations are visible throughout the document, including
forward references; duplicate names fail only requests that reference them.
Environment files, scripting, response references, persistent history and full
JetBrains HTTP Client compatibility remain open.
