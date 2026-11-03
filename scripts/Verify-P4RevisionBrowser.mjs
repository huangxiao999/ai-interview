// Real Chrome + production Vue/API. Only HTTP delivery is faulted; no business state replacement.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
const base=process.env.P4_FRONTEND_BASE
assert.ok(base&&process.env.P4_BROWSER_USER&&process.env.P4_BROWSER_PASSWORD)
const profile=fs.mkdtempSync(path.resolve('../.cache/tmp/p4-revisions-chrome-'))
const browser=spawn('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',['--headless=new','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'],{stdio:'ignore'})
let socket
try {
  let endpoint
  for(let i=0;i<100;i++){const active=path.join(profile,'DevToolsActivePort');if(fs.existsSync(active)){const [port,suffix]=fs.readFileSync(active,'utf8').trim().split('\n');endpoint=`ws://127.0.0.1:${port}${suffix}`;break}await delay(100)}
  assert.ok(endpoint,'Chrome debugging unavailable');socket=new WebSocket(endpoint)
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true})})
  let id=0;const pending=new Map()
  socket.addEventListener('message',event=>{const m=JSON.parse(event.data);if(m.method==='Page.javascriptDialogOpening'){void send('Page.handleJavaScriptDialog',{accept:true},m.sessionId).catch(()=>{});return}const p=pending.get(m.id);if(!p)return;pending.delete(m.id);clearTimeout(p.timer);if(m.error)p.reject(new Error(m.error.message));else p.resolve(m.result)})
  function send(method,params={},sessionId){return new Promise((resolve,reject)=>{const requestId=++id;const timer=setTimeout(()=>{pending.delete(requestId);reject(new Error(`CDP timeout ${method}`))},15000);pending.set(requestId,{resolve,reject,timer});socket.send(JSON.stringify({id:requestId,method,params,...(sessionId?{sessionId}:{})}))})}
  const {targetId}=await send('Target.createTarget',{url:`${base}/login`});const {sessionId}=await send('Target.attachToTarget',{targetId,flatten:true});await send('Page.enable',{},sessionId)
  await send('Emulation.setDeviceMetricsOverride',{width:1360,height:1100,deviceScaleFactor:1,mobile:false},sessionId)
  async function evaluate(expression,target=sessionId){const r=await send('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true,userGesture:true},target);if(r.exceptionDetails)throw new Error(`Browser evaluation: ${r.exceptionDetails.text}`);return r.result.value}
  async function wait(expression,label,target=sessionId){for(let i=0;i<180;i++){try{if(await evaluate(expression,target))return}catch{}await delay(100)}throw new Error(`Timed out ${label}: ${await evaluate('document.body.innerText',target)}`)}
  async function fill(selector,value,target=sessionId){await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}))})()`,target)}
  async function click(selector,target=sessionId){await wait(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});if(!e||e.disabled)return false;e.click();return true})()`,`click ${selector}`,target)}
  await wait(`!!document.querySelector('#login-name')`,'login');await fill('#login-name',process.env.P4_BROWSER_USER);await fill('#login-password',process.env.P4_BROWSER_PASSWORD);await evaluate(`document.querySelector('form').requestSubmit()`);await wait(`location.pathname==='/app'`,'authenticated')
  await send('Page.navigate',{url:`${base}/app/plans?resumeId=${process.env.P4_BROWSER_ROOT}&resumeVersionId=${process.env.P4_BROWSER_VERSION}`},sessionId)
  await wait(`document.querySelector('#plan-knowledge')?.options.length>0`,'fixed knowledge');await click('#plan-confirm');await click('#plan-generate');await wait(`!!document.querySelector('#revision-status')`,'production generated revision')
  const recovery=await evaluate(`Array.from(document.querySelectorAll('a')).find(a=>a.textContent==='原操作恢复链接').href`)
  const second=await send('Target.createTarget',{url:recovery});const attached=await send('Target.attachToTarget',{targetId:second.targetId,flatten:true});await send('Page.enable',{},attached.sessionId)
  await wait(`!!document.querySelector('#revision-status')`,'second window',attached.sessionId)
  await fill('.revision-count','5');await fill('.revision-count','6',attached.sessionId);await click('#revision-save',attached.sessionId);await wait(`document.querySelector('#revision-status')?.textContent.includes('修订 2')`,'R2',attached.sessionId)
  await click('#revision-save');await wait(`document.querySelector('#revision-message')?.textContent.includes('另一窗口')`,'CAS conflict');assert.equal(await evaluate(`document.querySelector('.revision-count').value`),'5')
  await click('#revision-current');await wait(`document.querySelector('#revision-status')?.textContent.includes('修订 2')`,'explicit current');assert.equal(await evaluate(`document.querySelector('.revision-count').value`),'5')
  await click('#revision-reload');assert.equal(await evaluate(`document.querySelector('.revision-count').value`),'6');await fill('.revision-count','7')
  await evaluate(`window.nativeConfirm=window.confirm;window.confirm=()=>false;Array.from(document.querySelectorAll('a')).find(a=>a.textContent==='返回本人资料').click()`);assert.equal(await evaluate(`location.pathname`),'/app/plans');await evaluate(`window.confirm=window.nativeConfirm`)
  await evaluate(`window.revisionFault='SAVE';window.revisionPosts=0;window.nativeFetch=window.fetch.bind(window);window.fetch=async(input,init)=>{const url=String(input),post=init?.method==='POST';if(post&&(url.endsWith('/revisions')||url.endsWith('/confirmations')))window.revisionPosts++;const r=await window.nativeFetch(input,init);if(post&&((window.revisionFault==='SAVE'&&url.endsWith('/revisions'))||(window.revisionFault==='CONFIRM'&&url.endsWith('/confirmations')))){window.revisionFault=null;return {ok:r.ok,status:r.status,json:async()=>{throw new TypeError('synthetic committed delivery loss')}}}return r}`)
  const receiptKey='interview-coach-plan-revision-receipt-v1'
  await click('#revision-save');await wait(`document.querySelector('#revision-message')?.textContent.includes('结果未知')`,'save unknown')
  const receipt=await evaluate(`JSON.parse(sessionStorage.getItem('${receiptKey}'))`);assert.deepEqual(Object.keys(receipt).sort(),['kind','operation','owner','plan']);assert.equal(receipt.kind,'SAVE');assert.equal(await evaluate(`document.querySelector('#revision-save').disabled&&document.querySelector('#revision-confirm').disabled`),true)
  const origin=await evaluate('performance.timeOrigin');await send('Page.reload',{},sessionId);await wait(`performance.timeOrigin!==${origin}&&document.querySelector('#revision-status')?.textContent.includes('修订 3')`,'refresh original R3');assert.equal(await evaluate(`document.querySelector('.revision-count').value`),'7');assert.equal(await evaluate(`sessionStorage.getItem('${receiptKey}')`),null)
  await evaluate(`window.revisionPosts=0;window.nativeFetch=window.fetch.bind(window);window.fetch=async(input,init)=>{const r=await window.nativeFetch(input,init);if(init?.method==='POST'&&String(input).endsWith('/confirmations')){window.revisionPosts++;return {ok:r.ok,status:r.status,json:async()=>{throw new TypeError('synthetic confirmation delivery loss')}}}return r}`)
  await click('#revision-confirm');await wait(`document.querySelector('#revision-message')?.textContent.includes('结果未知')`,'confirm unknown');assert.equal((await evaluate(`JSON.parse(sessionStorage.getItem('${receiptKey}'))`)).kind,'CONFIRM');await click('#revision-query');await wait(`document.querySelector('#revision-status')?.textContent.includes('已确认')`,'confirmation recovered');assert.equal(await evaluate('window.revisionPosts'),1)
  const shot=await send('Page.captureScreenshot',{format:'png'},sessionId);fs.writeFileSync(process.env.P4_BROWSER_SCREENSHOT,Buffer.from(shot.data,'base64'))
  await evaluate(`window.holdRevision=true;const previous=window.fetch.bind(window);window.fetch=async(input,init)=>{const r=await previous(input,init);if(window.holdRevision&&!init?.method&&String(input).includes('/revisions/current')){window.holdRevision=false;return new Promise(resolve=>{window.releaseRevision=()=>resolve(r)})}return r}`)
  await click('#revision-current');await wait(`typeof window.releaseRevision==='function'`,'held read')
  await evaluate(`(async()=>{let c=await(await fetch('/api/auth/csrf')).json();await fetch('/api/auth/logout',{method:'POST',headers:{[c.headerName]:c.token}});c=await(await fetch('/api/auth/csrf')).json();const r=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',[c.headerName]:c.token},body:new URLSearchParams({username:${JSON.stringify(process.env.P4_OTHER_USER)},password:${JSON.stringify(process.env.P4_OTHER_PASSWORD)}})});if(!r.ok)throw new Error('login failed')})()`,attached.sessionId)
  await evaluate(`window.dispatchEvent(new Event('focus'))`);await wait(`!document.querySelector('#plan-result')&&sessionStorage.getItem('${receiptKey}')===null`,'account fence');await evaluate('window.releaseRevision()');await delay(250);assert.equal(await evaluate(`!!document.querySelector('#revision-status')`),false)
  fs.writeFileSync(process.env.P4_BROWSER_REPORT,JSON.stringify({profile,casPreservedDraft:true,explicitReload:true,leaveGuard:true,saveDeliveryLost:true,refreshOriginalRevision:3,confirmPosts:1,confirmationRecovered:true,minimalReceiptKeys:Object.keys(receipt),staleResponseIgnored:true,realServiceCalls:0,screenshot:process.env.P4_BROWSER_SCREENSHOT},null,2))
  process.stdout.write('Actual Chrome: edit, CAS, original receipts, refresh, leave guard and account fence passed.\n')
} finally {socket?.close();browser.kill()}
