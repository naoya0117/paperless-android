# API v10 fixtures

Response bodies captured from Paperless-ngx 3.4.2 with `Accept: application/json; version=10`.
Tokens are replaced by a fixed fake value.

To refresh them, start the local server and run the capture script (see `tools/paperless-dev/README.md`):

```bash
python3 -I tools/paperless-dev/capture_v10.py --write-fixtures core/network/src/test/resources/fixtures/v10
```

Headers are not part of the fixtures; tests set the relevant ones (`X-Api-Version`, `X-Version`,
`Retry-After`) explicitly. Captured values: `X-Api-Version: 10` and `X-Version: 3.4.2` on every
authenticated response (including 403), `WWW-Authenticate: Token` on 401.
