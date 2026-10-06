#!/usr/bin/env python3
"""End-to-end API demo. Does not follow external redirects or print the API key."""
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
KEY = os.environ.get("APP_API_KEY", "local-demo-key")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


CLIENT = urllib.request.build_opener(NoRedirect())


def request(method, path, payload=None, extra=None, authenticated=True):
    headers = {"Content-Type": "application/json"}
    if authenticated:
        headers["X-API-Key"] = KEY
    headers.update(extra or {})
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        response = CLIENT.open(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        body = json.loads(raw) if raw and "json" in response.headers.get("Content-Type", "") else None
        return response.status, dict(response.headers), body


def main():
    unique = uuid.uuid4().hex[:10]
    payload = {"url": "https://example.com/product?id=42#details", "customAlias": "demo-" + unique}
    idem = {"Idempotency-Key": "demo-" + unique}
    status, _, link = request("POST", "/api/v1/urls", payload, idem)
    assert status == 201, (status, link)
    code = link["code"]
    print("Created:", link["shortUrl"])
    assert request("POST", "/api/v1/urls", payload, idem)[0] == 200
    assert request("HEAD", "/" + code, authenticated=False)[0] == 302
    assert request("GET", f"/api/v1/urls/{code}/analytics")[2]["totalRedirects"] == 0
    status, headers, _ = request("GET", "/" + code, authenticated=False)
    assert status == 302 and headers["Location"] == payload["url"]
    analytics = request("GET", f"/api/v1/urls/{code}/analytics")[2]
    assert analytics["totalRedirects"] == 1
    print("Analytics:", json.dumps(analytics))
    assert request("GET", f"/api/v1/urls/{code}/analytics", authenticated=False)[0] == 401
    assert request("DELETE", f"/api/v1/urls/{code}")[0] == 204
    assert request("GET", "/" + code, authenticated=False)[0] == 410
    assert request("POST", "/api/v1/urls", payload)[0] == 409
    print("PASS: create, replay, HEAD exclusion, redirect target, analytics, auth, disable and alias tombstone")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, urllib.error.URLError) as failure:
        print("FAIL:", failure, file=sys.stderr)
        sys.exit(1)
