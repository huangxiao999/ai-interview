// Actual Chrome/Vue/Session/production plan API. Faults only delay/drop delivery; no business state replacement.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

const base = process.env.P4_FRONTEND_BASE
assert.ok(base && process.env.P4_BROWSER_USER && process.env.P4_BROWSER_PASSWORD)
const profile = fs.mkdtempSync(path.resolve('../.cache/tmp/p4-chrome-'))
const browser = spawn('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', [
  '--headless=new', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0',
  `--user-data-dir=${profile}`, 'about:blank',
], { stdio: 'ignore' })
let socket
try {
  let endpoint
  for (let i = 0; i < 100; i++) {
    const active = path.join(profile, 'DevToolsActivePort')
    if (fs.existsSync(active)) { const [port, suffix] = fs.readFileSync(active, 'utf8').trim().split('\n'); endpoint = `ws://127.0.0.1:${port}${suffix}`; break }
    await delay(100)
  }
  assert.ok(endpoint, 'Chrome debugging unavailable')
  socket = new WebSocket(endpoint)
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }) })
  let id = 0
  const pending = new Map()
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data); const p = pending.get(message.id)
    if (!p) return
    pending.delete(message.id); clearTimeout(p.timer)
    if (message.error) p.reject(new Error(message.error.message)); else p.resolve(message.result)
  })
  function send(method, params = {}, sessionId) {
    return new Promise((resolve, reject) => {
      const requestId = ++id
      const timer = setTimeout(() => { pending.delete(requestId); reject(new Error(`CDP timeout: ${method}`)) }, 15000)
      pending.set(requestId, { resolve, reject, timer }); socket.send(JSON.stringify({ id: requestId, method, params, ...(sessionId ? { sessionId } : {}) }))
    })
  }
  const { targetId } = await send('Target.createTarget', { url: `${base}/login` })
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true })
  await send('Page.enable', {}, sessionId)
  await send('Emulation.setDeviceMetricsOverride', { width: 1360, height: 1100, deviceScaleFactor: 1, mobile: false }, sessionId)
  async function evaluate(expression, targetSession = sessionId) {
    const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true, userGesture: true }, targetSession)
    if (result.exceptionDetails) throw new Error(`Browser evaluation failed: ${result.exceptionDetails.text}`)
    return result.result.value
  }
  async function wait(expression, label, targetSession = sessionId) {
    for (let i = 0; i < 180; i++) { try { if (await evaluate(expression, targetSession)) return } catch { /* document replacement */ } await delay(100) }
    const diagnostic = await evaluate(`({path:location.pathname,fault:window.planFault,posts:window.planPosts,reviewPosts:window.reviewPosts,receipt:sessionStorage.getItem('interview-coach-plan-receipt-v1'),status:document.querySelector('[role="status"]')?.textContent,body:document.body.textContent,requests:window.planResponses})`).catch(()=>({documentUnavailable:true}))
    throw new Error(`Timed out: ${label}; ${JSON.stringify(diagnostic)}`)
  }
  async function fill(selector, value, targetSession = sessionId) { await evaluate(`(() => { const e=document.querySelector(${JSON.stringify(selector)}); e.value=${JSON.stringify(value)}; e.dispatchEvent(new Event('input',{bubbles:true})); })()`, targetSession) }
  async function click(selector, targetSession = sessionId) { await wait(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});if(!e || e.disabled)return false;e.click();return true})()`, `enabled ${selector} click`, targetSession) }
  async function clickText(text, targetSession = sessionId) { await wait(`(()=>{const e=Array.from(document.querySelectorAll('a,button')).find(e=>e.textContent.trim()===${JSON.stringify(text)});if(!e || e.disabled)return false;e.click();return true})()`, `enabled ${text}`, targetSession) }
  async function ready() { await wait(`!!document.querySelector('#plan-confirm') && document.querySelector('#plan-jd-version').options.length>0 && document.querySelector('#plan-knowledge').options.length>0`, 'fixed materials and knowledge options') }
  async function generateClick() { await ready(); await wait(`(()=>{const c=document.querySelector('#plan-confirm'),b=document.querySelector('#plan-generate');if(!c || !b)return false;if(!c.checked)c.click();if(b.disabled)return false;b.click();return true})()`, 'explicit generate submitted') }
  await wait(`!!document.querySelector('#login-name')`, 'login')
  await fill('#login-name', process.env.P4_BROWSER_USER); await fill('#login-password', process.env.P4_BROWSER_PASSWORD)
  await evaluate(`document.querySelector('form').requestSubmit()`); await wait(`location.pathname==='/app'`, 'authenticated workspace')
  await send('Page.navigate', { url: `${base}/app/plans?resumeId=${process.env.P4_BROWSER_ROOT}&resumeVersionId=${process.env.P4_BROWSER_VERSION}` }, sessionId)
  await ready()
  await evaluate(`(() => {
    window.planNativeFetch=window.fetch.bind(window); window.planFault=null; window.planPosts={create:0,generate:0}; window.planResponses=[];
    window.fetch=async(input,init)=>{
      const url=String(input); const post=init?.method==='POST';
      if(post && url==='/api/plan-workspaces')window.planPosts.create++;
      if(post && url.includes('/generation'))window.planPosts.generate++;
      if(window.planReadFault && !post && url.includes('/api/plan-operations/'))throw new TypeError('synthetic original read unavailable');
      if(window.planFault==='ME' && url==='/api/auth/me'){window.planFault=null;throw new TypeError('synthetic me preflight loss')}
      if(window.planFault==='CSRF' && url==='/api/auth/csrf'){window.planFault=null;throw new TypeError('synthetic csrf preflight loss')}
      const response=await window.planNativeFetch(input,init);
      window.planResponses.push({url,status:response.status});if(window.planResponses.length>30)window.planResponses.shift();
      if((window.planFault==='CREATE_ACK' && post && url==='/api/plan-workspaces') || (window.planFault==='GENERATE_ACK' && post && url.includes('/generation'))){
        window.planFault=null;return {ok:response.ok,status:response.status,json:async()=>{throw new TypeError('synthetic committed response loss')}};
      }
      if(window.holdPlanRead && !post && url.includes('/api/plan-operations/')){window.holdPlanRead=false;return new Promise(resolve=>{window.releasePlanRead=()=>resolve(response)})}
      return response;
    };
  })()`)
  const receiptKey = 'interview-coach-plan-receipt-v1'
  if (process.env.P4_BROWSER_SCENARIO === 'INVALIDATED') {
    const kind = process.env.P4_BROWSER_UPDATE_KIND
    assert.ok(kind === 'RESUME' || kind === 'JD')
    await generateClick(); await wait(`document.querySelector('#plan-status')?.textContent.includes('RUNNING')`, 'original plan running')
    const old = await evaluate(`JSON.parse(sessionStorage.getItem('${receiptKey}'))`)
    assert.equal(await evaluate(`!!document.querySelector('#plan-new')`), false)
    await evaluate(`window.planReadFault=true`); await click('#plan-query')
    await wait(`!document.querySelector('#plan-status') && !!document.querySelector('[role="status"]')?.textContent`, 'unverified result hides new-plan action')
    assert.equal(await evaluate(`!!document.querySelector('#plan-new')`), false)
    assert.equal(await evaluate(`JSON.parse(sessionStorage.getItem('${receiptKey}')).operation`), old.operation)
    await evaluate(`window.planReadFault=false`); await click('#plan-query'); await wait(`document.querySelector('#plan-status')?.textContent.includes('RUNNING')`, 'original operation reverified')
    const second = await send('Target.createTarget', { url: `${base}/app/resumes/${process.env.P4_BROWSER_ROOT}` })
    const attached = await send('Target.attachToTarget', { targetId: second.targetId, flatten: true }); await send('Page.enable', {}, attached.sessionId)
    const editor = kind === 'RESUME' ? '#resume-version-body' : '#jd-body'
    await wait(`!!document.querySelector('${editor}') && !document.querySelector('${editor}').disabled`, 'material editor', attached.sessionId)
    await fill(editor, kind === 'RESUME' ? '[P4-SYNTHETIC] 线程池项目：换版后的Java并发经历，保留固定版本与恢复约束。'.repeat(4) : '[P4-SYNTHETIC] Java 后端岗位换版，需要理解线程池、JMM和故障恢复。'.repeat(4), attached.sessionId)
    await clickText(kind === 'RESUME' ? '保存简历新版本' : '确认并保存JD版本', attached.sessionId)
    await wait(`!!document.querySelector('[data-${kind === 'RESUME' ? 'resume' : 'jd'}-version="2"]')`, 'material version two saved', attached.sessionId)
    // 另一标签完成编辑后显式切回计划标签，等待真实焦点核实；不在后台页抢跑一次点击。
    await send('Page.bringToFront', {}, sessionId)
    await wait(`document.querySelector('#plan-status')?.textContent.includes('INVALIDATED')`, 'late result invalidated by actual material update')
    assert.equal(await evaluate(`JSON.parse(sessionStorage.getItem('${receiptKey}')).operation`), old.operation)
    const origin = await evaluate('performance.timeOrigin'); await send('Page.reload', {}, sessionId)
    await wait(`performance.timeOrigin!==${origin} && document.querySelector('#plan-status')?.textContent.includes('INVALIDATED')`, 'invalidated receipt survives refresh')
    await clickText('返回本人资料'); await wait(`location.pathname==='/app' && !!document.querySelector('.material-list li button')`, 'return to actual material page')
    await click('.material-list li button'); await clickText('准备单场计划')
    await wait(`location.pathname==='/app/plans' && document.querySelector('#plan-status')?.textContent.includes('INVALIDATED')`, 'material entry still verifies original receipt')
    assert.equal(await evaluate(`JSON.parse(sessionStorage.getItem('${receiptKey}')).operation`), old.operation)
    await evaluate(`(()=>{window.reviewPosts={create:0,generate:0};const prior=window.fetch.bind(window);window.fetch=async(input,init)=>{if(init?.method==='POST'){if(String(input)==='/api/plan-workspaces')window.reviewPosts.create++;if(String(input).includes('/generation'))window.reviewPosts.generate++}return prior(input,init)}})()`)
    await click('#plan-new'); await ready()
    await wait(`!document.querySelector('#plan-resume-version').disabled && !document.querySelector('#plan-jd-version').disabled`, 'explicit new plan unfreezes current versions')
    assert.equal(await evaluate(`sessionStorage.getItem('${receiptKey}')`), null)
    assert.equal(await evaluate(`new URL(location.href).searchParams.has('operationId')`), false)
    assert.equal(await evaluate(`document.querySelector('#plan-confirm').checked`), false)
    assert.deepEqual(await evaluate('window.reviewPosts'), { create: 0, generate: 0 })
    const selected = await evaluate(`({resume:document.querySelector('#plan-resume-version').value,jd:document.querySelector('#plan-jd-version').value,changedVersion:document.querySelector('${kind === 'RESUME' ? '#plan-resume-version' : '#plan-jd-version'}').selectedOptions[0].textContent})`)
    assert.ok(selected.changedVersion.includes('v2'))
    await generateClick(); await wait(`!!document.querySelector('#plan-result')`, 'explicit plan for updated material succeeds')
    const current = await evaluate(`JSON.parse(sessionStorage.getItem('${receiptKey}'))`)
    assert.notEqual(current.operation, old.operation); assert.notEqual(current.workspace, old.workspace)
    assert.deepEqual(await evaluate('window.reviewPosts'), { create: 1, generate: 1 })
    const original = await evaluate(`(async()=>{const r=await fetch('/api/plan-operations/${old.operation}?kind=GENERATE_PLAN');if(!r.ok)throw new Error('original missing');return await r.json()})()`)
    assert.equal(original.state, 'INVALIDATED'); assert.equal(original.workspaceId, old.workspace)
    const shot = await send('Page.captureScreenshot', { format: 'png' }, sessionId); fs.writeFileSync(process.env.P4_BROWSER_SCREENSHOT, Buffer.from(shot.data, 'base64'))
    fs.writeFileSync(process.env.P4_BROWSER_REPORT, JSON.stringify({ kind, old, current, selected, oldState: original.state, inFlightAndUnknownBlocked: true, refreshAndMaterialReturnPreservedReceipt: true, explicitPreparationPosts: 0, createPosts: 1, generationPosts: 1, profile, screenshot: process.env.P4_BROWSER_SCREENSHOT }, null, 2))
    process.stdout.write(`Actual Chrome: ${kind} update, original invalidation, refresh/material return, explicit new plan and retained history passed.\n`)
  } else {
  await evaluate(`window.planFault='ME'`); await generateClick(); await wait(`window.planFault===null`, 'me failure injected'); await delay(200)
  assert.deepEqual(await evaluate('window.planPosts'), { create: 0, generate: 0 }); assert.equal(await evaluate(`sessionStorage.getItem('${receiptKey}')`), null)
  await evaluate(`window.dispatchEvent(new Event('focus'))`); await ready()
  await evaluate(`window.planFault='CSRF'`); await generateClick(); await wait(`window.planFault===null`, 'csrf failure injected'); await delay(200)
  assert.deepEqual(await evaluate('window.planPosts'), { create: 0, generate: 0 }); assert.equal(await evaluate(`sessionStorage.getItem('${receiptKey}')`), null)
  await evaluate(`window.planReadFault=true;window.planFault='CREATE_ACK'`); await generateClick(); await wait(`!!sessionStorage.getItem('${receiptKey}') && window.planFault===null`, 'unresolved committed workspace receipt')
  const unresolved = await evaluate(`sessionStorage.getItem('${receiptKey}')`)
  await evaluate(`window.planFault='ME';window.dispatchEvent(new Event('focus'))`); await wait(`window.planFault===null`, 'old unknown identity preflight loss')
  assert.equal(await evaluate(`sessionStorage.getItem('${receiptKey}')`), unresolved)
  assert.deepEqual(await evaluate('window.planPosts'), { create: 1, generate: 0 })
  await evaluate(`window.planReadFault=false;window.dispatchEvent(new Event('focus'))`); await wait(`document.querySelector('#plan-status')?.textContent.includes('DRAFT')`, 'original committed workspace recovered')
  assert.deepEqual(await evaluate('window.planPosts'), { create: 1, generate: 0 })
  await evaluate(`window.planFault='GENERATE_ACK'`); await generateClick(); await wait(`!!document.querySelector('#plan-result')`, 'original generated plan recovered')
  assert.deepEqual(await evaluate('window.planPosts'), { create: 1, generate: 1 })
  const receipt = await evaluate(`JSON.parse(sessionStorage.getItem('${receiptKey}'))`)
  assert.equal(receipt.kind, 'GENERATE_PLAN')
  assert.deepEqual(Object.keys(receipt).sort(), ['kind', 'operation', 'owner', 'resumeId', 'workspace'])
  assert.equal(await evaluate('window.planInjected===true'), false); assert.equal(await evaluate(`document.querySelectorAll('#plan-result img').length`), 0)
  const recovery = await evaluate(`Array.from(document.querySelectorAll('a')).find(a=>a.textContent==='原操作恢复链接').href`)
  const shot = await send('Page.captureScreenshot', { format: 'png' }, sessionId)
  fs.writeFileSync(process.env.P4_BROWSER_SCREENSHOT, Buffer.from(shot.data, 'base64'))
  const origin = await evaluate('performance.timeOrigin'); await send('Page.reload', {}, sessionId)
  await wait(`performance.timeOrigin!==${origin} && document.readyState==='complete'`, 'new document after reload'); await wait(`!!document.querySelector('#plan-result')`, 'refresh returns original plan')
  assert.equal(await evaluate(`JSON.parse(sessionStorage.getItem('${receiptKey}')).operation`), receipt.operation)
  const second = await send('Target.createTarget', { url: `${base}/app` }); const attached = await send('Target.attachToTarget', { targetId: second.targetId, flatten: true }); await send('Page.enable', {}, attached.sessionId)
  await delay(300)
  await evaluate(`(() => { window.planNativeFetch=window.fetch.bind(window);window.holdPlanRead=true;
    window.fetch=async(input,init)=>{const r=await window.planNativeFetch(input,init);if(window.holdPlanRead && !init?.method && String(input).includes('/api/plan-operations/')){window.holdPlanRead=false;return new Promise(resolve=>{window.releasePlanRead=()=>resolve(r)})}return r}; })()`)
  await click('#plan-query'); await wait(`typeof window.releasePlanRead==='function'`, 'held original read')
  async function switchAccount(login, password) {
    await evaluate(`(async()=>{let csrf=await(await fetch('/api/auth/csrf')).json();await fetch('/api/auth/logout',{method:'POST',headers:{[csrf.headerName]:csrf.token}});csrf=await(await fetch('/api/auth/csrf')).json();const result=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',[csrf.headerName]:csrf.token},body:new URLSearchParams({username:${JSON.stringify(login)},password:${JSON.stringify(password)}})});if(!result.ok)throw new Error('login failed')})()`, attached.sessionId)
  }
  await switchAccount(process.env.P4_OTHER_USER, process.env.P4_OTHER_PASSWORD)
  await evaluate(`window.dispatchEvent(new Event('focus'))`); await wait(`sessionStorage.getItem('${receiptKey}')===null && !document.querySelector('#plan-result')`, 'other account clears private plan')
  await evaluate('window.releasePlanRead()'); await delay(200); assert.equal(await evaluate(`document.body.textContent.includes('线程池项目')`), false)
  await switchAccount(process.env.P4_BROWSER_USER, process.env.P4_BROWSER_PASSWORD)
  await send('Page.navigate', { url: recovery }, sessionId); await wait(`!!document.querySelector('#plan-result')`, 'owned recovery link after login')
  assert.equal(await evaluate(`JSON.parse(sessionStorage.getItem('${receiptKey}')).operation`), receipt.operation)
  await evaluate(`(async()=>{const me=await(await fetch('/api/auth/me')).json();if(me.accountId!==${JSON.stringify(process.env.P4_BROWSER_OWNER)})throw new Error('delete account mismatch');const csrf=await(await fetch('/api/auth/csrf')).json();const r=await fetch('/api/materials/resumes/${process.env.P4_BROWSER_ROOT}',{method:'DELETE',headers:{[csrf.headerName]:csrf.token,'X-Expected-Account-Id':${JSON.stringify(process.env.P4_BROWSER_OWNER)}}});if(!r.ok)throw new Error('delete failed '+r.status+' '+await r.text())})()`, attached.sessionId)
  await click('#plan-query'); await wait(`!document.querySelector('#plan-result') && document.body.textContent.includes('资料已删除')`, 'deletion receipt clears plan')
  assert.equal(await evaluate(`sessionStorage.getItem('${receiptKey}')`), null)
  fs.writeFileSync(process.env.P4_BROWSER_REPORT, JSON.stringify({ operation: receipt.operation, workspace: receipt.workspace, createPosts: 1, generationPosts: 1, preflightPosts: 0, oldResponseIgnored: true, recoveryLink: true, deletedBodyHidden: true, tabs: 2, profile, screenshot: process.env.P4_BROWSER_SCREENSHOT }, null, 2))
  process.stdout.write('Actual Chrome: default plan, zero-POST preflight, committed receipt recovery, refresh, two-account stale response, original link and deletion passed.\n')
  }
} finally {
  socket?.close(); browser.kill()
  // Retain the synthetic Chrome profile and screenshots; never recursively remove evidence.
}
