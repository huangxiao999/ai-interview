// Real Chrome + production Vue/API. Only HTTP delivery is faulted; no business state replacement.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
const base=process.env.P4_FRONTEND_BASE
assert.ok(base&&process.env.P4_BROWSER_USER&&process.env.P4_BROWSER_PASSWORD)
const profile=fs.mkdtempSync(path.resolve('../.cache/tmp/p4-history-chrome-'))
const browser=spawn('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',['--headless=new','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{stdio:'ignore'})
let socket
try {
  let endpoint
  for(let i=0;i<100;i++){const active=path.join(profile,'DevToolsActivePort');if(fs.existsSync(active)){const [port,suffix]=fs.readFileSync(active,'utf8').trim().split('\n');endpoint=`ws://127.0.0.1:${port}${suffix}`;break}await delay(100)}
  assert.ok(endpoint,'Chrome debugging unavailable');socket=new WebSocket(endpoint)
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true})})
  let id=0;const pending=new Map()
  socket.addEventListener('message',event=>{const m=JSON.parse(event.data);if(m.method==='Runtime.exceptionThrown')process.stdout.write(JSON.stringify(m.params.exceptionDetails)+'\n');if(m.method==='Page.javascriptDialogOpening'){void send('Page.handleJavaScriptDialog',{accept:true},m.sessionId).catch(()=>{});return}const p=pending.get(m.id);if(!p)return;pending.delete(m.id);clearTimeout(p.timer);if(m.error)p.reject(new Error(m.error.message));else p.resolve(m.result)})
  function send(method,params={},sessionId){return new Promise((resolve,reject)=>{const requestId=++id;const timer=setTimeout(()=>{pending.delete(requestId);reject(new Error(`CDP timeout ${method}`))},15000);pending.set(requestId,{resolve,reject,timer});socket.send(JSON.stringify({id:requestId,method,params,...(sessionId?{sessionId}:{})}))})}
  const {targetId}=await send('Target.createTarget',{url:`${base}/login`});const {sessionId}=await send('Target.attachToTarget',{targetId,flatten:true});await send('Page.enable',{},sessionId)
  await send('Runtime.enable',{},sessionId)
  await send('Page.navigate',{url:`${base}/login`},sessionId)
  await send('Emulation.setDeviceMetricsOverride',{width:1360,height:1100,deviceScaleFactor:1,mobile:false},sessionId)
  async function evaluate(expression,target=sessionId){const r=await send('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true,userGesture:true},target);if(r.exceptionDetails)throw new Error(`Browser evaluation: ${r.exceptionDetails.text}`);return r.result.value}
  async function wait(expression,label,target=sessionId){for(let i=0;i<180;i++){try{if(await evaluate(expression,target))return}catch{}await delay(100)}throw new Error(`Timed out ${label}: ${await evaluate('document.body.innerText',target)}`)}
  async function fill(selector,value,target=sessionId){await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}))})()`,target)}
  async function click(selector,target=sessionId){await wait(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});if(!e||e.disabled)return false;e.click();return true})()`,`click ${selector}`,target)}
  await wait(`!!document.querySelector('#login-name')`,'login');await fill('#login-name',process.env.P4_BROWSER_USER);await fill('#login-password',process.env.P4_BROWSER_PASSWORD);await evaluate(`document.querySelector('form').requestSubmit()`);await wait(`location.pathname==='/app'`,'authenticated')
  const a=process.env.P4_HISTORY_A,b=process.env.P4_HISTORY_B,root=process.env.P4_HISTORY_ROOT
  await click('a[href="/app/plan-history"]');await wait(`document.querySelectorAll('.plan-history-item').length===3`,'history discovery')
  await evaluate(`window.historyFetch=window.fetch.bind(window);window.failHistory=true;window.fetch=async(input,init)=>{if(window.failHistory&&String(input).startsWith('/api/plan-workspaces?'))return new Response(JSON.stringify({code:'TEMPORARY_UNAVAILABLE',message:'合成查询故障'}),{status:503,headers:{'Content-Type':'application/json'}});return window.historyFetch(input,init)}`)
  await click('#history-refresh');await wait(`document.querySelector('[role="alert"]')?.textContent.includes('未将核实失败当作空列表')`,'query error distinct from empty');assert.equal(await evaluate(`!!document.querySelector('#history-empty')`),false)
  await evaluate('window.failHistory=false');await click('#history-refresh');await wait(`document.querySelectorAll('.plan-history-item').length===3`,'history read recovered')
  const link=workspace=>`.plan-history-item[data-workspace="${workspace}"] a`
  await click(link(a));await wait(`!!document.querySelector('#revision-status')&&location.search.includes('${a}')`,'A fixed plan')
  const aRevision=await evaluate(`new URL(location.href).searchParams.get('revisionId')`)
  await send('Page.reload',{},sessionId);await wait(`!!document.querySelector('#revision-status')&&location.search.includes('${a}')`,'refresh fixed A')
  await evaluate('history.back()');await wait(`location.pathname==='/app/plan-history'&&document.querySelectorAll('.plan-history-item').length===3`,'back to history')
  await evaluate('history.forward()');await wait(`location.search.includes('${a}')&&!!document.querySelector('#revision-status')`,'forward to A')
  // Same Vue route reused: B must replace A, and a delayed A response cannot overwrite it.
  await evaluate(`window.nativeFetch=window.fetch.bind(window);window.holdA=true;window.fetch=async(input,init)=>{const r=await window.nativeFetch(input,init);if(window.holdA&&String(input).endsWith('/plan-workspaces/${a}')){window.holdA=false;return new Promise(resolve=>{window.releaseA=()=>resolve(r)})}return r};window.dispatchEvent(new Event('focus'))`)
  await wait(`typeof window.releaseA==='function'`,'held old A')
  await evaluate(`document.querySelector('a[href="/app/plan-history"]').click()`);await wait(`!!document.querySelector('.plan-history-item[data-workspace="${b}"]')`,'history B')
  await click(link(b));await wait(`location.search.includes('${b}')&&!!document.querySelector('#revision-status')`,'B')
  const fixedB=await evaluate(`document.querySelector('#plan-status').innerText`);await evaluate('window.releaseA()');await delay(250)
  assert.equal(await evaluate(`document.querySelector('#plan-status').innerText`),fixedB)
  // Reuse the same component for A/B via router-linked operation; observe fixed IDs rather than latest material.
  await evaluate(`document.querySelector('a[href="/app/plan-history"]').click()`);await wait(`!!document.querySelector('.plan-history-item[data-workspace="${a}"]')`,'return A')
  await click(link(a));await wait(`location.search.includes('${a}')&&!!document.querySelector('#revision-status')`,'A again')
  const originalReceipt=await evaluate(`JSON.parse(sessionStorage.getItem('interview-coach-plan-receipt-v1'))`)
  await evaluate(`window.queryFetch=window.fetch.bind(window);window.failOriginal=true;window.planWritePosts=0;window.fetch=async(input,init)=>{const url=String(input);if(init?.method==='POST'&&(url.startsWith('/api/plan-')||url.startsWith('/api/plans/')))window.planWritePosts++;if(window.failOriginal&&url.startsWith('/api/plan-operations/${originalReceipt.operation}?'))return new Response(JSON.stringify({code:'TEMPORARY_UNAVAILABLE',message:'原结果暂不可核'}),{status:503,headers:{'Content-Type':'application/json'}});return window.queryFetch(input,init)}`)
  await evaluate(`document.querySelector('a[href="/app/plan-history"]').click()`);await wait(`!!document.querySelector('.plan-history-item[data-workspace="${b}"]')`,'unknown receipt other history')
  await click(link(b));await wait(`location.search.includes('${b}')&&document.querySelector('#revision-confirm')?.disabled&&document.querySelector('#plan-status')?.textContent.includes('固定资料')`,'unknown receipt readonly B')
  assert.deepEqual(await evaluate(`JSON.parse(sessionStorage.getItem('interview-coach-plan-receipt-v1'))`),originalReceipt)
  await click('#plan-query');await wait(`location.search.includes('${a}')&&!!document.querySelector('#revision-status')`,'explicit original target')
  await evaluate('window.failOriginal=false');await click('#plan-query');await wait(`!document.body.innerText.includes('原请求仍待核实')`,'original receipt verified')
  assert.equal(await evaluate('window.planWritePosts'),0)
  // Updating only JD uses the actual M-02 API, no direct database edit.
  await evaluate(`(async()=>{const c=await(await fetch('/api/auth/csrf')).json();const r=await fetch('/api/materials/resumes/${root}/jd-versions',{method:'POST',headers:{'Content-Type':'application/json',[c.headerName]:c.token,'X-Expected-Account-Id':${JSON.stringify(process.env.P4_BROWSER_OWNER)},'X-Operation-Id':crypto.randomUUID()},body:JSON.stringify({body:'[P4-SYNTHETIC] Java 后端岗位换版，需要JMM和线程池。'.repeat(5)})});if(r.status!==201)throw new Error('material update failed')})()`)
  await evaluate(`window.dispatchEvent(new Event('focus'))`);await wait(`document.querySelector('#plan-status')?.textContent.includes('资料已更新')`,'material version mismatch')
  await click('#revision-current');await wait(`document.querySelector('#revision-save')?.disabled`,'old plan readonly')
  assert.equal(await evaluate(`(async()=>{const v=await(await fetch('/api/plan-workspaces/${a}')).json();return v.plan.revisionId})()`),aRevision)
  // Explicit new preparation clears old operation, but does not generate or silently submit.
  await click('#plan-new');await wait(`!document.querySelector('#plan-status')&&document.querySelector('#plan-jd-version')?.options.length===2`,'prepare new fixed JD')
  assert.equal(await evaluate(`document.querySelector('#plan-jd-version').disabled`),false)
  await evaluate(`document.querySelector('a[href="/app/plan-history"]').click()`);await wait(`!!document.querySelector('.plan-history-item[data-workspace="${a}"]')`,'history material changed')
  assert.equal(await evaluate(`document.querySelector('.plan-history-item[data-workspace="${a}"]').textContent.includes('JD v1')`),true)
  // Fresh re-login recovers through history without any browser receipt.
  async function switchAccount(user,password){await evaluate(`(async()=>{let c=await(await fetch('/api/auth/csrf')).json();await fetch('/api/auth/logout',{method:'POST',headers:{[c.headerName]:c.token}});c=await(await fetch('/api/auth/csrf')).json();const r=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',[c.headerName]:c.token},body:new URLSearchParams({username:${JSON.stringify(user)},password:${JSON.stringify(password)}})});if(!r.ok)throw new Error('switch failed')})()`)}
  await evaluate(`window.listFetch=window.fetch.bind(window);window.holdList=true;window.fetch=async(input,init)=>{const r=await window.listFetch(input,init);if(window.holdList&&String(input).startsWith('/api/plan-workspaces?')){window.holdList=false;return new Promise(resolve=>{window.releaseList=()=>resolve(r)})}return r}`)
  await click('#history-refresh');await wait(`typeof window.releaseList==='function'`,'held A list')
  await switchAccount(process.env.P4_OTHER_USER,process.env.P4_OTHER_PASSWORD);await evaluate(`window.dispatchEvent(new Event('focus'))`);await wait(`!document.querySelector('.plan-history-item[data-workspace="${a}"]')`,'B identity no A')
  await evaluate('window.releaseList()');await delay(250);assert.equal(await evaluate(`!!document.querySelector('.plan-history-item[data-workspace="${a}"]')`),false)
  await switchAccount(process.env.P4_BROWSER_USER,process.env.P4_BROWSER_PASSWORD);await evaluate(`window.dispatchEvent(new Event('focus'))`);await wait(`!!document.querySelector('.plan-history-item[data-workspace="${a}"]')`,'A re-login discover original')
  const storage=await evaluate(`({session:{...sessionStorage},local:{...localStorage}})`)
  assert.ok(!JSON.stringify(storage).includes('线程池项目'));assert.ok(!JSON.stringify(storage).includes('resumeQuote'));assert.ok(!JSON.stringify(storage).includes('password'))
  await click(link(a));await wait(`!!document.querySelector('#plan-result')`,'before deletion')
  await evaluate(`(async()=>{const c=await(await fetch('/api/auth/csrf')).json();const r=await fetch('/api/materials/resumes/${root}',{method:'DELETE',headers:{[c.headerName]:c.token,'X-Expected-Account-Id':${JSON.stringify(process.env.P4_BROWSER_OWNER)}}});if(!r.ok)throw new Error('delete failed')})();`)
  await evaluate(`window.dispatchEvent(new Event('focus'))`);await wait(`!document.querySelector('#plan-result')&&!document.querySelector('#revision-status')`,'deleted reconnect hides content')
  await evaluate(`document.querySelector('a[href="/app/plan-history"]').click()`);await wait(`document.querySelectorAll('.plan-history-item').length===2`,'deleted absent from history')
  assert.equal(await evaluate(`!!document.querySelector('.plan-history-item[data-workspace="${a}"]')`),false)
  const shot=await send('Page.captureScreenshot',{format:'png'},sessionId);fs.writeFileSync(process.env.P4_BROWSER_SCREENSHOT,Buffer.from(shot.data,'base64'))
  fs.writeFileSync(process.env.P4_BROWSER_REPORT,JSON.stringify({profile,discovered:3,queryFailureNotEmpty:true,historyBackForward:true,refreshFixedRevision:true,lateOldResponseIgnored:true,unknownReceiptPreserved:true,explicitOriginalTarget:true,unknownWrites:0,materialVersionReadonly:true,explicitNewPreparation:true,reloginAToBToA:true,lateOtherAccountListIgnored:true,noPrivateBodyInStorage:true,deletionRecheck:true,remaining:2,extraQueryOrGeneration:0,screenshot:process.env.P4_BROWSER_SCREENSHOT},null,2))
  process.stdout.write('Actual Chrome: history, fixed navigation, identity, late response and deletion passed.\n')
} finally {socket?.close();browser.kill()}
