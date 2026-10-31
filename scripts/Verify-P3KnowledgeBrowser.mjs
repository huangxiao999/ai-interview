// Real Chrome + actual Vue/HTTP/session; only response delivery is fault-controlled.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

const base = process.env.P3_FRONTEND_BASE
assert.ok(base && process.env.P3_BROWSER_USER && process.env.P3_BROWSER_PASSWORD)
const profile = fs.mkdtempSync(path.resolve('../.cache/tmp/p3-chrome-'))
const browser = spawn('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', [
  '--headless=new', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0',
  `--user-data-dir=${profile}`, 'about:blank',
], { stdio: 'ignore' })
let socket
try {
  let endpoint
  for (let i = 0; i < 100; i++) {
    const active = path.join(profile, 'DevToolsActivePort')
    if (fs.existsSync(active)) {
      const [port, suffix] = fs.readFileSync(active, 'utf8').trim().split('\n')
      endpoint = `ws://127.0.0.1:${port}${suffix}`; break
    }
    await delay(100)
  }
  assert.ok(endpoint, 'Chrome debugging unavailable')
  socket = new WebSocket(endpoint)
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }) })
  let id = 0
  const pending = new Map()
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    const p = pending.get(message.id)
    if (!p) return
    pending.delete(message.id)
    if (message.error) p.reject(new Error(message.error.message)); else p.resolve(message.result)
  })
  function send(method, params = {}, sessionId) {
    return new Promise((resolve, reject) => {
      const requestId = ++id; pending.set(requestId, { resolve, reject })
      socket.send(JSON.stringify({ id: requestId, method, params, ...(sessionId ? { sessionId } : {}) }))
    })
  }
  const { targetId } = await send('Target.createTarget', { url: `${base}/login` })
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true })
  await send('Page.enable', {}, sessionId)
  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false }, sessionId)
  async function evaluate(expression) {
    const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true, userGesture: true }, sessionId)
    if (result.exceptionDetails) throw new Error('Browser evaluation failed')
    return result.result.value
  }
  async function wait(expression, label) {
    for (let i = 0; i < 150; i++) {
      try { if (await evaluate(expression)) return } catch { /* navigation replaces context */ }
      await delay(100)
    }
    throw new Error(`Timed out: ${label}`)
  }
  async function fill(selector, value) {
    await evaluate(`(() => { const e=document.querySelector(${JSON.stringify(selector)}); e.value=${JSON.stringify(value)}; e.dispatchEvent(new Event('input',{bubbles:true})); })()`)
  }
  async function click(label) {
    await evaluate(`Array.from(document.querySelectorAll('button')).find(e=>e.textContent.trim()===${JSON.stringify(label)}).click()`)
  }
  await wait(`!!document.querySelector('#login-name')`, 'login page')
  await fill('#login-name', process.env.P3_BROWSER_USER)
  await fill('#login-password', process.env.P3_BROWSER_PASSWORD)
  await evaluate(`document.querySelector('form').requestSubmit()`)
  await wait(`!!document.querySelector('a[href="/app/knowledge"]')`, 'maintenance link')
  await evaluate(`document.querySelector('a[href="/app/knowledge"]').click()`)
  await wait(`!!document.querySelector('#knowledge-title')`, 'knowledge page')
  const body = 'Synthetic <img src=x onerror="window.knowledgeInjected=true"> original'
  for (const [selector, value] of [
    ['#knowledge-title','Browser synthetic source'], ['#knowledge-body',body],
    ['#knowledge-url','https://example.invalid/synthetic'], ['#knowledge-anchor','section 1'],
    ['#knowledge-applicability','Java 21 synthetic scope'], ['#knowledge-usage','Synthetic fixture'],
    ['#knowledge-verification','Workflow fixture only'], ['#knowledge-date','2026-10-31'],
  ]) await fill(selector,value)
  await evaluate(`(() => {
    const nativeFetch=window.fetch.bind(window); window.knowledgeNativeFetch=nativeFetch;
    window.failKnowledgeBody=true; window.knowledgePosts=0;
    window.fetch=async (input,init)=>{
      const response=await nativeFetch(input,init);
      const pathname=new URL(input instanceof Request?input.url:input,location.href).pathname;
      if(pathname.startsWith('/api/maintenance/knowledge') && init?.method==='POST') {
        window.knowledgePosts++;
        if(window.failKnowledgeBody) { window.failKnowledgeBody=false; response.json=async()=>{throw new TypeError('synthetic lost response')}; }
      }
      return response;
    };
  })()`)
  await evaluate(`document.querySelector('form').requestSubmit()`)
  await wait(`document.body.textContent.includes('结果未知')`, 'unknown submission')
  assert.equal(await evaluate(`document.querySelector('#knowledge-body').matches(':disabled')`), true)
  await evaluate(`window.dispatchEvent(new Event('focus'))`)
  await wait(`document.querySelector('#knowledge-title') && Array.from(document.querySelectorAll('button')).some(e=>e.textContent.trim()==='核实原操作' && !e.disabled)`, 'same-account identity recheck retains receipt')
  await click('核实原操作')
  await wait(`document.body.textContent.includes('已核实原操作') && !!document.querySelector('#knowledge-original')`, 'original result')
  assert.equal(await evaluate(`window.knowledgePosts`),1)
  assert.equal(await evaluate(`document.querySelector('#knowledge-original').textContent`),body)
  assert.equal(await evaluate(`window.knowledgeInjected===true || !!document.querySelector('#knowledge-original img')`),false)
  await fill('#knowledge-review-reason','Synthetic reviewed source')
  await evaluate(`window.failKnowledgeBody=true`)
  await click('确认已核实并批准该版本')
  await wait(`document.body.textContent.includes('结果未知')`, 'unknown review')
  await send('Page.reload', {}, sessionId)
  await wait(`Array.from(document.querySelectorAll('button')).some(e=>e.textContent.trim()==='核实原操作')`, 'minimal operation receipt after reload')
  assert.equal(await evaluate(`document.querySelector('#knowledge-body').value`), '')
  await click('核实原操作')
  await wait(`document.querySelector('section[aria-label="固定版本审核"] h2')?.textContent.includes('APPROVED') && document.querySelector('#knowledge-original')?.textContent===${JSON.stringify(body)} && !document.querySelector('#knowledge-body').matches(':disabled')`, 'approved fact')
  await fill('#knowledge-body','Synthetic V2')
  await evaluate(`document.querySelector('form').requestSubmit()`)
  await wait(`!!document.querySelector('#knowledge-review-reason') && document.querySelector('#knowledge-original')?.textContent==='Synthetic V2'`, 'new V2')
  await fill('#knowledge-review-reason','Synthetic rejected source')
  await click('拒绝该版本')
  await wait(`document.querySelector('section[aria-label="固定版本审核"] h2')?.textContent.includes('REJECTED') && !document.querySelector('#knowledge-body').matches(':disabled')`, 'rejected V2')
  await evaluate(`Array.from(document.querySelectorAll('[data-knowledge-version]')).find(e=>e.textContent.includes('V1')).click()`)
  await wait(`document.querySelector('#knowledge-original')?.textContent===${JSON.stringify(body)}`, 'immutable V1')
  // Capture only synthetic maintenance UI, never credentials or private user content.
  const screenshot=await send('Page.captureScreenshot',{ format:'png' },sessionId)
  const database=process.env.P3_TEST_DATABASE
  assert.match(database,/^p3dt01_verify_[a-f0-9]{16}$/)
  fs.writeFileSync(path.resolve(`../.cache/${database}-knowledge.png`),Buffer.from(screenshot.data,'base64'))
  await evaluate(`(() => { const f=window.fetch.bind(window); window.knowledgeNativeFetch=f;
    window.holdKnowledgeList=true;
    window.fetch=async(input,init)=>{ const r=await f(input,init);
      if(window.holdKnowledgeList && String(input).endsWith('/api/maintenance/knowledge/documents') && !init?.method) {
        window.holdKnowledgeList=false; return new Promise(resolve=>{window.releaseKnowledgeList=()=>resolve(r)});
      } return r; };
  })()`)
  await click('重新加载版本列表')
  await wait(`typeof window.releaseKnowledgeList==='function'`, 'held old response')
  await evaluate(`(async()=>{
    const f=window.knowledgeNativeFetch;
    let csrf=await(await f('/api/auth/csrf')).json();
    await f('/api/auth/logout',{method:'POST',headers:{[csrf.headerName]:csrf.token}});
    csrf=await(await f('/api/auth/csrf')).json();
    await f('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',[csrf.headerName]:csrf.token},
      body:new URLSearchParams({username:${JSON.stringify(process.env.P3_OTHER_USER)},password:${JSON.stringify(process.env.P3_OTHER_PASSWORD)}})});
    window.dispatchEvent(new Event('focus'));
  })()`)
  await wait(`!document.querySelector('#knowledge-title')`, 'role switch clears knowledge')
  await evaluate(`window.releaseKnowledgeList()`)
  await delay(150)
  assert.equal(await evaluate(`document.body.textContent.includes('Browser synthetic source')`),false)
  process.stdout.write('Real Chrome knowledge submit/review recovery, immutable history, safe text and identity change passed.\n')
} finally {
  socket?.close(); browser.kill()
  // Retain the isolated synthetic browser profile for this verification; no recursive removal.
}
