# Supporting files acceptance fixture

Use this directory to verify how open Goland presents files commonly found in
Go projects:

- `Dockerfile`: Dockerfile TextMate syntax and bracket/comment behavior.
- `query.sql`: SQL TextMate syntax.
- `config.yaml` and `payload.json`: bundled structured-language support.
- `view.gotmpl`: Go template file type and editor behavior.
- `api.http`: plain-text request document unless a compatible HTTP client
  plugin is installed from Marketplace.

This fixture does not claim Docker execution, database connectivity or built-in
HTTP request execution.
