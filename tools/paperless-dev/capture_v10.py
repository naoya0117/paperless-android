#!/usr/bin/env python3
"""Captures Paperless-ngx API v10 responses from a local server for comparison with the test fixtures.

Standard library only. Creates (or updates) test users on the server and writes one JSON file per scenario
(status, selected headers, body) to the output directory. Tokens are replaced by a placeholder. Each
scenario declares the status it expects; the script stops if the server answers differently, so an
unexpected response never ends up in the fixtures.

Usage: python3 -I capture_v10.py [--base-url http://localhost:8000] [--out build/paperless-dev/v10]
                                 [--write-fixtures core/network/src/test/resources/fixtures/v10]

With --write-fixtures, the response bodies of the scenarios in FIXTURES are also written there (tokens
replaced by FAKE_TOKEN, which the tests expect). The script ends by waiting for the login throttle to reset,
so the smoke tests can run right after it.
"""

import argparse
import base64
import hashlib
import hmac
import json
import pathlib
import re
import struct
import sys
import time
import urllib.error
import urllib.request

ADMIN = ("admin", "admin-password")
PASSWORD = "Fixture-Passw0rd!"
FAKE_TOKEN = "0123456789abcdef0123456789abcdef01234567"
# Test users: name -> global permissions (re-applied on every run).
USERS = {
    "alice": ["view_uisettings", "change_uisettings", "add_uisettings"],
    "limited": [],
    "mfa": ["view_uisettings"],
}
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
MFA_REQUIRED = "MFA code is required"


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def request(base, method, path, *, body=None, token=None, version=10):
    headers = {"Accept": f"application/json; version={version}"}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Token {token}"
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with _OPENER.open(req, timeout=30) as resp:
            return resp.status, dict(resp.headers.items()), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers.items()), e.read()


def json_body(response, context):
    status, _, body = response
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        raise SystemExit(f"{context}: HTTP {status}, not JSON: {body[:200]!r}")


def wait_for_server(base, timeout=300):
    deadline = time.time() + timeout
    while True:
        try:
            request(base, "GET", "/api/")
            return
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            if time.time() > deadline:
                raise SystemExit(f"server at {base} not reachable: {e}")
            print("waiting for the server to start")
            time.sleep(5)


def login(base, username, password, *, code=None, wait_if_throttled=True):
    """POST /api/token/; while throttled (and allowed to), waits for Retry-After and tries again."""
    body = {"username": username, "password": password}
    if code is not None:
        body["code"] = code
    while True:
        response = request(base, "POST", "/api/token/", body=body)
        status, headers, _ = response
        if status != 429 or not wait_if_throttled:
            return response
        delay = int(headers.get("Retry-After", "10"))
        print(f"login throttled, waiting {delay}s")
        time.sleep(delay + 1)


def token_for(base, username, password):
    response = login(base, username, password)
    if response[0] != 200:
        raise SystemExit(f"login as {username} failed: HTTP {response[0]} {response[2][:200]!r}")
    return json_body(response, f"login as {username}")["token"]


