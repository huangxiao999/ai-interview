// Runs the actual Vue paste/list/delete path through Vite and the local backend in real Chrome.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import fs from 'node:fs'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

const chrome = process.env.P2_CHROME_PATH ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const base = process.env.P2_FRONTEND_BASE
const username = process.env.P2_BROWSER_USER
const password = process.env.P2_BROWSER_PASSWORD
const otherUser = process.env.P2_BROWSER_OTHER_USER
const otherPassword = process.env.P2_BROWSER_OTHER_PASSWORD
assert.ok(base && username && password && otherUser && otherPassword, 'Browser verification inputs are required')
assert.ok(fs.existsSync(chrome), 'Real Chrome is required')

const profileRoot = fs.realpathSync(path.resolve('../.cache/tmp'))
const profile = fs.mkdtempSync(path.join(profileRoot, 'p2-dt01-chrome-'))
const browser = spawn(
  chrome,
  [
    '--headless=new',
    '--no-first-run',
    '--no-default-browser-check',
    '--remote-debugging-port=0',
    `--user-data-dir=${profile}`,
    'about:blank',
  ],
  { stdio: 'ignore' },
)

let socket
try {
  let endpoint
  for (let i = 0; i < 100; i += 1) {
    const activePort = path.join(profile, 'DevToolsActivePort')
    if (fs.existsSync(activePort)) {
      const [port, browserPath] = fs.readFileSync(activePort, 'utf8').trim().split('\n')
      endpoint = `ws://127.0.0.1:${port}${browserPath}`
      break
    }
    if (browser.exitCode !== null) throw new Error('Chrome exited before remote debugging was ready')
    await delay(100)
  }
  assert.ok(endpoint, 'Chrome remote debugging did not start')
  socket = new WebSocket(endpoint)
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true })
    socket.addEventListener('error', reject, { once: true })
  })
  let nextId = 0
  let acceptDialogs = true
  let dialogCount = 0
  const pending = new Map()
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (message.method === 'Page.javascriptDialogOpening') {
      dialogCount += 1
      void send('Page.handleJavaScriptDialog', { accept: acceptDialogs }, message.sessionId)
      return
    }
    if (!message.id || !pending.has(message.id)) return
    const { resolve, reject } = pending.get(message.id)
    pending.delete(message.id)
    if (message.error) reject(new Error(message.error.message))
    else resolve(message.result)
  })
  function send(method, params = {}, sessionId) {
    const id = ++nextId
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject })
      socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }))
    })
  }
  const { targetId } = await send('Target.createTarget', { url: `${base}/app` })
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true })
  await send('Page.enable', {}, sessionId)
  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false }, sessionId)
  async function evaluate(expression, targetSession = sessionId) {
    const result = await send(
      'Runtime.evaluate',
      { expression, awaitPromise: true, returnByValue: true, userGesture: true },
      targetSession,
    )
    if (result.exceptionDetails) throw new Error('Browser page script failed')
    return result.result.value
  }
  async function waitUntil(expression, label, targetSession = sessionId) {
    for (let i = 0; i < 100; i += 1) {
      try {
        if (await evaluate(expression, targetSession)) return
      } catch {
        // Navigation may replace the JavaScript execution context.
      }
      await delay(100)
    }
    throw new Error(`Timed out waiting for ${label}`)
  }
  async function login(user = username, secretValue = password, targetSession = sessionId) {
    await waitUntil(`location.pathname === '/login' && !!document.querySelector('#login-name')`, 'login page', targetSession)
    await evaluate(`(() => {
      const name = document.querySelector('#login-name')
      const secret = document.querySelector('#login-password')
      name.value = ${JSON.stringify(user)}
      secret.value = ${JSON.stringify(secretValue)}
      name.dispatchEvent(new Event('input', { bubbles: true }))
      secret.dispatchEvent(new Event('input', { bubbles: true }))
      document.querySelector('form').requestSubmit()
    })()`, targetSession)
    await waitUntil(`location.pathname === '/app' && !!document.querySelector('#resume-body')`, 'resume workspace', targetSession)
  }

  async function assertNewResumeBlocksVersionSaves() {
    await waitUntil(`!!document.querySelector('#jd-body')
      && !Array.from(document.querySelectorAll('button')).find(button => button.textContent.trim() === '核实原保存结果').disabled`,
      'new resume unresolved with existing version panel')
    const before = await evaluate(`({ writes: window.p2Hooks.versionWriteAttempts, me: window.p2Hooks.meCalls })`)
    for (const label of ['保存简历新版本', '确认并保存JD版本']) {
      assert.equal(await evaluate(`Array.from(document.querySelectorAll('button'))
        .find(button => button.textContent.trim() === ${JSON.stringify(label)}).disabled`), true,
        'an unresolved new resume must block another version save')
      // Dispatch deliberately bypasses the native disabled-button click suppression.
      await evaluate(`Array.from(document.querySelectorAll('button'))
        .find(button => button.textContent.trim() === ${JSON.stringify(label)})
        .dispatchEvent(new MouseEvent('click', { bubbles: true }))`)
    }
    await delay(100)
    assert.equal(await evaluate(`window.p2Hooks.versionWriteAttempts`), before.writes)
    assert.equal(await evaluate(`window.p2Hooks.meCalls`), before.me,
      'version save handlers must stop before identity precheck or POST')
    assert.equal(await evaluate(`!!Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.trim() === '核实版本保存结果')`), false,
      'a second unresolved version operation must not be created')
  }

  await login()
  const accountARoot = await evaluate(`(async () => {
    const me = await (await fetch('/api/auth/me')).json()
    const csrf = await (await fetch('/api/auth/csrf')).json()
    const saved = await fetch('/api/materials/resumes', { method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.token,
        'X-Expected-Account-Id': me.accountId, 'X-Operation-Id': crypto.randomUUID() },
      body: JSON.stringify({ body: 'DT04 private A direct fixture '.repeat(6) }) })
    return (await saved.json()).resumeId
  })()`)
  const unsaved = '切窗未保存草稿' + 'U'.repeat(105)
  await evaluate(`(() => {
    const nativeFetch = window.fetch.bind(window)
    window.p2Hooks = { holdMe: false, releaseMe: null, holdNextList: false,
      releaseList: null, listReleaseApplied: false, failPostBody: false,
      failLookup: false, failNextMe: false,
      invalidateNextPost: false, invalidateNextDelete: false,
      meCalls: 0, csrfCalls: 0,
      postOperations: [], versionOperations: [], versionWriteAttempts: 0,
      failVersionBody: false, failVersionLookup: false,
      failDeleteBody: false, failDeleteLookup: false, deleteWrites: 0 }
    window.fetch = async (input, init) => {
      const pathname = new URL(input instanceof Request ? input.url : input, location.href).pathname
      if (init?.method === 'POST' && (pathname.endsWith('/versions') || pathname.endsWith('/jd-versions'))) {
        window.p2Hooks.versionWriteAttempts += 1
      }
      if (pathname.startsWith('/api/materials/resumes/') && init?.method === 'DELETE') window.p2Hooks.deleteWrites += 1
      if (pathname === '/api/auth/me') window.p2Hooks.meCalls += 1
      if (pathname === '/api/auth/csrf') window.p2Hooks.csrfCalls += 1
      if (pathname === '/api/auth/me' && window.p2Hooks.failNextMe) {
        window.p2Hooks.failNextMe = false
        throw new TypeError('synthetic identity precheck interruption')
      }
      if (pathname === '/api/auth/me' && window.p2Hooks.holdMe) {
        return new Promise(resolve => {
          window.p2Hooks.releaseMe = () => nativeFetch(input, init).then(resolve)
        })
      }
      if (pathname === '/api/materials/resumes' && init?.method !== 'POST'
        && window.p2Hooks.holdNextList) {
        window.p2Hooks.holdNextList = false
        const oldResponse = await nativeFetch(input, init)
        return new Promise(resolve => {
          window.p2Hooks.releaseList = () => resolve({
            ok: oldResponse.ok,
            json: async () => {
              const body = await oldResponse.json()
              window.p2Hooks.listReleaseApplied = true
              return body
            },
          })
        })
      }
      if (pathname.startsWith('/api/materials/resumes/operations/') && window.p2Hooks.failLookup) {
        throw new TypeError('synthetic operation lookup interruption')
      }
      if (pathname.includes('/jd-operations/') && window.p2Hooks.failVersionLookup) {
        throw new TypeError('synthetic version lookup interruption')
      }
      if (pathname.endsWith('/deletion') && window.p2Hooks.failDeleteLookup) {
        throw new TypeError('synthetic deletion lookup interruption')
      }
      if (pathname.endsWith('/jd-versions') && init?.method === 'POST') {
        window.p2Hooks.versionOperations.push(init.headers['X-Operation-Id'])
        const response = await nativeFetch(input, init)
        if (response.ok && window.p2Hooks.failVersionBody) {
          window.p2Hooks.failVersionBody = false
          return { ok: true, json: async () => { throw new TypeError('synthetic JD body interruption') } }
        }
        return response
      }
      if (pathname === '/api/materials/resumes' && init?.method === 'POST') {
        window.p2Hooks.postOperations.push(init.headers['X-Operation-Id'])
        if (window.p2Hooks.invalidateNextPost) {
          window.p2Hooks.invalidateNextPost = false
          return nativeFetch(input, { ...init,
            headers: { ...init.headers, 'X-CSRF-TOKEN': 'synthetic-invalid-token' } })
        }
        const response = await nativeFetch(input, init)
        if (response.ok && window.p2Hooks.failPostBody) {
          window.p2Hooks.failPostBody = false
          return { ok: true, json: async () => { throw new TypeError('synthetic body failure') } }
        }
        return response
      }
      if (pathname.startsWith('/api/materials/resumes/') && init?.method === 'DELETE'
        && window.p2Hooks.invalidateNextDelete) {
        window.p2Hooks.invalidateNextDelete = false
        return nativeFetch(input, { ...init,
          headers: { ...init.headers, 'X-CSRF-TOKEN': 'synthetic-invalid-token' } })
      }
      if (pathname.startsWith('/api/materials/resumes/') && init?.method === 'DELETE') {
        const response = await nativeFetch(input, init)
        if (response.ok && window.p2Hooks.failDeleteBody) {
          window.p2Hooks.failDeleteBody = false
          return { ok: true, json: async () => { throw new TypeError('synthetic deletion body interruption') } }
        }
        return response
      }
      return nativeFetch(input, init)
    }
    const input = document.querySelector('#resume-body')
    input.value = ${JSON.stringify(unsaved)}
    input.dispatchEvent(new Event('input', { bubbles: true }))
    window.p2Hooks.holdMe = true
    window.dispatchEvent(new Event('focus'))
  })()`)
  await waitUntil(`!!window.p2Hooks.releaseMe`, 'paused same-account identity check')
  assert.equal(await evaluate(`!!document.querySelector('#resume-body')`), false)
  await evaluate(`window.p2Hooks.holdMe = false; window.p2Hooks.releaseMe()`)
  await waitUntil(`document.querySelector('#resume-body')?.value === ${JSON.stringify(unsaved)}`,
    'draft retained after same-account focus')

  const beforeCsrfRecovery = await evaluate(`({ me: window.p2Hooks.meCalls,
    csrf: window.p2Hooks.csrfCalls, posts: window.p2Hooks.postOperations.length })`)
  await evaluate(`(() => {
    window.p2Hooks.invalidateNextPost = true
    Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('确认并保存简历')).click()
  })()`)
  await waitUntil(`window.p2Hooks.meCalls > ${beforeCsrfRecovery.me}
    && document.querySelector('#resume-body')?.value === ${JSON.stringify(unsaved)}
    && !Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('确认并保存简历')).disabled`,
    'CSRF rejection followed by same-account identity verification')
  await evaluate(`Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('确认并保存简历')).click()`)
  await waitUntil(`window.p2Hooks.csrfCalls > ${beforeCsrfRecovery.csrf}
    && document.body.innerText.includes('简历正文已保存为第 1 版')`,
    'fresh CSRF token used for the next explicit save')
  assert.equal(await evaluate(`window.p2Hooks.postOperations.length`), beforeCsrfRecovery.posts + 2)
  await evaluate(`Array.from(document.querySelectorAll('.material-list li:first-child button'))
    .find(button => button.textContent.includes('删除整份')).click()`)
  await waitUntil(`document.body.innerText.includes('确认删除整份简历')`, 'CSRF-case cleanup confirmation')
  await evaluate(`Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('确认删除')).click()`)
  await waitUntil(`!document.body.innerText.includes(${JSON.stringify(unsaved)})`, 'CSRF-case cleanup')
  await evaluate(`window.p2Hooks.postOperations = []`)

  const beforeLateSaveCount = await evaluate(`document.querySelectorAll('.material-list li').length`)
  await evaluate(`(() => {
    window.p2Hooks.holdNextList = true
    window.p2Hooks.releaseList = null
    window.p2Hooks.listReleaseApplied = false
    Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('刷新列表')).click()
  })()`)
  await waitUntil(`!!window.p2Hooks.releaseList`, 'older list snapshot before save')
  const synthetic = '浏览器合成简历' + 'A'.repeat(110)
  await evaluate(`(() => {
    const input = document.querySelector('#resume-body')
    input.value = ${JSON.stringify(synthetic)}
    input.dispatchEvent(new Event('input', { bubbles: true }))
    Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('确认并保存简历')).click()
  })()`)
  await waitUntil(`document.body.innerText.includes('简历正文已保存为第 1 版')
    && document.querySelectorAll('.material-list li').length === ${beforeLateSaveCount + 1}`,
    'saved resume and refreshed list')
  await evaluate(`window.p2Hooks.releaseList()`)
  await waitUntil(`window.p2Hooks.listReleaseApplied`, 'older list response after save')
  await delay(100)
  assert.equal(await evaluate(`document.querySelectorAll('.material-list li').length`),
    beforeLateSaveCount + 1, 'older list response must not hide the saved resume')
  assert.equal(await evaluate(`document.body.innerText.includes(${JSON.stringify(synthetic)})`), true)
  assert.equal(await evaluate(`!!document.querySelector('.material-list li')`), true)
  const beforeUnknownCount = await evaluate(`document.querySelectorAll('.material-list li').length`)
  const uncertain = '未知回包合成简历' + 'B'.repeat(110)
  await evaluate(`(() => {
    const input = document.querySelector('#resume-body')
    input.value = ${JSON.stringify(uncertain)}
    input.dispatchEvent(new Event('input', { bubbles: true }))
    window.p2Hooks.failPostBody = true
    window.p2Hooks.failLookup = true
    Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('确认并保存简历')).click()
  })()`)
  await waitUntil(`document.body.innerText.includes('暂时无法确认是否保存')`, 'unknown save result')
  assert.equal(await evaluate(`window.p2Hooks.postOperations.length`), 2)
  await assertNewResumeBlocksVersionSaves()
  const pendingOperationBeforePrecheck = await evaluate(`window.p2Hooks.postOperations[1]`)
  await evaluate(`(() => {
    window.p2Hooks.failNextMe = true
    Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('沿用原操作重试')).click()
  })()`)
  await waitUntil(`window.p2Hooks.meCalls > 0
    && document.body.innerText.includes('暂时无法核实登录身份')
    && !!document.querySelector('#resume-body')`, 'identity precheck failure and recovery')
  assert.equal(await evaluate(`window.p2Hooks.postOperations.length`), 2,
    'failed identity precheck must not issue another save')
  assert.equal(await evaluate(`!!Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('沿用原操作重试'))`), true,
  'unresolved original save must remain available after identity recovery')
  await evaluate(`(() => {
    window.p2Hooks.releaseMe = null
    window.p2Hooks.holdMe = true
    window.dispatchEvent(new Event('focus'))
  })()`)
  await waitUntil(`!!window.p2Hooks.releaseMe`, 'paused identity check with unknown save')
  assert.equal(await evaluate(`!!document.querySelector('#resume-body')`), false)
  await evaluate(`window.p2Hooks.holdMe = false; window.p2Hooks.releaseMe()`)
  await waitUntil(`document.querySelector('#resume-body')?.value === ${JSON.stringify(uncertain)}
    && document.body.innerText.includes('沿用原操作重试')`, 'pending operation retained after focus')
  await evaluate(`(() => {
    window.p2Hooks.failLookup = false
    Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('沿用原操作重试')).click()
  })()`)
  await waitUntil(`document.body.innerText.includes('简历正文已保存为第 1 版')
    && document.querySelectorAll('.material-list li').length === ${beforeUnknownCount + 1}`,
    'same operation retry')
  assert.equal(await evaluate(`window.p2Hooks.postOperations.length`), 3)
  assert.equal(await evaluate(`window.p2Hooks.postOperations[2]`), pendingOperationBeforePrecheck)
  assert.equal(await evaluate(`window.p2Hooks.postOperations[1] === window.p2Hooks.postOperations[2]`), true)
  await evaluate(`Array.from(document.querySelectorAll('.material-list li:first-child button'))
    .find(button => button.textContent.includes('删除整份')).click()`)
  await waitUntil(`document.body.innerText.includes('确认删除整份简历')`, 'unknown-case cleanup confirmation')
  await evaluate(`Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('确认删除')).click()`)
  await waitUntil(`document.querySelectorAll('.material-list li').length === ${beforeUnknownCount}`,
    'unknown-case cleanup')
  await evaluate(`document.querySelector('.material-list li:first-child button').click()`)
  await waitUntil(`!!document.querySelector('#jd-body')`, 'version editing panel')
  // A second unknown create exercises the query-only recovery path as well as the retry above.
  await waitUntil(`!document.querySelector('#resume-body')?.disabled`, 'ready for query-only create recovery')
  const beforeQueryRecovery = await evaluate(`({ items: document.querySelectorAll('.material-list li').length,
    posts: window.p2Hooks.postOperations.length })`)
  const queryRecoveredBody = '仅核实原新建结果的合成简历' + 'Q'.repeat(110)
  await evaluate(`(() => {
    const input = document.querySelector('#resume-body')
    input.value = ${JSON.stringify(queryRecoveredBody)}
    input.dispatchEvent(new Event('input', { bubbles: true }))
    window.p2Hooks.failPostBody = true
    window.p2Hooks.failLookup = true
    Array.from(document.querySelectorAll('button')).find(button => button.textContent.trim() === '确认并保存简历').click()
  })()`)
  await waitUntil(`document.body.innerText.includes('暂时无法确认是否保存')`, 'query-only new resume result unknown')
  await assertNewResumeBlocksVersionSaves()
  await evaluate(`window.p2Hooks.failLookup = false;
    Array.from(document.querySelectorAll('button')).find(button => button.textContent.trim() === '核实原保存结果').click()`)
  await waitUntil(`document.querySelector('[aria-label="简历详情"] pre')?.textContent === ${JSON.stringify(queryRecoveredBody)}
    && document.querySelectorAll('.material-list li').length === ${beforeQueryRecovery.items + 1}
    && !document.querySelector('#resume-body')?.disabled`, 'new resume recovered by its original operation query')
  assert.equal(await evaluate(`window.p2Hooks.postOperations.length`), beforeQueryRecovery.posts + 1)
  await evaluate(`Array.from(document.querySelectorAll('.material-list li:first-child button'))
    .find(button => button.textContent.includes('删除整份')).click()`)
  await waitUntil(`document.body.innerText.includes('确认删除整份简历')`, 'query-only recovery cleanup confirmation')
  await evaluate(`Array.from(document.querySelectorAll('button')).find(button => button.textContent.trim() === '确认删除').click()`)
  await waitUntil(`document.querySelectorAll('.material-list li').length === ${beforeQueryRecovery.items}
    && !document.querySelector('.material-list li:first-child button').disabled`, 'query-only recovery cleanup')
  await evaluate(`document.querySelector('.material-list li:first-child button').click()`)
  await waitUntil(`!!document.querySelector('#jd-body') && !document.querySelector('#jd-body').disabled`, 'A version panel after resolving both creates')
  assert.equal(await evaluate(`document.querySelectorAll('[data-resume-version]').length`), 1)
  assert.equal(await evaluate(`document.querySelectorAll('[data-jd-version]').length`), 0)
  const jdFirst = 'Java后端目标岗位' + 'J'.repeat(70)
  const jdSecond = '第二版Java后端目标岗位' + 'K'.repeat(70)
  await evaluate(`(() => {
    const input = document.querySelector('#jd-body')
    input.value = ${JSON.stringify(jdFirst)}
    input.dispatchEvent(new Event('input', { bubbles: true }))
    window.p2Hooks.failVersionBody = true
    window.p2Hooks.failVersionLookup = true
    Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('确认并保存JD版本')).click()
  })()`)
  await waitUntil(`document.body.innerText.includes('暂时无法确认版本是否保存')`, 'unknown JD save')
  assert.equal(await evaluate(`window.p2Hooks.versionOperations.length`), 1)
  await evaluate(`window.p2Hooks.failNextMe = true;
    Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('沿用原操作重试版本')).click()`)
  await waitUntil(`document.body.innerText.includes('暂时无法核实登录身份') && !!document.querySelector('#jd-body')`, 'JD precheck recovery')
  assert.equal(await evaluate(`window.p2Hooks.versionOperations.length`), 1)
  assert.equal(await evaluate(`document.querySelector('#jd-body').value`), jdFirst)
  assert.equal(await evaluate(`!!Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('核实版本保存结果'))`), true)
  await evaluate(`window.p2Hooks.failVersionLookup = false;
    Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('沿用原操作重试版本')).click()`)
  await waitUntil(`document.body.innerText.includes('JD第 1 版已确认保存') && !!document.querySelector('[data-jd-version="1"]')`, 'original JD operation restored')
  assert.equal(await evaluate(`window.p2Hooks.versionOperations[0] === window.p2Hooks.versionOperations[1]`), true)
  assert.equal(await evaluate(`document.querySelectorAll('[data-jd-version]').length`), 1)
  await waitUntil(`!document.querySelector('#jd-body')?.disabled`, 'JD save completion')
  await evaluate(`(() => {
    const input = document.querySelector('#jd-body')
    input.value = ${JSON.stringify(jdSecond)}
    input.dispatchEvent(new Event('input', { bubbles: true }))
    Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('确认并保存JD版本')).click()
  })()`)
  await waitUntil(`!!document.querySelector('[data-jd-version="2"]') && !document.querySelector('[data-jd-version="1"]').disabled`, 'second JD version')
  await evaluate(`document.querySelector('[data-jd-version="1"]').click()`)
  await waitUntil(`document.querySelector('[aria-label="JD详情"] pre')?.textContent === ${JSON.stringify(jdFirst)}`, 'fixed JD V1')
  const resumeSecond = synthetic + '\n简历第二版'
  await evaluate(`(() => {
    const input = document.querySelector('#resume-version-body')
    input.value = ${JSON.stringify(resumeSecond)}
    input.dispatchEvent(new Event('input', { bubbles: true }))
    Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('保存简历新版本')).click()
  })()`)
  await waitUntil(`!!document.querySelector('[data-resume-version="2"]') && !document.querySelector('[data-resume-version="1"]').disabled`, 'second resume version')
  await evaluate(`document.querySelector('[data-resume-version="1"]').click()`)
  await waitUntil(`document.querySelector('[aria-label="简历详情"] pre')?.textContent === ${JSON.stringify(synthetic)}`, 'fixed resume V1')

  // Keep B's deletion dialog open while A's accepted version save remains unresolved.
  for (const recoverDelete of [false, true]) {
    await waitUntil(`!document.querySelector('#resume-body')?.disabled`, 'ready to create deletion target B')
    const listCount = await evaluate(`document.querySelectorAll('.material-list li').length`)
    const versionCount = await evaluate(`document.querySelectorAll('[data-jd-version]').length`)
    await evaluate(`(() => {
      const input = document.querySelector('#resume-body')
      input.value = '待删除的另一份合成简历' + 'D'.repeat(110)
      input.dispatchEvent(new Event('input', { bubbles: true }))
      Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('确认并保存简历')).click()
    })()`)
    await waitUntil(`document.querySelectorAll('.material-list li').length === ${listCount + 1}
      && !document.querySelector('#resume-body')?.disabled`, 'B created before deletion confirmation')
    await evaluate(`Array.from(document.querySelectorAll('.material-list li:first-child button'))
      .find(button => button.textContent.includes('删除整份')).click()`)
    await waitUntil(`document.body.innerText.includes('确认删除整份简历')
      && !document.querySelector('.material-list li:nth-child(2) button').disabled`, 'B deletion awaiting confirmation')
    await evaluate(`document.querySelector('.material-list li:nth-child(2) button').click()`)
    await waitUntil(`document.querySelectorAll('[data-jd-version]').length === ${versionCount}
      && !document.querySelector('#jd-body')?.disabled`, 'A reopened with B deletion still pending')
    const pendingBody = '删除其他简历时保留的JD' + (recoverDelete ? 'V' : 'U').repeat(70)
    await evaluate(`(() => {
      const input = document.querySelector('#jd-body')
      input.value = ${JSON.stringify(pendingBody)}
      input.dispatchEvent(new Event('input', { bubbles: true }))
      window.p2Hooks.failVersionBody = true
      window.p2Hooks.failVersionLookup = true
      Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('确认并保存JD版本')).click()
    })()`)
    await waitUntil(`document.body.innerText.includes('暂时无法确认版本是否保存')
      && !Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('确认删除')).disabled`, 'A version save unresolved before deleting B')
    const originalOperation = await evaluate(`window.p2Hooks.versionOperations.at(-1)`)
    const postsBeforeDelete = await evaluate(`window.p2Hooks.versionOperations.length`)
    await evaluate(`window.p2Hooks.failDeleteBody = ${recoverDelete};
      window.p2Hooks.failDeleteLookup = ${recoverDelete};
      Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('确认删除')).click()`)
    if (recoverDelete) {
      await waitUntil(`document.body.innerText.includes('暂时无法确认删除结果')
        && !Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('核实删除状态')).disabled`, 'B deletion result initially unknown')
      const writesAfterAcceptedDelete = await evaluate(`window.p2Hooks.deleteWrites`)
      const recoveryLocation = await evaluate(`(() => {
        const button = Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '核实删除状态');
        const modal = document.querySelector('dialog[open]');
        return { reachable: !!button && (!modal || modal.contains(button)),
          visibleMessage: (modal ?? document.body).innerText.includes('暂时无法确认删除结果') }
      })()`)
      assert.equal(recoveryLocation.reachable, true, 'unknown deletion recovery must be reachable from the active modal')
      assert.equal(recoveryLocation.visibleMessage, true, 'unknown deletion feedback must be visible in the active interaction surface')
      async function clickDeletionRecovery() {
        const point = await evaluate(`(() => {
          const button = Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '核实删除状态');
          button.scrollIntoView({ block: 'center' });
          const r = button.getBoundingClientRect();
          const x = r.left + r.width / 2, y = r.top + r.height / 2;
          return { x, y, hit: document.elementFromPoint(x, y)?.closest('button') === button }
        })()`)
        assert.equal(point.hit, true, 'native pointer must hit the recovery button without closing the modal manually')
        await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1 }, sessionId)
        await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1 }, sessionId)
      }
      await clickDeletionRecovery()
      await waitUntil(`document.body.innerText.includes('暂时无法核实删除状态') &&
        !!Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '核实删除状态' && !b.disabled)`, 'failed deletion lookup preserves recovery')
      assert.equal(await evaluate(`window.p2Hooks.deleteWrites`), writesAfterAcceptedDelete, 'lookup failure must not reissue DELETE')
      await evaluate(`window.p2Hooks.failDeleteLookup = false`)
      await clickDeletionRecovery()
      await waitUntil(`!document.querySelector('dialog[open]') && document.body.innerText.includes('已核实删除状态')`, 'lost deletion response reconciled without another DELETE')
      assert.equal(await evaluate(`window.p2Hooks.deleteWrites`), writesAfterAcceptedDelete, 'successful verification must only query the original deletion')
    }
    await waitUntil(`document.querySelectorAll('.material-list li').length === ${listCount}
      && !Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('刷新列表')).disabled`, 'B deletion completed')
    assert.equal(await evaluate(`document.querySelector('#jd-body')?.value`), pendingBody,
      'deleting B must preserve the unresolved JD body belonging to A')
    assert.equal(await evaluate(`!!Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('核实版本保存结果'))`), true)
    assert.equal(await evaluate(`!!Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('沿用原操作重试版本'))`), true)
    assert.equal(await evaluate(`window.p2Hooks.versionOperations.length`), postsBeforeDelete)
    const recoveryAction = recoverDelete ? '沿用原操作重试版本' : '核实版本保存结果'
    await evaluate(`window.p2Hooks.failVersionLookup = false;
      Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes(${JSON.stringify(recoveryAction)})).click()`)
    await waitUntil(`document.querySelectorAll('[data-jd-version]').length === ${versionCount + 1}
      && !document.querySelector('#jd-body')?.disabled`, 'A recovered using the original version operation')
    assert.equal(await evaluate(`window.p2Hooks.versionOperations.at(-1)`), originalOperation)
    assert.equal(await evaluate(`window.p2Hooks.versionOperations.length`), postsBeforeDelete + (recoverDelete ? 1 : 0))
    assert.equal(await evaluate(`document.querySelector('[aria-label="JD详情"] pre')?.textContent`), pendingBody)
  }
  const jdCountAfterUnrelatedDeletes = await evaluate(`document.querySelectorAll('[data-jd-version]').length`)
  await evaluate(`Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('退出登录')).click()`)
  await login()
  await waitUntil(`!!document.querySelector('.material-list li')`, 'resume history after re-login')
  await evaluate(`document.querySelector('.material-list li:first-child button').click()`)
  await waitUntil(`document.body.innerText.includes(${JSON.stringify(synthetic)})`, 'saved resume detail')
  await waitUntil(`document.querySelectorAll('[data-jd-version]').length === ${jdCountAfterUnrelatedDeletes}`, 'JD history after re-login')
  const beforeLateDeleteCount = await evaluate(`document.querySelectorAll('.material-list li').length`)
  await evaluate(`(() => {
    window.p2Hooks.holdNextList = true
    window.p2Hooks.releaseList = null
    window.p2Hooks.listReleaseApplied = false
    Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('刷新列表')).click()
  })()`)
  await waitUntil(`!!window.p2Hooks.releaseList`, 'older list snapshot before delete')
  await evaluate(`Array.from(document.querySelectorAll('.material-list li:first-child button'))
    .find(button => button.textContent.includes('删除整份')).click()`)
  await waitUntil(`document.body.innerText.includes('确认删除整份简历')`, 'deletion confirmation')
  assert.equal(await evaluate(`document.body.innerText.includes('当前的 2 个正文版本和 ${jdCountAfterUnrelatedDeletes} 个JD版本')`), true)
  const beforeDeleteCsrf = await evaluate(`({ me: window.p2Hooks.meCalls,
    csrf: window.p2Hooks.csrfCalls })`)
  await evaluate(`(() => {
    window.p2Hooks.invalidateNextDelete = true
    Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('确认删除')).click()
  })()`)
  await waitUntil(`window.p2Hooks.meCalls > ${beforeDeleteCsrf.me}
    && document.body.innerText.includes('确认删除整份简历')
    && !Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('确认删除')).disabled`,
    'delete CSRF rejection followed by identity verification')
  await evaluate(`Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('确认删除')).click()`)
  await waitUntil(`document.body.innerText.includes('简历及当前正文已删除')
    && document.querySelectorAll('.material-list li').length === ${beforeLateDeleteCount - 1}`,
    'deleted resume and refreshed list')
  await evaluate(`window.p2Hooks.releaseList()`)
  await waitUntil(`window.p2Hooks.listReleaseApplied`, 'older list response after delete')
  await delay(100)
  assert.equal(await evaluate(`document.querySelectorAll('.material-list li').length`),
    beforeLateDeleteCount - 1, 'older list response must not restore the deleted resume')
  assert.equal(await evaluate(`window.p2Hooks.csrfCalls > ${beforeDeleteCsrf.csrf}`), true)
  assert.equal(await evaluate(`document.body.innerText.includes(${JSON.stringify(synthetic)})`), false)
  const reloadPath = await evaluate(`location.pathname`)
  await evaluate(`window.p2BeforeReload = true`)
  await send('Page.reload', {}, sessionId)
  await waitUntil(`!window.p2BeforeReload && location.pathname === ${JSON.stringify(reloadPath)} && !!document.querySelector('#resume-body') && !document.querySelector('#resume-body').disabled`, 'workspace reload retains selected address')
  assert.equal(await evaluate(`document.body.innerText.includes(${JSON.stringify(synthetic)})`), false)

  const raceBody = '账号切换期间的合成草稿' + 'R'.repeat(110)
  await evaluate(`(() => {
    const original = window.fetch.bind(window)
    window.p2Race = { releaseCsrf: null, writeCount: 0 }
    window.fetch = (input, init) => {
      const pathname = new URL(input instanceof Request ? input.url : input, location.href).pathname
      if (pathname === '/api/auth/csrf') {
        return new Promise(resolve => { window.p2Race.releaseCsrf = () => original(input, init).then(resolve) })
      }
      if (pathname === '/api/materials/resumes' && init?.method === 'POST') {
        window.p2Race.writeCount += 1
      }
      return original(input, init)
    }
    const input = document.querySelector('#resume-body')
    input.value = ${JSON.stringify(raceBody)}
    input.dispatchEvent(new Event('input', { bubbles: true }))
    Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('确认并保存简历')).click()
  })()`)
  await waitUntil(`!!window.p2Race.releaseCsrf`, 'A save waiting for CSRF')
  const { targetId: otherTarget } = await send('Target.createTarget', { url: `${base}/app` })
  const { sessionId: otherSession } = await send('Target.attachToTarget', {
    targetId: otherTarget, flatten: true,
  })
  await waitUntil(`location.pathname === '/app' && !!document.querySelector('#resume-body')`,
    'second tab with A session', otherSession)
  await evaluate(`Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('退出登录')).click()`, otherSession)
  await waitUntil(`location.pathname === '/login' && !!document.querySelector('#login-name')`,
    'second tab logged out A', otherSession)
  await login(otherUser, otherPassword, otherSession)
  await evaluate(`window.p2Race.releaseCsrf()`)
  await waitUntil(`window.p2Race.writeCount === 0 && document.querySelector('#resume-body')`,
    'old A save rejected after B login')
  assert.equal(await evaluate(`document.body.innerText.includes(${JSON.stringify(raceBody)})`), false)
  // P2-DT-03：真实文件选择、解析核对、同账号切窗、确认正文流丢失后沿用原操作。
  // 前一案例刻意挂起所有 CSRF 请求，刷新隔离该故障夹具，避免污染后续上传。
  await send('Page.reload', {}, sessionId)
  const uploadFixture = process.env.P2_UPLOAD_FIXTURE
  assert.ok(uploadFixture && fs.existsSync(uploadFixture), 'Synthetic DOCX fixture is required')
  await waitUntil(`!!document.querySelector('input[type=file]') && !document.querySelector('input[type=file]').disabled`, 'upload input')
  const { root: dom } = await send('DOM.getDocument', {}, sessionId)
  const { nodeId: fileNode } = await send('DOM.querySelector', { nodeId: dom.nodeId, selector: 'input[type=file]' }, sessionId)
  await send('DOM.setFileInputFiles', { nodeId: fileNode, files: [uploadFixture] }, sessionId)
  await waitUntil(`document.body.innerText.includes('文件：synthetic.docx')`, 'durable upload')
  for (let n = 0; n < 60; n += 1) {
    await evaluate(`Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '核实上传与解析状态').click()`)
    await delay(200)
    if (await evaluate(`!!document.querySelector('[data-upload-state="READY"]')`)) break
  }
  await waitUntil(`!!document.querySelector('[data-upload-state="READY"]')`, 'parsed DOCX')
  await send('Page.reload', {}, sessionId)
  await waitUntil(`!!Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '查找未完成上传')`, 'upload recovery after reload')
  await evaluate(`Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '查找未完成上传').click()`)
  await waitUntil(`!!Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '恢复核对或放弃' && !b.disabled)`, 'persisted upload draft')
  await evaluate(`Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '恢复核对或放弃').click()`)
  assert.equal(await evaluate(`document.querySelector('[aria-label="文件简历上传"] textarea').value.includes('Browser synthetic resume')`), true)
  await evaluate(`(() => {
    const text = document.querySelector('[aria-label="文件简历上传"] textarea')
    text.value = 'Verified browser upload ' + 'U'.repeat(120)
    text.dispatchEvent(new Event('input', { bubbles: true }))
    window.dispatchEvent(new Event('focus'))
  })()`)
  await waitUntil(`!!document.querySelector('[aria-label="文件简历上传"] textarea')`, 'same account restores upload draft')
  assert.equal(await evaluate(`document.querySelector('[aria-label="文件简历上传"] textarea').value.startsWith('Verified browser upload')`), true)
  await evaluate(`(() => {
    const original = window.fetch.bind(window)
    let lose = true
    window.uploadProof = { calls: 0, status: null }
    window.fetch = async (...args) => {
      const response = await original(...args)
      if (String(args[0]).endsWith('/confirmation')) { window.uploadProof.calls++; window.uploadProof.status = response.status }
      if (lose && String(args[0]).endsWith('/confirmation') && response.ok) {
        lose = false
        return new Response(new ReadableStream({ start(controller) { controller.error(new TypeError('synthetic body loss')) } }), { status: 200 })
      }
      return response
    }
    Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '确认正文并保存版本').click()
  })()`)
  await waitUntil(`!!Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '核实并沿用原确认操作' && !b.disabled)`, 'unknown confirmation retained').catch(async () => {
    throw new Error('Confirmation diagnostic: ' + JSON.stringify(await evaluate(`({ proof: window.uploadProof, path: location.pathname, buttons: Array.from(document.querySelectorAll('[aria-label="文件简历上传"] button')).map(b => ({label:b.textContent.trim(),disabled:b.disabled})) })`)))
  })
  await evaluate(`Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '核实并沿用原确认操作').click()`)
  await waitUntil(`!document.body.innerText.includes('文件：synthetic.docx') && document.body.innerText.includes('Verified browser upload')`, 'original confirmation reconciled')
  await waitUntil(`(async () => {
    if (!location.pathname.startsWith('/app/resumes/')) return false;
    const detail = await (await fetch('/api/materials/resumes/' + location.pathname.split('/').at(-1))).json();
    return detail.body.startsWith('Verified browser upload') && !!document.querySelector('#jd-body')
  })()`, 'confirmed upload address identifies accepted root')
  // 同一上传由另一窗口先确认时，原窗口只能打开固定版本，不能再次确认或丢失恢复出口。
  const { root: secondDom } = await send('DOM.getDocument', {}, sessionId)
  const { nodeId: secondInput } = await send('DOM.querySelector', { nodeId: secondDom.nodeId, selector: 'input[type=file]' }, sessionId)
  await send('DOM.setFileInputFiles', { nodeId: secondInput, files: [uploadFixture] }, sessionId)
  await waitUntil(`document.body.innerText.includes('文件：synthetic.docx')`, 'second upload')
  for (let n = 0; n < 60; n += 1) {
    await evaluate(`Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '核实上传与解析状态').click()`)
    await delay(200)
    if (await evaluate(`!!document.querySelector('[data-upload-state="READY"]')`)) break
  }
  assert.equal(await evaluate(`(async () => {
    const uploads = await (await fetch('/api/materials/uploads')).json()
    const upload = uploads.find(u => u.name === 'synthetic.docx' && u.state === 'READY')
    const me = await (await fetch('/api/auth/me')).json()
    const csrf = await (await fetch('/api/auth/csrf')).json()
    const response = await fetch('/api/materials/uploads/' + upload.uploadId + '/confirmation', { method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf.token,
        'X-Expected-Account-Id': me.accountId, 'X-Operation-Id': crypto.randomUUID() },
      body: JSON.stringify({ body: 'Confirmed in another window '.repeat(6) }) })
    return response.status
  })()`, otherSession), 200)
  await evaluate(`Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '核实上传与解析状态').click()`)
  await waitUntil(`!!Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '查看已确认版本并结束核对' && !b.disabled)`, 'other window confirmation outlet')
  await evaluate(`Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '查看已确认版本并结束核对').click()`)
  await waitUntil(`!document.body.innerText.includes('文件：synthetic.docx') && document.querySelector('[aria-label="简历详情"] pre')?.textContent.startsWith('Confirmed in another window')`, 'fixed version from other window')
  const failedFixture = process.env.P2_FAILED_UPLOAD_FIXTURE
  assert.ok(failedFixture && fs.existsSync(failedFixture), 'Synthetic malformed PDF is required')
  await waitUntil(`!!document.querySelector('#jd-body')`, 'JD draft before failed upload')
  await evaluate(`(() => {
    const input = document.querySelector('#jd-body'); input.value = 'Retained JD draft ' + 'J'.repeat(60)
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })()`)
  const { root: failedDom } = await send('DOM.getDocument', {}, sessionId)
  const { nodeId: failedInput } = await send('DOM.querySelector', { nodeId: failedDom.nodeId, selector: 'input[type=file]' }, sessionId)
  await send('DOM.setFileInputFiles', { nodeId: failedInput, files: [failedFixture] }, sessionId)
  await waitUntil(`document.body.innerText.includes('文件：broken.pdf')`, 'malformed upload registered')
  for (let n = 0; n < 60; n += 1) {
    await evaluate(`Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '核实上传与解析状态').click()`)
    await delay(200)
    if (await evaluate(`!!document.querySelector('[data-upload-state="FAILED"]')`)) break
  }
  await waitUntil(`!!document.querySelector('[data-upload-state="FAILED"]')`, 'malformed parser failure')
  await evaluate(`Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '放弃上传，改用粘贴正文').click()`)
  await waitUntil(`!document.body.innerText.includes('文件：broken.pdf') && !document.querySelector('#resume-body').disabled`, 'paste fallback enabled')
  assert.equal(await evaluate(`document.querySelector('#jd-body').value.startsWith('Retained JD draft')`), true)
  await evaluate(`(() => {
    const input = document.querySelector('#resume-body'); input.value = 'Pasted after parse failure ' + 'F'.repeat(120)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    Array.from(document.querySelectorAll('button')).find(b => b.textContent.includes('确认并保存简历')).click()
  })()`)
  await waitUntil(`document.querySelector('[aria-label="简历详情"] pre')?.textContent.startsWith('Pasted after parse failure') && !document.querySelector('#resume-body').disabled`, 'paste fallback saved')
  const savedPastePath = await evaluate(`location.pathname`)
  assert.match(savedPastePath, /^\/app\/resumes\/[0-9a-f-]{36}$/)
  await evaluate(`window.dispatchEvent(new Event('focus'))`)
  await waitUntil(`location.pathname === ${JSON.stringify(savedPastePath)} && document.querySelector('[aria-label="简历详情"] pre')?.textContent.startsWith('Pasted after parse failure') && !document.querySelector('#resume-body').disabled`, 'accepted paste root retained after focus')
  // P2-DT-04：精确草稿计数、离开取消和读取失败不冒充空数据。
  await evaluate(`(() => { const input = document.querySelector('#resume-body'); input.value = ${JSON.stringify(' \t😀\r\nX \t')}; input.dispatchEvent(new Event('input', { bubbles: true })) })()`)
  await waitUntil(`document.querySelector('[data-count="resume"]').textContent.includes('3 / 20,000')`, 'normalized draft count')
  acceptDialogs = false
  const beforeDialog = dialogCount
  await evaluate(`Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '退出登录').click()`)
  assert.ok(dialogCount > beforeDialog, 'dirty signout must ask before leaving')
  await waitUntil(`!!document.querySelector('#resume-body') && document.querySelector('#resume-body').value.includes('😀')`, 'same-account draft after cancel and focus identity check')
  assert.equal(await evaluate(`!!document.querySelector('#resume-body') && document.querySelector('#resume-body').value.includes('😀')`), true)
  await evaluate(`window.dirtyRefreshSentinel = true`)
  const beforeUnloadDialog = dialogCount
  await send('Page.reload', {}, sessionId)
  await delay(150)
  assert.equal(await evaluate(`window.dirtyRefreshSentinel === true`), true, 'cancel refresh preserves draft document')
  assert.ok(dialogCount > beforeUnloadDialog, 'dirty refresh must show a real beforeunload dialog')
  await waitUntil(`!!document.querySelector('#resume-body')`, 'identity restored after cancelled refresh')
  acceptDialogs = true
  await evaluate(`(() => { const input = document.querySelector('#resume-body'); input.value = 'X'.repeat(20001); input.dispatchEvent(new Event('input', { bubbles: true })) })()`)
  await waitUntil(`document.querySelector('[data-count="resume"]').textContent.includes('超出 1')`, 'untruncated over-limit count')
  assert.equal(await evaluate(`document.querySelector('#resume-body').value.length`), 20001)
  await evaluate(`(() => { const input = document.querySelector('#resume-body'); input.value = ''; input.dispatchEvent(new Event('input', { bubbles: true }));
    const prior = window.fetch; window.uiPriorFetch = prior; window.uiFailList = true;
    window.fetch = (input, init) => {
      if (window.uiFailList && new URL(input, location.href).pathname === '/api/materials/resumes' && !init?.method) throw new TypeError('synthetic list interruption')
      return prior(input, init)
    };
    Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '刷新列表').click()
  })()`)
  await waitUntil(`document.body.innerText.includes('简历列表加载失败')`, 'list failure is not empty')
  assert.equal(await evaluate(`document.body.innerText.includes('暂无已保存简历。')`), false)
  await evaluate(`window.uiFailList = false; Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '刷新列表').click()`)
  await waitUntil(`!document.body.innerText.includes('简历列表加载失败') && !!document.querySelector('.material-list li')`, 'list retry')
  await evaluate(`(() => { const prior = window.fetch; window.uiFailVersions = true;
    window.fetch = (input, init) => { const p = new URL(input, location.href).pathname;
      if (window.uiFailVersions && !init?.method && (p.endsWith('/versions') || p.endsWith('/jd-versions'))) throw new TypeError('synthetic version list interruption');
      return prior(input, init) };
    Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '刷新版本列表').click()
  })()`)
  await waitUntil(`document.body.innerText.includes('版本列表加载失败')`, 'version list failure')
  await evaluate(`window.uiFailVersions = false; Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '刷新版本列表').click()`)
  await waitUntil(`!document.body.innerText.includes('版本列表加载失败') && !!document.querySelector('[data-resume-version]')`, 'version list recovery')
  await waitUntil(`!document.body.innerText.includes('网络异常，暂时无法核实请求结果')`, 'recovered reads clear stale failure messages')
  const historyShot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true }, sessionId)
  fs.writeFileSync(path.resolve('../.cache/p2dt04-history.png'), Buffer.from(historyShot.data, 'base64'))

  // 固定地址进入后新增/切根，焦点身份核实不能恢复旧地址的目标。
  const navigationRoots = await evaluate(`(async () => (await (await fetch('/api/materials/resumes')).json()).slice(0, 2).map(r => r.resumeId))()`)
  assert.equal(navigationRoots.length, 2, 'navigation regression needs two private roots')
  for (const targetRoot of [null, navigationRoots[1]]) {
    await send('Page.navigate', { url: `${base}/app/resumes/${navigationRoots[0]}` }, sessionId)
    await waitUntil(`!!document.querySelector('#jd-body') && !document.querySelector('#resume-body').disabled`, 'navigation source ready')
    await evaluate(`(() => {
      window.navigationProof = { me: 0, uploads: [] }; const prior = window.fetch;
      window.fetch = (input, init) => {
        const p = new URL(input, location.href).pathname;
        if (p === '/api/auth/me') window.navigationProof.me++;
        if (p === '/api/materials/uploads' && init?.method === 'POST') window.navigationProof.uploads.push(init.body.get('resumeId'));
        return prior(input, init)
      };
      ${targetRoot === null
        ? `Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '新增简历').click()`
        : `Array.from(document.querySelectorAll('.material-list li')).find(li => li.querySelector('.resume-label').title === '${targetRoot}').querySelector('button').click()`}
    })()`)
    const targetPath = targetRoot === null ? '/app' : `/app/resumes/${targetRoot}`
    await waitUntil(`location.pathname === '${targetPath}' && !!document.querySelector('#resume-body') && !document.querySelector('#resume-body').disabled`, 'selected root address synchronized')
    const checksBeforeFocus = await evaluate(`window.navigationProof.me`)
    await evaluate(`window.dispatchEvent(new Event('focus'))`)
    await waitUntil(`window.navigationProof.me > ${checksBeforeFocus} && !!document.querySelector('#resume-body') && !document.querySelector('#resume-body').disabled`, 'navigation focus identity restored')
    assert.equal(await evaluate(`location.pathname`), targetPath)
    if (targetRoot === null) {
      assert.equal(await evaluate(`!!document.querySelector('#jd-body')`), false, 'new resume remains unselected')
    } else {
      const targetVersion = await evaluate(`(async () => (await (await fetch('/api/materials/resumes/${targetRoot}')).json()).versionId)()`)
      assert.equal(await evaluate(`document.querySelector('[aria-label="简历详情"]').textContent.includes('${targetVersion}')`), true, 'focus retains selected B')
      await evaluate(`(() => { const input = document.querySelector('#jd-body'); input.value = 'Retain B navigation draft'; input.dispatchEvent(new Event('input', { bubbles: true })) })()`)
      acceptDialogs = false
      const beforeCancel = dialogCount
      await evaluate(`Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '新增简历').click()`)
      await waitUntil(`!!document.querySelector('#jd-body')`, 'cancel navigation keeps editor')
      assert.equal(dialogCount, beforeCancel + 1, 'root change asks once')
      assert.equal(await evaluate(`location.pathname`), targetPath, 'cancel keeps B address')
      assert.equal(await evaluate(`document.querySelector('#jd-body').value`), 'Retain B navigation draft')
      acceptDialogs = true
      await evaluate(`(() => { const input = document.querySelector('#jd-body'); input.value = ''; input.dispatchEvent(new Event('input', { bubbles: true })) })()`)
    }
    const { root: navigationDom } = await send('DOM.getDocument', {}, sessionId)
    const { nodeId: navigationFile } = await send('DOM.querySelector', { nodeId: navigationDom.nodeId, selector: 'input[type=file]' }, sessionId)
    await send('DOM.setFileInputFiles', { nodeId: navigationFile, files: [uploadFixture] }, sessionId)
    await waitUntil(`window.navigationProof.uploads.length === 1 && !!document.querySelector('[data-upload-state]')`, 'navigation upload registered')
    assert.deepEqual(await evaluate(`window.navigationProof.uploads`), [targetRoot], 'upload uses new or selected B target after focus')
    await waitUntil(`!!Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '放弃上传，改用粘贴正文' && !b.disabled)`, 'navigation upload can be abandoned')
    await evaluate(`Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '放弃上传，改用粘贴正文').click()`)
    await waitUntil(`!document.body.innerText.includes('文件：synthetic.docx') && !document.querySelector('#resume-body').disabled`, 'navigation upload abandoned')
  }

  // 同根固定地址：未决版本经过焦点身份核实，详情重读失败也必须保留两种恢复入口。
  const focusRoot = await evaluate(`(async () => (await (await fetch('/api/materials/resumes')).json())[0].resumeId)()`)
  await send('Page.navigate', { url: `${base}/app/resumes/${focusRoot}` }, sessionId)
  await waitUntil(`!!document.querySelector('#jd-body') && !!document.querySelector('[data-resume-version]')`, 'fixed-address version editor')
  const initialJdCount = await evaluate(`(async () => (await (await fetch('/api/materials/resumes/${focusRoot}/jd-versions')).json()).length)()`)
  await evaluate(`(() => {
    const prior = window.fetch;
    window.focusNativeFetch = prior;
    window.focusCase = { holdMe: false, releaseMe: null, failDetail: false, detailFailures: 0,
      failLookup: true, dropNextBody: true, operations: [] };
    window.fetch = async (input, init) => {
      const p = new URL(input, location.href).pathname;
      if (p === '/api/auth/me' && window.focusCase.holdMe) return new Promise(resolve => {
        window.focusCase.releaseMe = () => prior(input, init).then(resolve)
      });
      if (p === '/api/materials/resumes/${focusRoot}' && !init?.method && window.focusCase.failDetail) {
        window.focusCase.detailFailures += 1;
        throw new TypeError('synthetic same-root detail interruption')
      }
      if (p.includes('/jd-operations/') && window.focusCase.failLookup) throw new TypeError('synthetic unresolved version lookup');
      if (p.endsWith('/jd-versions') && init?.method === 'POST') {
        window.focusCase.operations.push(init.headers['X-Operation-Id']);
        const response = await prior(input, init);
        if (response.ok && window.focusCase.dropNextBody) {
          window.focusCase.dropNextBody = false;
          return { ok: true, json: async () => { throw new TypeError('synthetic accepted version response loss') } }
        }
        return response
      }
      return prior(input, init)
    }
  })()`)
  for (const recovery of ['query', 'retry']) {
    const pendingBody = `Focus detail failure ${recovery} ` + 'J'.repeat(100)
    await evaluate(`(() => {
      window.focusCase.failLookup = true; window.focusCase.dropNextBody = true;
      const input = document.querySelector('#jd-body'); input.value = ${JSON.stringify(pendingBody)};
      input.dispatchEvent(new Event('input', { bubbles: true }));
      Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '确认并保存JD版本').click()
    })()`)
    await waitUntil(`document.body.innerText.includes('暂时无法确认版本是否保存') &&
      !!Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '核实版本保存结果' && !b.disabled)`, 'unresolved fixed-address JD')
    const originalOperation = await evaluate(`window.focusCase.operations.at(-1)`)
    const failureCount = await evaluate(`window.focusCase.detailFailures`)
    await evaluate(`window.focusCase.holdMe = true; window.focusCase.releaseMe = null;
      window.focusCase.failDetail = true; window.dispatchEvent(new Event('focus'))`)
    await waitUntil(`!!window.focusCase.releaseMe && !document.querySelector('#jd-body')`, 'private editor hidden during focus identity check')
    await evaluate(`window.focusCase.holdMe = false; window.focusCase.releaseMe()`)
    await waitUntil(`window.focusCase.detailFailures > ${failureCount} &&
      !!Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '核实版本保存结果' && !b.disabled) &&
      !!Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '沿用原操作重试版本' && !b.disabled)`, 'pending version recovery remains reachable after same-root detail failure')
    assert.equal(await evaluate(`document.querySelector('#jd-body').value`), pendingBody)
    assert.equal(await evaluate(`!!document.querySelector('[aria-label="简历详情"] pre')`), true, 'same-root saved detail remains available')
    const writesBeforeRecovery = await evaluate(`window.focusCase.operations.length`)
    const action = recovery === 'query' ? '核实版本保存结果' : '沿用原操作重试版本'
    await evaluate(`window.focusCase.failLookup = false;
      Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === ${JSON.stringify(action)}).click()`)
    await waitUntil(`document.querySelector('[aria-label="JD详情"] pre')?.textContent === ${JSON.stringify(pendingBody)} &&
      !Array.from(document.querySelectorAll('button')).some(b => b.textContent.trim() === '核实版本保存结果')`, 'fixed-address original version reconciled')
    assert.equal(await evaluate(`window.focusCase.operations.length`), writesBeforeRecovery + (recovery === 'retry' ? 1 : 0))
    assert.equal(await evaluate(`window.focusCase.operations.at(-1)`), originalOperation, 'recovery must retain the original operation identity')
  }
  const recoveredVersions = await evaluate(`(async () => (await (await fetch('/api/materials/resumes/${focusRoot}/jd-versions')).json()).map(v => v.versionId))()`)
  assert.equal(recoveredVersions.length, initialJdCount + 2, 'two logical saves create exactly two versions')
  assert.equal(new Set(recoveredVersions).size, recoveredVersions.length)
  await evaluate(`window.fetch = window.focusNativeFetch`)

  // 未决 JD 已提交但回包/核实失败；另一窗口删除原根后，用本人删除回执终止旧入口。
  const deletedVersionRoot = await evaluate(`(async () => (await (await fetch('/api/materials/resumes')).json())[0].resumeId)()`)
  await evaluate(`(() => { const prior = window.fetch; window.uiVersionLookupFails = true;
    window.fetch = async (input, init) => {
      const p = new URL(input, location.href).pathname;
      if (window.uiVersionLookupFails && p.includes('/jd-operations/')) throw new TypeError('synthetic lost lookup');
      const response = await prior(input, init);
      if (init?.method === 'POST' && p.endsWith('/jd-versions') && response.ok) return { ok: true, json: async () => { throw new TypeError('synthetic lost body') } };
      return response
    };
    const input = document.querySelector('#jd-body'); input.value = 'DT04 unknown JD '.repeat(6); input.dispatchEvent(new Event('input', { bubbles: true }));
    Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '确认并保存JD版本').click()
  })()`)
  await waitUntil(`document.body.innerText.includes('暂时无法确认版本是否保存')`, 'unknown JD before remote delete')
  assert.equal(await evaluate(`(async () => { const me = await (await fetch('/api/auth/me')).json(); const csrf = await (await fetch('/api/auth/csrf')).json();
    return (await fetch('/api/materials/resumes/${deletedVersionRoot}', { method: 'DELETE', headers: { 'X-CSRF-TOKEN': csrf.token, 'X-Expected-Account-Id': me.accountId } })).status
  })()`, otherSession), 200)
  await evaluate(`window.uiVersionLookupFails = false; Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === '核实版本保存结果').click()`)
  await waitUntil(`!document.querySelector('#jd-body') && !!document.querySelector('[data-cleanup-state]')`, 'remote deletion terminates pending version')
  assert.equal(await evaluate(`document.body.innerText.includes('沿用原操作重试版本')`), false)

  await send('Page.navigate', { url: `${base}/app/resumes/${accountARoot}` }, sessionId)
  await waitUntil(`document.body.innerText.includes('不存在或无权访问此记录')`, 'other-account direct navigation')
  assert.equal(await evaluate(`document.body.innerText.includes('DT04 private A direct fixture')`), false)

  // 真实目录阻碍保持 PENDING；界面仅展示服务端回执，不伪造具体故障类别。
  const cleanupRoot = process.env.P2_CLEANUP_RESUME
  const blocking = process.env.P2_CLEANUP_BLOCK
  assert.ok(cleanupRoot && blocking)
  assert.ok(path.resolve(blocking).startsWith(path.resolve('../.cache') + path.sep), 'cleanup fixture must stay inside project cache')
  await send('Page.navigate', { url: `${base}/app/resumes/${cleanupRoot}` }, sessionId)
  await waitUntil(`!!document.querySelector('[data-delete-selected]') && !document.querySelector('[data-delete-selected]').disabled`, 'cleanup fixture direct view')
  await evaluate(`(() => { const prior = window.fetch; window.uiMissingCount = true;
    window.fetch = async (input, init) => { const response = await prior(input, init);
      if (window.uiMissingCount && new URL(input, location.href).pathname.endsWith('/deletion-impact') && response.ok) {
        const body = await response.json(); delete body.fileCount; return { ok: true, json: async () => body }
      }
      return response
    };
    document.querySelector('[data-delete-selected]').click()
  })()`)
  await waitUntil(`document.body.innerText.includes('删除范围响应格式无法识别')`, 'missing count does not default to zero')
  assert.equal(await evaluate(`!!document.querySelector('[aria-label="确认删除简历"]')`), false)
  await evaluate(`window.uiMissingCount = false`)
  await evaluate(`document.querySelector('[data-delete-selected]').click()`)
  await waitUntil(`!!document.querySelector('[aria-label="确认删除简历"]')`, 'real deletion impact')
  assert.equal(await evaluate(`document.querySelector('[aria-label="确认删除简历"]').textContent.includes('1 份原文件/暂存')`), true)
  await evaluate(`Array.from(document.querySelectorAll('[aria-label="确认删除简历"] button')).find(b => b.textContent.trim() === '取消').click()`)
  assert.equal(await evaluate(`(async () => (await fetch('/api/materials/resumes/${cleanupRoot}')).status)()`), 200)
  await evaluate(`document.querySelector('[data-delete-selected]').click()`)
  await waitUntil(`!!document.querySelector('[aria-label="确认删除简历"]')`, 'second delete confirmation')
  await evaluate(`Array.from(document.querySelectorAll('[aria-label="确认删除简历"] button')).find(b => b.textContent.trim() === '确认删除').click()`)
  await waitUntil(`!!document.querySelector('[data-cleanup-state="PENDING"]') && !document.querySelector('[aria-label="简历详情"]')`, 'pending cleanup prohibits access')
  assert.equal(await evaluate(`(async () => (await fetch('/api/materials/resumes/${cleanupRoot}')).status)()`), 404)
  assert.equal(await evaluate(`Array.from(document.querySelectorAll('button')).some(b => b.textContent.includes('恢复删除'))`), false)
  await send('Page.reload', {}, sessionId)
  await waitUntil(`!!document.querySelector('[data-cleanup-state="PENDING"]')`, 'pending cleanup survives reload through server receipt')
  await evaluate(`(() => { const prior = window.fetch; window.cleanupFetch = prior;
    window.fetch = (input, init) => { if (new URL(input, location.href).pathname.endsWith('/deletion')) throw new TypeError('synthetic cleanup lookup interruption'); return prior(input, init) };
    document.querySelector('[data-cleanup-state="PENDING"] button').click()
  })()`)
  await waitUntil(`document.body.innerText.includes('保留上次回执')`, 'cleanup lookup failure preserves pending receipt')
  assert.equal(await evaluate(`!!document.querySelector('[data-cleanup-state="PENDING"]')`), true)
  await evaluate(`window.fetch = window.cleanupFetch`)
  const pendingShot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true }, sessionId)
  fs.writeFileSync(path.resolve('../.cache/p2dt04-cleanup-pending.png'), Buffer.from(pendingShot.data, 'base64'))
  fs.unlinkSync(path.join(blocking, 'sentinel'))
  try { fs.rmdirSync(blocking) } catch (error) { if (error.code !== 'ENOENT') throw error }
  // 已确认文件仍保留解析截止＋5秒退出保护；观察窗口覆盖完整35秒，不改业务期限。
  for (let n = 0; n < 300; n += 1) {
    await evaluate(`document.querySelector('[data-cleanup-state="PENDING"] button')?.click()`)
    if (await evaluate(`!!document.querySelector('[data-cleanup-state="CLEANED"]') && !document.querySelector('[data-cleanup-state="PENDING"]')`)) break
    await delay(150)
  }
  await waitUntil(`!document.querySelector('[data-cleanup-state="PENDING"]') && !!document.querySelector('[data-cleanup-state="CLEANED"]')`, 'cleanup completion receipt')
  assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true, '1280px desktop must not overflow horizontally')
  process.stdout.write('P2-DT-01 real Chrome material checks passed.\nP2-DT-03 real Chrome upload checks passed.\nP2-DT-04 real Chrome page checks passed.\n')
} finally {
  socket?.close()
  browser.kill()
  if (browser.exitCode === null) await Promise.race([once(browser, 'exit'), delay(5000)])
  const resolvedProfile = fs.realpathSync(profile)
  if (
    path.dirname(resolvedProfile) !== profileRoot ||
    !path.basename(resolvedProfile).startsWith('p2-dt01-chrome-')
  ) {
    throw new Error('Refusing to remove a browser profile outside the P2-DT-01 test directory')
  }
  fs.rmSync(resolvedProfile, { recursive: true, force: true, maxRetries: 20, retryDelay: 200 })
}
