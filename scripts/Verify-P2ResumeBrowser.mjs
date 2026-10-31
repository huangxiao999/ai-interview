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
  const pending = new Map()
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
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
  async function evaluate(expression, targetSession = sessionId) {
    const result = await send(
      'Runtime.evaluate',
      { expression, awaitPromise: true, returnByValue: true },
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
      failDeleteBody: false, failDeleteLookup: false }
    window.fetch = async (input, init) => {
      const pathname = new URL(input instanceof Request ? input.url : input, location.href).pathname
      if (init?.method === 'POST' && (pathname.endsWith('/versions') || pathname.endsWith('/jd-versions'))) {
        window.p2Hooks.versionWriteAttempts += 1
      }
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
      await evaluate(`window.p2Hooks.failDeleteLookup = false;
        Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('核实删除状态')).click()`)
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
  await evaluate(`window.p2BeforeReload = true`)
  await send('Page.reload', {}, sessionId)
  await waitUntil(`!window.p2BeforeReload && location.pathname === '/app' && !!document.querySelector('#resume-body')`, 'workspace reload')
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
  await waitUntil(`document.querySelector('[aria-label="简历详情"] pre')?.textContent.startsWith('Pasted after parse failure')`, 'paste fallback saved')
  process.stdout.write('P2-DT-01 real Chrome material checks passed.\nP2-DT-03 real Chrome upload checks passed.\n')
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