def totp(secret, at=None):
    key = base64.b32decode(secret.upper() + "=" * (-len(secret) % 8))
    counter = int((at or time.time()) // 30)
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = (struct.unpack(">I", digest[offset : offset + 4])[0] & 0x7FFFFFFF) % 1_000_000
    return f"{code:06d}"


def ensure_user(base, admin_token, username, permissions):
    """Creates the user, or resets password, name and permissions of an existing one."""
    lookup = request(base, "GET", f"/api/users/?username__iexact={username}", token=admin_token)
    if lookup[0] != 200:
        raise SystemExit(f"looking up {username} failed: HTTP {lookup[0]} {lookup[2][:200]!r}")
    results = json_body(lookup, f"looking up {username}")["results"]
    payload = {"username": username, "password": PASSWORD, "first_name": username.capitalize(),
               "user_permissions": permissions}
    if results:
        status, _, body = request(base, "PATCH", f"/api/users/{results[0]['id']}/", body=payload, token=admin_token)
        expected = 200
    else:
        status, _, body = request(base, "POST", "/api/users/", body=payload, token=admin_token)
        expected = 201
    if status != expected:
        raise SystemExit(f"saving {username} failed: HTTP {status} {body[:300]!r}")


def ensure_mfa(base, username):
    """Enables TOTP for the user unless it already is. The secret is not kept (see README)."""
    response = login(base, username, PASSWORD)
    status, _, body = response
    if status == 400 and MFA_REQUIRED in json_body(response, "MFA check").get("non_field_errors", []):
        return
    if status != 200:
        raise SystemExit(f"login as {username} failed: HTTP {status} {body[:200]!r}")
    user_token = json_body(response, "MFA login")["token"]
    secret = json_body(request(base, "GET", "/api/profile/totp/", token=user_token), "TOTP setup")["secret"]
    for _ in range(2):  # a code generated at the end of a 30 s window may already be stale
        activation = request(base, "POST", "/api/profile/totp/", body={"secret": secret, "code": totp(secret)},
                             token=user_token)
        if activation[0] == 200 and json_body(activation, "TOTP activation").get("success"):
            return
        time.sleep(2)
    raise SystemExit(f"enabling TOTP failed: HTTP {activation[0]} {activation[2][:300]!r}")


def redact(text):
    return re.sub(r'"token":\s*"[0-9a-f]{40}"', '"token": "<redacted>"', text)


def record(out, name, expected_status, response):
    status, headers, body = response
    if status != expected_status:
        raise SystemExit(f"{name}: expected HTTP {expected_status}, got {status}: {body[:300]!r}")
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


def write_fixtures(captured, fixtures):
    fixtures.mkdir(parents=True, exist_ok=True)
    for name in FIXTURES:
        body = json.loads((captured / f"{name}.json").read_text())["body"]
        text = json.dumps(body, indent=2, ensure_ascii=False).replace("<redacted>", FAKE_TOKEN)
        (fixtures / f"{name}.json").write_text(text + "\n")
        print(f"fixture  {name}.json")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8000")
    parser.add_argument("--out", default="build/paperless-dev/v10", type=pathlib.Path)
    parser.add_argument("--write-fixtures", type=pathlib.Path)
    args = parser.parse_args()
    base, out = args.base_url.rstrip("/"), args.out
    out.mkdir(parents=True, exist_ok=True)

    wait_for_server(base)
    admin_token = token_for(base, *ADMIN)
    for username, permissions in USERS.items():
        ensure_user(base, admin_token, username, permissions)
    ensure_mfa(base, "mfa")
    alice = token_for(base, "alice", PASSWORD)
    limited = token_for(base, "limited", PASSWORD)

    def attempt(username, password, **kwargs):
        return login(base, username, password, wait_if_throttled=False, **kwargs)

    record(out, "token", 200, attempt("alice", PASSWORD))
    record(out, "token_invalid_credentials", 400, attempt("alice", "wrong"))
    record(out, "token_blank_username", 400, attempt("", "x"))
    record(out, "token_mfa_required", 400, attempt("mfa", PASSWORD))
    record(out, "token_invalid_mfa_code", 400, attempt("mfa", PASSWORD, code="000000"))
    record(out, "ui_settings", 200, request(base, "GET", "/api/ui_settings/", token=alice))
    record(out, "ui_settings_without_permission", 403, request(base, "GET", "/api/ui_settings/", token=limited))
    record(out, "error_invalid_token", 401, request(base, "GET", "/api/ui_settings/", token="0" * 40))
    record(out, "error_not_acceptable", 406, request(base, "GET", "/api/ui_settings/", token=alice, version=8))
    record(out, "error_not_acceptable_token", 406,
           request(base, "POST", "/api/token/", body={"username": "alice", "password": PASSWORD}, version=8))
    record(out, "error_unauthenticated", 401, request(base, "GET", "/api/ui_settings/"))
    # Last: exhaust the login throttle (it also counts the logins above).
    for _ in range(60):
        response = attempt("alice", "wrong")
        if response[0] == 429:
            break
    record(out, "error_throttled", 429, response)

    if args.write_fixtures:
        write_fixtures(out, args.write_fixtures)

    # Leave the server usable (e.g. for RealServerSmokeTest) by waiting until the throttle resets.
    retry_after = int(response[1].get("Retry-After", "60"))
    print(f"waiting {retry_after + 1}s for the login throttle to reset")
    time.sleep(retry_after + 1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
