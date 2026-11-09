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
import urllib.parse
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
    "tags",
    "tag_child",
    "tag_created",
    "tag_updated",
    "error_duplicate_name",
    "correspondents",
    "document_types",
    "storage_paths",
    "custom_fields",
    "custom_field_select_created",
    "error_not_found",
    "documents",
    "document",
    "document_updated",
    "documents_search_text",
    "documents_more_like",
    "error_invalid_custom_field_query",
    "note_added",
    "document_metadata",
    "document_suggestions",
    "document_history",
    "tasks_success",
    "task_failure",
    "error_unsupported_file_type",
    "tasks_acknowledged",
]
# Documents uploaded once (looked up by title): title -> (text lines, created, tag names, correspondent).
DOCUMENTS = {
    "Invoice 2026-001": (["ACME Corp", "Invoice 2026-001", "Total 120.00 EUR"], "2026-10-01", ["Finance"], "ACME Corp"),
    "Contract renewal": (["Dear customer", "Your electricity contract renewal", "Kind regards"], "2026-09-15", [], None),
    "Invoice 2026-002": (["ACME Corp", "Invoice 2026-002", "Total 80.00 EUR"], "2026-11-02", ["Finance", "Tax"], "ACME Corp"),
}
# Labels the capture script keeps on the server (created once, then patched back to these values).
LABELS = {
    "tags": [
        {"name": "Finance", "color": "#a6cee3", "is_inbox_tag": False, "matching_algorithm": 1, "match": "invoice bill"},
        {"name": "Tax", "parent": "Finance"},
        {"name": "Inbox", "is_inbox_tag": True, "color": "#ff0000"},
    ],
    "correspondents": [{"name": "ACME Corp", "matching_algorithm": 6}],
    "document_types": [{"name": "Invoice", "matching_algorithm": 0}],
    "storage_paths": [{"name": "By year", "path": "{{ created_year }}/{{ title }}"}],
    "custom_fields": [
        {"name": "Due date", "data_type": "date"},
        {"name": "Amount", "data_type": "monetary", "extra_data": {"default_currency": "EUR"}},
        {"name": "Priority", "data_type": "select", "extra_data": {"select_options": [{"label": "High"}, {"label": "Low"}]}},
    ],
}
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


def ensure_labels(base, token):
    """Creates the LABELS once; later runs patch them back so IDs and fixtures stay stable."""
    ids = {}
    for endpoint, items in LABELS.items():
        for item in items:
            payload = dict(item)
            if isinstance(payload.get("parent"), str):
                payload["parent"] = ids[(endpoint, payload["parent"])]
            lookup = json_body(request(base, "GET", f"/api/{endpoint}/?name__iexact={urllib.parse.quote(item['name'])}", token=token),
                               f"looking up {endpoint}")
            if lookup["results"]:
                existing = lookup["results"][0]["id"]
                if payload.pop("data_type", None) == "select":
                    # The server requires the options on every update of a select field; send the existing
                    # ones with their IDs (options without IDs would be replaced by new ones).
                    payload["extra_data"] = lookup["results"][0]["extra_data"]
                status, _, body = request(base, "PATCH", f"/api/{endpoint}/{existing}/", body=payload, token=token)
                expected, object_id = 200, existing
            else:
                status, _, body = request(base, "POST", f"/api/{endpoint}/", body=payload, token=token)
                expected, object_id = 201, None
            if status != expected:
                raise SystemExit(f"saving {endpoint} {item['name']} failed: HTTP {status} {body[:300]!r}")
            ids[(endpoint, item["name"])] = object_id or json.loads(body)["id"]
    return ids


def make_pdf(lines):
    """A minimal one-page PDF with the given text lines (Helvetica), so Paperless can extract the text."""
    text = " ".join(f"({line.replace('(', '').replace(')', '')}) Tj T*" for line in lines)
    content = f"BT /F1 14 Tf 72 720 Td 18 TL {text} ET"
    objects = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
        "/Resources << /Font << /F1 5 0 R >> >> >>",
        f"<< /Length {len(content)} >>\nstream\n{content}\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out, offsets = "%PDF-1.4\n", []
    for number, obj in enumerate(objects, 1):
        offsets.append(len(out.encode()))
        out += f"{number} 0 obj\n{obj}\nendobj\n"
    xref = len(out.encode())
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n" + "".join(f"{o:010d} 00000 n \n" for o in offsets)
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
    return out.encode()


