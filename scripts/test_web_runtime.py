"""Headless regression for visibility, push state and optional camera previews.

Run against an isolated test node; PHONEBRIDGE_TOKEN must be a fixture token.
"""
import argparse
import os
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright

parser = argparse.ArgumentParser()
parser.add_argument("--url", default="http://127.0.0.1:19507")
args = parser.parse_args()
token = os.environ["PHONEBRIDGE_TOKEN"]
with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True)
    try:
        context = browser.new_context(viewport={"width": 390, "height": 844})
        context.add_cookies([{"name": "phonebridge_token", "value": token, "url": args.url}])
        page = context.new_page()
        errors, requests = [], []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("request", lambda request: requests.append(urlparse(request.url).path))
        page.goto(args.url)
        page.wait_for_load_state("networkidle")
        page.wait_for_selector(".mote-svg")
        page.wait_for_function("webRefresh.state.pushOnline === true")
        assert page.evaluate("() => { const active = pushSocket; connectWebPush(); return pushSocket === active; }"), \
            "repeated page-show callbacks must not create a second WebSocket"
        assert "/frame" not in requests, "closed drawer must not request frames"
        count = requests.count("/api/motes")
        page.wait_for_timeout(5500)
        assert requests.count("/api/motes") == count, "active WS must replace Mote polling"
        page.locator("#openDrawer").click()
        page.wait_for_selector("#commandDrawer.open")
        page.wait_for_timeout(500)
        drawer_reads = ("/api/diagnostics", "/api/workspace/timeline", "/api/routines", "/api/goals")
        drawer_counts = {path: requests.count(path) for path in drawer_reads}
        page.wait_for_timeout(5500)
        assert {path: requests.count(path) for path in drawer_reads} == drawer_counts, \
            "active WS must replace drawer polling"
        assert "/frame" not in requests, "preview requires explicit activation"
        page.get_by_role("button", name="显示实时画面", exact=True).click()
        page.wait_for_timeout(500)
        assert requests.count("/frame") > 0
        page.locator("#closeDrawer").click()
        page.wait_for_timeout(500)
        count = requests.count("/frame")
        page.wait_for_timeout(650)
        assert requests.count("/frame") == count, "closed drawer must stop frames"
        page.evaluate("""() => {
            window.testHidden = true;
            Object.defineProperty(document, 'hidden', {get: () => window.testHidden, configurable: true});
            document.dispatchEvent(new Event('visibilitychange'));
        }""")
        page.wait_for_timeout(400)
        count = len(requests)
        page.wait_for_timeout(5500)
        assert len(requests) == count, "hidden document must stop read requests"
        page.evaluate("window.testHidden=false;document.dispatchEvent(new Event('visibilitychange'))")
        page.wait_for_function("webRefresh.state.pushOnline === true")
        assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
        assert not errors, errors
        print("Web runtime: push, visibility, optional frames, reconnect and narrow layout passed")
    finally:
        browser.close()
