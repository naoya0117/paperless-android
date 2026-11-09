#!/usr/bin/env python3
"""Captures Paperless-ngx API v10 responses from a local server for comparison with the test fixtures.

Standard library only. Creates test users on the server (idempotent) and writes one JSON file per
scenario (status, selected headers, body) to the output directory. Tokens are replaced by a placeholder.

Usage: python3 -I capture_v10.py [--base-url http://localhost:8000] [--out build/paperless-dev/v10]
                                 [--write-fixtures core/network/src/test/resources/fixtures/v10]

With --write-fixtures, the response bodies of the scenarios in FIXTURES are also written there (the
tokens replaced by FAKE_TOKEN, which the tests expect).
"""

import argparse
import base64
import hashlib
import hmac
import json
import pathlib
import re
import struct
import time
import urllib.error
import urllib.request

ADMIN = ("admin", "admin-password")
PASSWORD = "Fixture-Passw0rd!"
FAKE_TOKEN = "0123456789abcdef0123456789abcdef01234567"
# Scenarios whose body is used as a unit test fixture (file name without .json).
FIXTURES = [
    "token",
    "token_invalid_credentials",
    "token_blank_username",
    "token_mfa_required",
    "token_invalid_mfa_code",
    "ui_settings",
    "ui_settings_without_permission",
    "error_invalid_token",
    "error_not_acceptable",
    "error_unauthenticated",
    "error_throttled",
]
HEADERS_OF_INTEREST = ["content-type", "x-api-version", "x-version", "retry-after", "www-authenticate", "location"]


def request(base, method, path, *, body=None, token=None, version=10, raw_body=None):
    headers = {"Accept": f"application/json; version={version}"}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if raw_body is not None:
        data = raw_body
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Token {token}"
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None

    opener = urllib.request.build_opener(NoRedirect)
    try:
        with opener.open(req) as resp:
            return resp.status, dict(resp.headers.items()), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers.items()), e.read()


def totp(secret, at=None):
    key = base64.b32decode(secret.upper() + "=" * (-len(secret) % 8))
    counter = int((at or time.time()) // 30)
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = (struct.unpack(">I", digest[offset : offset + 4])[0] & 0x7FFFFFFF) % 1_000_000
    return f"{code:06d}"


def wait_for_login_throttle(base):
    """A previous run may have used up the login throttle; wait until it resets."""
    while True:
        status, headers, _ = request(base, "POST", "/api/token/", body={"username": "", "password": ""})
        if status != 429:
            return
        delay = int(headers.get("Retry-After", "10"))
        print(f"login throttled, waiting {delay}s")
        time.sleep(delay + 1)


def token_for(base, username, password):
    while True:
        status, headers, body = request(base, "POST", "/api/token/", body={"username": username, "password": password})
        if status != 429:
            break
        delay = int(headers.get("Retry-After", "10"))
        print(f"login throttled, waiting {delay}s")
        time.sleep(delay + 1)
    if status != 200:
        raise SystemExit(f"login as {username} failed: {status} {body[:200]!r}")
    return json.loads(body)["token"]


def ensure_user(base, admin_token, username, permissions):
    status, _, body = request(base, "GET", f"/api/users/?username__iexact={username}", token=admin_token)
    results = json.loads(body)["results"]
    if results:
        return
    payload = {"username": username, "password": PASSWORD, "first_name": username.capitalize(),
               "user_permissions": permissions}
    status, _, body = request(base, "POST", "/api/users/", body=payload, token=admin_token)
    if status != 201:
        raise SystemExit(f"creating {username} failed: {status} {body[:300]!r}")


def ensure_mfa(base, username):
    """Enables TOTP for the user unless already enabled; returns the secret (stored next to the output)."""
    status, _, _ = request(base, "POST", "/api/token/", body={"username": username, "password": PASSWORD})
    if status != 200:
        return None  # MFA already enabled
    user_token = token_for(base, username, PASSWORD)
    _, _, body = request(base, "GET", "/api/profile/totp/", token=user_token)
    secret = json.loads(body)["secret"]
    status, _, body = request(base, "POST", "/api/profile/totp/", body={"secret": secret, "code": totp(secret)},
                              token=user_token)
    if status != 200:
        raise SystemExit(f"enabling TOTP failed: {status} {body[:300]!r}")
    return secret


def redact(text):
    return re.sub(r'"token":\s*"[0-9a-f]{40}"', '"token": "<redacted>"', text)


def record(out, name, response):
    status, headers, body = response
    if status == 429 and name != "error_throttled":
        raise SystemExit(f"{name}: unexpectedly throttled; wait a minute and run again")
    text = body.decode(errors="replace")
    try:
        parsed = json.loads(redact(text))
    except json.JSONDecodeError:
        parsed = redact(text)[:2000]
    selected = {k.lower(): v for k, v in headers.items() if k.lower() in HEADERS_OF_INTEREST}
    (out / f"{name}.json").write_text(
        json.dumps({"status": status, "headers": selected, "body": parsed}, indent=2, ensure_ascii=False) + "\n"
    )
    print(f"{status}  {name}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8000")
    parser.add_argument("--out", default="build/paperless-dev/v10")
    parser.add_argument("--write-fixtures", type=pathlib.Path)
    args = parser.parse_args()
    base, out = args.base_url.rstrip("/"), pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    wait_for_login_throttle(base)
    admin_token = token_for(base, *ADMIN)
    ensure_user(base, admin_token, "alice", ["view_uisettings", "change_uisettings", "add_uisettings"])
    ensure_user(base, admin_token, "limited", [])
    ensure_user(base, admin_token, "mfa", ["view_uisettings"])
    ensure_mfa(base, "mfa")
    alice = token_for(base, "alice", PASSWORD)
    limited = token_for(base, "limited", PASSWORD)

    def login(**body):
        return request(base, "POST", "/api/token/", body=body)

    record(out, "token", login(username="alice", password=PASSWORD))
    record(out, "token_invalid_credentials", login(username="alice", password="wrong"))
    record(out, "token_blank_username", login(username="", password="x"))
    record(out, "token_mfa_required", login(username="mfa", password=PASSWORD))
    record(out, "token_invalid_mfa_code", login(username="mfa", password=PASSWORD, code="000000"))
    record(out, "ui_settings", request(base, "GET", "/api/ui_settings/", token=alice))
    record(out, "ui_settings_without_permission", request(base, "GET", "/api/ui_settings/", token=limited))
    record(out, "error_invalid_token", request(base, "GET", "/api/ui_settings/", token="0" * 40))
    record(out, "error_not_acceptable", request(base, "GET", "/api/ui_settings/", token=alice, version=8))
    record(out, "error_not_acceptable_token",
           request(base, "POST", "/api/token/", body={"username": "alice", "password": PASSWORD}, version=8))
    record(out, "error_unauthenticated", request(base, "GET", "/api/ui_settings/"))
    # Last: the login throttle also counts the requests above.
    for _ in range(30):
        response = login(username="alice", password="wrong")
        if response[0] == 429:
            break
    record(out, "error_throttled", response)

    if args.write_fixtures:
        write_fixtures(out, args.write_fixtures)


def write_fixtures(captured, fixtures):
    fixtures.mkdir(parents=True, exist_ok=True)
    for name in FIXTURES:
        body = json.loads((captured / f"{name}.json").read_text())["body"]
        text = json.dumps(body, indent=2, ensure_ascii=False).replace("<redacted>", FAKE_TOKEN)
        (fixtures / f"{name}.json").write_text(text + "\n")
        print(f"fixture  {name}.json")


if __name__ == "__main__":
    main()