def post_document(base, token, file_name, data, fields):
    """POST /api/documents/post_document/ (multipart); returns (status, headers, body)."""
    boundary = "----paperless-dev-capture"
    parts = []
    for name, value in fields:
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="document"; filename="{file_name}"\r\n'
                 "Content-Type: application/octet-stream\r\n\r\n".encode() + data + b"\r\n")
    body = b"".join(parts) + f"--{boundary}--\r\n".encode()
    req = urllib.request.Request(base + "/api/documents/post_document/", data=body, method="POST", headers={
        "Accept": "application/json; version=10", "Authorization": f"Token {token}",
        "Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with _OPENER.open(req, timeout=60) as resp:
            return resp.status, dict(resp.headers.items()), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers.items()), e.read()


def wait_for_task(base, token, task_id):
    for _ in range(120):
        tasks = json_body(request(base, "GET", f"/api/tasks/?task_id={task_id}", token=token), "task status")
        results = tasks.get("results", tasks) if isinstance(tasks, dict) else tasks
        if results and results[0]["status"].lower() in ("success", "failure", "revoked"):
            return results[0]
        time.sleep(1)
    raise SystemExit(f"task {task_id} did not finish")


def upload(base, token, title, pdf, fields):
    """POST /api/documents/post_document/ (multipart) and wait for the consume task; returns the document ID."""
    boundary = "----paperless-dev-capture"
    parts = []
    for name, value in [("title", title)] + fields:
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="document"; filename="{title}.pdf"\r\n'
                 "Content-Type: application/pdf\r\n\r\n".encode() + pdf + b"\r\n")
    body = b"".join(parts) + f"--{boundary}--\r\n".encode()
    req = urllib.request.Request(base + "/api/documents/post_document/", data=body, method="POST", headers={
        "Accept": "application/json; version=10", "Authorization": f"Token {token}",
        "Content-Type": f"multipart/form-data; boundary={boundary}"})
    with _OPENER.open(req, timeout=60) as resp:
        task_id = json.loads(resp.read())
    for _ in range(120):
        tasks = json_body(request(base, "GET", f"/api/tasks/?task_id={task_id}", token=token), "task status")
        results = tasks.get("results", tasks) if isinstance(tasks, dict) else tasks
        if results and results[0]["status"].lower() in ("success", "failure"):
            if results[0]["status"].lower() != "success":
                raise SystemExit(f"consuming {title} failed: {results[0]}")
            return results[0]["result_data"]["document_id"]
        time.sleep(1)
    raise SystemExit(f"consuming {title} timed out")


