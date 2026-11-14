#!/usr/bin/env python3
"""Captures the README screenshots from a fresh local instance in sandbox mode (docker compose up).

Requires: pip install playwright && playwright install chromium   (or pass --chrome to use an installed Chrome)
Usage:    python scripts/capture_screenshots.py [--base-url http://localhost:8091] [--chrome] [--only checkout,events]
Shots:    shop, checkout, jsapi, events, refund, reconciliation, swagger, tests (needs `mvn verify` first)
"""
import argparse
import base64
import json
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "screenshots"
ADMIN = ("admin", "change-me")
BEIJING = timezone(timedelta(hours=8))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.auth = "Basic " + base64.b64encode(f"{ADMIN[0]}:{ADMIN[1]}".encode()).decode()
        self.opener = urllib.request.build_opener(NoRedirect)

    def call(self, method, path, body=None, headers=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("Authorization", self.auth)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read()
                return r.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                return e.code, json.loads(raw) if raw else None
            except ValueError:
                return e.code, raw.decode(errors="replace")

    def form(self, path, fields=None):
        """POST an HTML form; returns the redirect Location."""
        data = urllib.parse.urlencode(fields or {}).encode()
        req = urllib.request.Request(self.base + path, data=data, method="POST")
        req.add_header("Authorization", self.auth)
        req.add_header("Content-Type", "application/x-www-form-urlencoded")
        try:
            self.opener.open(req, timeout=60)
        except urllib.error.HTTPError as e:
            if e.code not in (302, 303):
                raise
            return e.headers.get("Location")
        return None

    def order(self, channel, amount, desc, **extra):
        body = {"merchantOrderNo": "SHOP-" + uuid.uuid4().hex[:8].upper(), "channel": channel, "amount": amount,
                "description": desc, **extra}
        s, o = self.call("POST", "/api/orders", body, {"Idempotency-Key": uuid.uuid4().hex})
        if s != 201:
            raise SystemExit(f"create failed {s}: {o}")
        return o

    def wait_status(self, oid, status, timeout=15):
        end = time.time() + timeout
        while time.time() < end:
            _, o = self.call("GET", f"/api/orders/{oid}")
            if o["status"] == status:
                return o
            time.sleep(0.3)
        raise SystemExit(f"order {oid} never reached {status}")

    def pay(self, oid):
        req = urllib.request.Request(self.base + f"/pay/{oid}/simulate", data=b"", method="POST")
        try:
            self.opener.open(req, timeout=60)
        except urllib.error.HTTPError as e:
            if e.code not in (302, 303):
                raise


def seed(api):
    d = {}
    # Paid by WeChat Native QR, then two partial refunds (PROCESSING -> REFUND.SUCCESS callback)
    a = api.order("WECHAT_NATIVE", "88.00", "Matcha starter kit 抹茶入门套装")
    api.pay(a["id"]); api.wait_status(a["id"], "PAID")
    api.call("POST", f"/api/orders/{a['id']}/refunds", {"amount": "20.00", "reason": "Whisk arrived damaged"})
    time.sleep(2.5)
    api.call("POST", f"/api/orders/{a['id']}/refunds", {"amount": "8.00", "reason": "Shipping fee refund"})
    time.sleep(2.5)
    d["refund_order"] = a
    # Alipay QR, paid and fully refunded (synchronous)
    b = api.order("ALIPAY_QR", "66.60", "Hojicha tin 焙茶罐")
    api.pay(b["id"]); api.wait_status(b["id"], "PAID")
    api.call("POST", f"/api/orders/{b['id']}/refunds", {"amount": "66.60", "reason": "Customer cancelled"})
    # Mini-program (JSAPI) and Alipay PC page payments
    c = api.order("WECHAT_JSAPI", "128.00", "Tea ceremony set 茶道套装", payerOpenid="oUpF8uMuAJO_M2pxb1Q9zNjWeS6o")
    api.pay(c["id"]); api.wait_status(c["id"], "PAID")
    d["jsapi"] = api.order("WECHAT_JSAPI", "39.00", "Sencha sampler 煎茶试饮装", payerOpenid="oUpF8uMuAJO_M2pxb1Q9zNjWeS6o")
    e = api.order("ALIPAY_PAGE", "199.00", "Cast-iron kettle 铁壶")
    # The shopper's browser follows the signed gateway URL to the (sandbox) cashier and pays there.
    try:
        api.opener.open(e["payment"]["url"], timeout=60)
    except urllib.error.HTTPError as err:
        if err.code not in (302, 303):
            raise
    try:
        api.opener.open(urllib.request.Request(f"{api.base}/sandbox/cashier/ALIPAY/{e['outTradeNo']}",
                                               data=b"", method="POST"), timeout=60)
    except urllib.error.HTTPError as err:
        if err.code not in (302, 303):
            raise
    api.wait_status(e["id"], "PAID")
    # A forged callback (wrong key) and a lost one
    forged = urllib.request.Request(api.base + "/notify/wechat", method="POST", data=json.dumps({
        "id": str(uuid.uuid4()), "event_type": "TRANSACTION.SUCCESS", "resource_type": "encrypt-resource",
        "resource": {"algorithm": "AEAD_AES_256_GCM", "ciphertext": "AAAA", "nonce": "abcdefabcdef", "associated_data": "transaction"}}).encode(),
        headers={"Content-Type": "application/json", "Wechatpay-Serial": "PUB_KEY_ID_0114FAKE", "Wechatpay-Nonce": "x" * 32,
                 "Wechatpay-Timestamp": str(int(time.time())), "Wechatpay-Signature": base64.b64encode(b"forged" * 40).decode(),
                 "Wechatpay-Signature-Type": "WECHATPAY2-SHA256-RSA2048"})
    try:
        urllib.request.urlopen(forged, timeout=30)
    except urllib.error.HTTPError:
        pass
    api.form("/admin/sandbox/drop-notification")
    lost = api.order("WECHAT_NATIVE", "45.00", "Bamboo whisk 竹茶筅")
    api.pay(lost["id"])
    # Provider-side surprises for reconciliation
    api.form("/admin/sandbox/orphan-payment", {"provider": "WECHAT"})
    f = api.order("WECHAT_H5", "30.00", "Tea towel 茶巾", clientIp="203.0.113.10")
    api.pay(f["id"]); api.wait_status(f["id"], "PAID")
    api.form("/admin/sandbox/tamper-amount", {"provider": "WECHAT"})
    time.sleep(1.5)
    # The shopper still looking at the QR code
    d["pending"] = api.order("WECHAT_NATIVE", "88.00", "Matcha starter kit 抹茶入门套装")
    d["pending_ali"] = api.order("ALIPAY_QR", "58.00", "Genmaicha 玄米茶")
    today = datetime.now(BEIJING).date().isoformat()
    d["recon"] = api.form("/admin/reconciliations", {"provider": "WECHAT", "date": today})
    api.form("/admin/reconciliations", {"provider": "ALIPAY", "date": today})
    return d


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8091")
    parser.add_argument("--chrome", action="store_true", help="use the installed Google Chrome")
    parser.add_argument("--only", default="", help="comma-separated subset of the shots")
    args = parser.parse_args()
    only = set(filter(None, args.only.split(",")))
    want = lambda n: not only or n in only  # noqa: E731
    OUT.mkdir(parents=True, exist_ok=True)
    api = Api(args.base_url)
    host = args.base_url.rstrip("/")
    data = seed(api) if any(want(n) for n in ("checkout", "jsapi", "events", "refund", "reconciliation")) else None

    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome" if args.chrome else None)
        ctx = browser.new_context(viewport={"width": 1280, "height": 820}, device_scale_factor=1,
                                  http_credentials={"username": ADMIN[0], "password": ADMIN[1]})
        page = ctx.new_page()

        def shot(url, name, full=True, height=None):
            if height:
                page.set_viewport_size({"width": 1280, "height": height})
            page.goto(url)
            page.wait_for_timeout(500)
            page.screenshot(path=str(OUT / name), full_page=full)
            page.set_viewport_size({"width": 1280, "height": 820})

        if want("shop"):
            shot(f"{host}/", "shop.png", full=False, height=640)
        if want("checkout"):
            shot(f"{host}/pay/{data['pending']['id']}", "checkout.png", full=False, height=720)
            shot(f"{host}/pay/{data['pending_ali']['id']}", "checkout-alipay.png", full=False, height=720)
        if want("jsapi"):
            shot(f"{host}/pay/{data['jsapi']['id']}", "jsapi.png", full=False, height=720)
        if want("events"):
            shot(f"{host}/admin/events", "notifications.png")
        if want("refund"):
            shot(f"{host}/admin/orders/{data['refund_order']['id']}", "refund.png")
        if want("reconciliation"):
            loc = data["recon"]
            shot(loc if loc.startswith("http") else host + loc, "reconciliation.png")
            shot(f"{host}/admin", "orders.png")
        if want("swagger"):
            page.goto(f"{host}/swagger-ui/index.html")
            page.wait_for_selector(".opblock", timeout=30_000)
            page.set_viewport_size({"width": 1280, "height": 1000})
            page.wait_for_timeout(600)
            page.screenshot(path=str(OUT / "swagger.png"), full_page=False)
            page.set_viewport_size({"width": 1280, "height": 820})
        if want("tests"):
            report = Path(tempfile.gettempdir()) / "cnpay-test-report.html"
            rc = subprocess.run([sys.executable, str(ROOT / "scripts" / "test_report.py"), "--html", str(report)])
            if report.exists():
                page.set_viewport_size({"width": 1100, "height": 700})
                page.goto(report.as_uri())
                page.screenshot(path=str(OUT / "tests.png"), full_page=True)
            else:
                print(f"PENDING: tests (no surefire reports, exit {rc.returncode}; run mvn verify first)")
        browser.close()
    print("Saved to", OUT)


if __name__ == "__main__":
    main()
