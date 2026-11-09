# Local Paperless-ngx for API checks

A throwaway Paperless-ngx (with Redis and PostgreSQL) for checking real API responses. Runs locally only;
it is not used by CI (the runner has too little memory). Credentials are fixed and insecure — never expose it.

```bash
# Start (http://localhost:8000, admin / admin-password)
docker compose -f tools/paperless-dev/compose.yml -p paperless-dev up -d

# Create test users and capture responses to build/paperless-dev/v10; with --write-fixtures,
# also refresh the unit test fixtures.
python3 -I tools/paperless-dev/capture_v10.py \
  --write-fixtures core/network/src/test/resources/fixtures/v10

# Smoke tests of the real client against the server (skipped unless PAPERLESS_DEV_URL is set)
PAPERLESS_DEV_URL=http://localhost:8000 ./gradlew :core:network:test --tests '*RealServerSmokeTest'

# Stop and delete all data
docker compose -f tools/paperless-dev/compose.yml -p paperless-dev down -v
```

The capture script creates these users (password `Fixture-Passw0rd!`):

| User | Purpose |
|---|---|
| `alice` | UI settings permissions |
| `limited` | no permissions (403 on `/api/ui_settings/`) |
| `mfa` | TOTP enabled (the secret is not kept) |

The server limits logins per client IP (`PAPERLESS_TOKEN_THROTTLE_RATE`, raised to 20/min here). The script
exhausts the limit on purpose to capture the 429 response, then waits until it resets, so the smoke tests can
run right after it. If it stops with "expected HTTP …", a previous run or other logins used up the limit:
wait a minute and run it again.