def ensure_documents(base, token, label_ids):
    ids = {}
    for title, (lines, created, tags, correspondent) in DOCUMENTS.items():
        found = json_body(request(base, "GET", f"/api/documents/?title__iexact={urllib.parse.quote(title)}&fields=id",
                                  token=token), "looking up documents")["results"]
        if found:
            ids[title] = found[0]["id"]
            continue
        fields = [("created", created)] + [("tags", label_ids[("tags", t)]) for t in tags]
        if correspondent:
            fields.append(("correspondent", label_ids[("correspondents", correspondent)]))
        ids[title] = upload(base, token, title, make_pdf(lines), fields)
    # Field values are (re)set every run so the captures stay stable.
    request(base, "PATCH", f"/api/documents/{ids['Invoice 2026-001']}/", token=token, body={
        "archive_serial_number": 1,
        "custom_fields": [{"field": label_ids[("custom_fields", "Due date")], "value": "2026-11-30"},
                          {"field": label_ids[("custom_fields", "Amount")], "value": "EUR120.00"}]})
    return ids


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
        if name == "document_history":
            body = body[:5]  # the log grows on every run; keep the fixture small
        if name == "task_failure":
            for task in body["results"]:
                task["result_data"]["traceback"] = "<omitted>"  # long, and specific to the server install
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

    # Labels (as admin, who owns them)
    ids = ensure_labels(base, admin_token)
    for endpoint in ["tags", "correspondents", "document_types", "storage_paths", "custom_fields"]:
        extra = "&last_correspondence=true" if endpoint == "correspondents" else ""
        record(out, endpoint, 200,
               request(base, "GET", f"/api/{endpoint}/?page=1&page_size=25{extra}", token=admin_token))
    record(out, "tag_child", 200, request(base, "GET", f"/api/tags/{ids[('tags', 'Tax')]}/", token=admin_token))
    # Leftovers of an interrupted earlier run would make the creates below fail as duplicates.
    for endpoint, name in [("tags", "Temporary"), ("tags", "Temporary renamed"), ("custom_fields", "Temporary select")]:
        found = json_body(request(base, "GET", f"/api/{endpoint}/?name__iexact={urllib.parse.quote(name)}",
                                  token=admin_token), "looking up leftovers")["results"]
        for item in found:
            request(base, "DELETE", f"/api/{endpoint}/{item['id']}/", token=admin_token)
    created = request(base, "POST", "/api/tags/", token=admin_token,
                      body={"name": "Temporary", "color": "#123456", "parent": ids[("tags", "Finance")]})
    record(out, "tag_created", 201, created)
    temporary = json.loads(created[2])["id"]
    record(out, "tag_updated", 200, request(base, "PATCH", f"/api/tags/{temporary}/", token=admin_token,
                                            body={"name": "Temporary renamed", "parent": None}))
    record(out, "tag_deleted", 204, request(base, "DELETE", f"/api/tags/{temporary}/", token=admin_token))
    record(out, "error_not_found", 404, request(base, "GET", f"/api/tags/{temporary}/", token=admin_token))
    record(out, "error_duplicate_name", 400, request(base, "POST", "/api/tags/", token=admin_token,
                                                     body={"name": "Finance"}))
    select = request(base, "POST", "/api/custom_fields/", token=admin_token, body={
        "name": "Temporary select", "data_type": "select",
        "extra_data": {"select_options": [{"label": "High"}, {"label": "Low"}]}})
    record(out, "custom_field_select_created", 201, select)
    request(base, "DELETE", f"/api/custom_fields/{json.loads(select[2])['id']}/", token=admin_token)

    # Documents (as admin)
    docs = ensure_documents(base, admin_token, ids)
    invoice = docs["Invoice 2026-001"]
    record(out, "documents", 200, request(base, "GET", "/api/documents/?page=1&page_size=25&truncate_content=true",
                                          token=admin_token))
    record(out, "document", 200, request(base, "GET", f"/api/documents/{invoice}/", token=admin_token))
    record(out, "document_updated", 200, request(base, "PATCH", f"/api/documents/{invoice}/", token=admin_token,
                                                 body={"title": "Invoice 2026-001 (paid)"}))
    request(base, "PATCH", f"/api/documents/{invoice}/", token=admin_token, body={"title": "Invoice 2026-001"})
    record(out, "documents_search_text", 200, request(base, "GET", "/api/documents/?text=invoice", token=admin_token))
    record(out, "documents_more_like", 200,
           request(base, "GET", f"/api/documents/?more_like_id={invoice}", token=admin_token))
    # Notes are added and removed in the same run so that documents keep none between runs.
    added = request(base, "POST", f"/api/documents/{invoice}/notes/", token=admin_token, body={"note": "Paid on 2026-11-05"})
    record(out, "note_added", 200, added)
    for note in json.loads(added[2]):
        request(base, "DELETE", f"/api/documents/{invoice}/notes/?id={note['id']}", token=admin_token)
    record(out, "document_metadata", 200, request(base, "GET", f"/api/documents/{invoice}/metadata/", token=admin_token))
    record(out, "document_suggestions", 200,
           request(base, "GET", f"/api/documents/{invoice}/suggestions/", token=admin_token))
    record(out, "document_history", 200, request(base, "GET", f"/api/documents/{invoice}/history/", token=admin_token))
    record(out, "next_asn", 200, request(base, "GET", "/api/documents/next_asn/", token=admin_token))
    # Tasks. A corrupt PDF fails during consumption without creating a document.
    status, _, body = post_document(base, admin_token, "corrupt.pdf", b"%PDF-1.4\n" + bytes(range(256)) * 16, [])
    if status != 200:
        raise SystemExit(f"uploading the corrupt PDF failed: HTTP {status} {body[:200]!r}")
    failed = wait_for_task(base, admin_token, json.loads(body))
    if failed["status"].lower() != "failure":
        raise SystemExit(f"expected the corrupt PDF to fail, got {failed['status']}")
    record(out, "task_failure", 200, request(base, "GET", f"/api/tasks/?task_id={failed['task_id']}", token=admin_token))
    record(out, "tasks_success", 200,
           request(base, "GET", "/api/tasks/?status=success&task_type=consume_file&page_size=2", token=admin_token))
    record(out, "error_unsupported_file_type", 400,
           post_document(base, admin_token, "random.bin", bytes(range(256)) * 8, []))
    record(out, "tasks_acknowledged", 200, request(base, "POST", "/api/tasks/acknowledge/", token=admin_token,
                                                   body={"tasks": [failed["id"]]}))
    record(out, "error_invalid_custom_field_query", 400,
           request(base, "GET", "/api/documents/?custom_field_query=%5B%22nope%22%5D", token=admin_token))
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
