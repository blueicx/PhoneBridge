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
        assert page.locator("#stageChatForm").is_visible(), "chat must be directly available on the stage"
        page.evaluate("""() => {
            let browserChatCounter = 0;
            const testChat = new StageChatController({
                getSocket: () => ({readyState: 1, send: value => { window.stageSent = JSON.parse(value); }}),
                createId: () => 'browser-check-' + (++browserChatCounter), onChange: renderStageChat
            });
            testChat.send('舞台流式测试');
            testChat.handle({type:'chat_delta', requestId:window.stageSent.requestId, text:'伙伴回应'});
            testChat.handle({type:'chat_delta', requestId:window.stageSent.requestId, text:'伙伴回应完成', terminal:true});
            testChat.send('失败后保留的旧消息');
            testChat.handle({type:'chat_error', requestId:window.stageSent.requestId, error:'模拟失败'});
            testChat.send('失败后用户输入的新消息');
            testChat.handle({type:'chat_delta', requestId:window.stageSent.requestId, text:'新消息已回复', terminal:true});
            testChat.hydrate([], {lastUserMessage:'上次在准备项目演示。',lastAssistantMessage:'我们决定先完成最小闭环。'});
            renderStageCards({
                tasks:[{id:'task-ui',title:'安全卡片',state:'running',progress:37}],
                approvals:[{id:'approval-ui',taskId:'task-ui',toolId:'read.status',state:'needs_confirmation',args:{secret:'must-not-render'}}],
                attention:[{id:'attention-ui',title:'第三条不能显示',status:'open'}]
            });
            document.getElementById('moteVisual').focus();
            document.getElementById('moteVisual').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));
        }""")
        assert page.locator("#stageChatReply").inner_text() == "新消息已回复"
        assert page.locator("#stageChatRetry").is_visible(), "an earlier failed message must remain explicitly retryable"
        assert "未送达" in page.locator("#stageChatHistoryList").text_content()
        assert page.locator("#stageQuickCards .stage-quick-card").count() == 2
        assert page.locator("#stageQuickCards").inner_text().find("must-not-render") < 0
        assert page.locator("#moteVisual").get_attribute("data-touched") == "true"
        page.locator("#openDrawer").click()
        page.wait_for_selector("#commandDrawer.open")
        assert page.locator("#stageChatResume").is_visible(), "resumed conversation context must appear in the chat drawer"
        assert "上次在准备项目演示" in page.locator("#stageChatResume").inner_text()
        page.locator("#closeDrawer").click()
        page.wait_for_function("!document.querySelector('#commandDrawer').classList.contains('open')")
        page.evaluate("renderStageCards({});renderStageChat({messages:[],status:'idle',error:''})")
        assert page.evaluate("() => { const active = pushSocket; connectWebPush(); return pushSocket === active; }"), \
            "repeated page-show callbacks must not create a second WebSocket"
        created = page.evaluate("""async () => {
            const response = await fetch('/api/tasks', {method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({title:'实时舞台任务',source:'browser-test'})});
            return {status:response.status,body:await response.json()};
        }""")
        assert created["status"] == 201, created
        page.wait_for_function("document.querySelector('#stageQuickCards')?.innerText.includes('实时舞台任务')")
        routine = page.evaluate("""async () => {
            const response = await fetch('/api/routines/focus-timer/events', {method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({eventId:'browser-routine-'+Date.now(),action:'start',occurredAt:new Date().toISOString(),privacyRevision:0})});
            return {status:response.status,body:await response.json()};
        }""")
        assert routine["status"] == 201, routine
        page.wait_for_function("document.querySelector('#stageQuickCards')?.innerText.includes('专注计时')")
        assert page.locator("#stageQuickCards .stage-quick-card").count() == 2
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
