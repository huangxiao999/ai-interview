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
  await wait(`!!document.querySelector('a[href="/app/knowledge-index"]')`, 'maintenance link')
  await evaluate(`document.querySelector('a[href="/app/knowledge-index"]').click()`)
  await wait(`!!document.querySelector('#index-version')`, 'knowledge page')

  async function selectVersion() {
    await wait(`!!Array.from(document.querySelector('#index-version').options).find(o=>o.value===${JSON.stringify(process.env.P3_INDEX_VERSION)})`, 'approved fixture option')
    await evaluate(`(() => {const e=document.querySelector('#index-version');e.value=${JSON.stringify(process.env.P3_INDEX_VERSION)};e.dispatchEvent(new Event('change',{bubbles:true}));})()`)
    await delay(150)
  }
  await selectVersion()
  await evaluate(`(() => {
    const f=window.fetch.bind(window);window.indexPosts=0;
    window.fetch=async(input,init)=>{
      if(String(input)==='/api/auth/me') throw new TypeError('synthetic preflight disconnect');
      if(init?.method==='POST') window.indexPosts++;
      return f(input,init);
    };
  })()`)
  await click('提交或核实同绑定构建')
  await wait(`!Array.from(document.querySelectorAll('button')).find(e=>e.textContent.trim()==='提交或核实同绑定构建').disabled`, 'preflight failure permits retry')
  assert.equal(await evaluate(`sessionStorage.getItem('knowledge-index-original-operation')`),null)
  assert.equal(await evaluate(`window.indexPosts`),0)
  await send('Page.reload',{},sessionId)
  await wait(`!!document.querySelector('#index-version')`, 'refresh after unsent operation')
  await selectVersion()
  await evaluate(`(() => {
    const f=window.fetch.bind(window);window.indexPosts=0;window.failIndexBody=true;
    window.fetch=async(input,init)=>{
      const r=await f(input,init);
      if(init?.method==='POST' && String(input).includes('/index-builds')) {
        window.indexPosts++;
        if(window.failIndexBody) {window.failIndexBody=false;r.json=async()=>{throw new TypeError('synthetic response loss')};}
      }
      return r;
    };
  })()`)
  await click('提交或核实同绑定构建')
  await wait(`document.body.textContent.includes('结果未知')`, 'local result unknown')
  assert.equal(await evaluate(`window.indexPosts`),1)
  const receipt=await evaluate(`JSON.parse(sessionStorage.getItem('knowledge-index-original-operation'))`)
  assert.deepEqual(Object.keys(receipt).sort(),['accountId','id'])
  await send('Page.reload',{},sessionId)
  await wait(`Array.from(document.querySelectorAll('button')).some(e=>e.textContent.trim()==='核实原索引操作')`, 'minimal receipt after refresh')
  await click('核实原索引操作')
  await wait(`document.body.textContent.includes('WAITING_MANUAL')`, 'external unknown waiting')
  assert.equal(await evaluate(`document.querySelector('#index-cost-ack').checked`),false)
  await click('恢复原批次')
  await wait(`document.body.textContent.includes('请明确确认重复费用风险')`, 'acknowledgment required')
  await evaluate(`document.querySelector('#index-cost-ack').click()`)
  await click('恢复原批次')
  await wait(`!Array.from(document.querySelectorAll('button')).find(e=>e.textContent.trim()==='核实原构建状态').disabled`, 'resume accepted')
  for (let i=0;i<50;i++) {
    await click('核实原构建状态')
    await wait(`!Array.from(document.querySelectorAll('button')).find(e=>e.textContent.trim()==='核实原构建状态').disabled`, 'state query finished')
    if(await evaluate(`document.querySelector('h2').textContent.includes('SUCCEEDED')`)) break
    await delay(200)
  }
  await wait(`document.querySelector('h2').textContent.includes('SUCCEEDED')`, 'complete original build')
  assert.equal(await evaluate(`sessionStorage.getItem('knowledge-index-original-operation')`),null)
  const screenshot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:true},sessionId)
  fs.writeFileSync(path.resolve('../.cache',`${process.env.P3_INDEX_TEST_DATABASE}-index.png`),Buffer.from(screenshot.data,'base64'))
  process.stdout.write('Real Chrome index preflight rejection, response loss, refresh query and explicit manual retry passed.\n')
} finally {
  socket?.close();browser.kill()
}

