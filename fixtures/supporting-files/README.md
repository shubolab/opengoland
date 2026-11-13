# Supporting files acceptance fixture

Use this directory to verify how open Goland presents files commonly found in
Go projects:

- `Dockerfile`: native Dockerfile lexical highlighting and bracket/comment behavior.
- `query.sql`: native SQL lexical highlighting.
- `config.yaml` and `payload.json`: bundled structured-language support.
- `view.gotmpl`, `view.go.html`, `mail.go.tmpl` and `script.go.txt`: Go
  template file types and editor behavior.
- `api.http`: native HTTP request highlighting; the built-in runner can send
  requests using the gutter or **Run HTTP Request** context action. This file
  expects your own local service on port 8080. For a self-contained example,
  use [the local HTTP fixture](../http-client).

This fixture does not claim Docker execution or database connectivity. HTTP
environment files, scripts and credential management remain outside the built-in
runner; see [supported syntax and limits](../../docs/verification/http-client.md).
