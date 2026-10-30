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
assert.ok(base && username && password, 'Browser verification inputs are required')
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
  async function evaluate(expression) {
    const result = await send(
      'Runtime.evaluate',
      { expression, awaitPromise: true, returnByValue: true },
      sessionId,
    )
    if (result.exceptionDetails) throw new Error('Browser page script failed')
    return result.result.value
  }
  async function waitUntil(expression, label) {
    for (let i = 0; i < 100; i += 1) {
      try {
        if (await evaluate(expression)) return
      } catch {
        // Navigation may replace the JavaScript execution context.
      }
      await delay(100)
    }
    throw new Error(`Timed out waiting for ${label}`)
  }
  async function login() {
    await waitUntil(`location.pathname === '/login' && !!document.querySelector('#login-name')`, 'login page')
    await evaluate(`(() => {
      const name = document.querySelector('#login-name')
      const secret = document.querySelector('#login-password')
      name.value = ${JSON.stringify(username)}
      secret.value = ${JSON.stringify(password)}
      name.dispatchEvent(new Event('input', { bubbles: true }))
      secret.dispatchEvent(new Event('input', { bubbles: true }))
      document.querySelector('form').requestSubmit()
    })()`)
    await waitUntil(`location.pathname === '/app' && !!document.querySelector('#resume-body')`, 'resume workspace')
  }

  await login()
  const unsaved = '切窗未保存草稿' + 'U'.repeat(105)
  await evaluate(`(() => {
    const nativeFetch = window.fetch.bind(window)
    window.p2Hooks = { holdMe: false, releaseMe: null, failPostBody: false,
      failLookup: false, invalidateNextPost: false, invalidateNextDelete: false,
      meCalls: 0, csrfCalls: 0,
      postOperations: [] }
    window.fetch = async (input, init) => {
      const pathname = new URL(input instanceof Request ? input.url : input, location.href).pathname
      if (pathname === '/api/auth/me') window.p2Hooks.meCalls += 1
      if (pathname === '/api/auth/csrf') window.p2Hooks.csrfCalls += 1
      if (pathname === '/api/auth/me' && window.p2Hooks.holdMe) {
        return new Promise(resolve => {
          window.p2Hooks.releaseMe = () => nativeFetch(input, init).then(resolve)
        })
      }
      if (pathname.startsWith('/api/materials/resumes/operations/') && window.p2Hooks.failLookup) {
        throw new TypeError('synthetic operation lookup interruption')
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

  const synthetic = '浏览器合成简历' + 'A'.repeat(110)
  await evaluate(`(() => {
    const input = document.querySelector('#resume-body')
    input.value = ${JSON.stringify(synthetic)}
    input.dispatchEvent(new Event('input', { bubbles: true }))
    Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('确认并保存简历')).click()
  })()`)
  await waitUntil(`document.body.innerText.includes('简历正文已保存为第 1 版')`, 'saved resume')
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
  assert.equal(await evaluate(`window.p2Hooks.postOperations[1] === window.p2Hooks.postOperations[2]`), true)
  await evaluate(`Array.from(document.querySelectorAll('.material-list li:first-child button'))
    .find(button => button.textContent.includes('删除整份')).click()`)
  await waitUntil(`document.body.innerText.includes('确认删除整份简历')`, 'unknown-case cleanup confirmation')
  await evaluate(`Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('确认删除')).click()`)
  await waitUntil(`document.querySelectorAll('.material-list li').length === ${beforeUnknownCount}`,
    'unknown-case cleanup')
  await evaluate(`Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('退出登录')).click()`)
  await login()
  await waitUntil(`!!document.querySelector('.material-list li')`, 'resume history after re-login')
  await evaluate(`document.querySelector('.material-list li:first-child button').click()`)
  await waitUntil(`document.body.innerText.includes(${JSON.stringify(synthetic)})`, 'saved resume detail')
  await evaluate(`Array.from(document.querySelectorAll('.material-list li:first-child button'))
    .find(button => button.textContent.includes('删除整份')).click()`)
  await waitUntil(`document.body.innerText.includes('确认删除整份简历')`, 'deletion confirmation')
  assert.equal(await evaluate(`document.body.innerText.includes('当前的 1 个正文版本')`), true)
  const beforeDeleteCsrf = await evaluate(`({ me: window.p2Hooks.meCalls,
    csrf: window.p2Hooks.csrfCalls })`)
  await evaluate(`(() => {
    window.p2Hooks.invalidateNextDelete = true
    Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('确认删除')).click()
  })()`)
  await waitUntil(`window.p2Hooks.meCalls > ${beforeDeleteCsrf.me}
    && document.body.innerText.includes('确认删除整份简历')`,
    'delete CSRF rejection followed by identity verification')
  await evaluate(`Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('确认删除')).click()`)
  await waitUntil(`document.body.innerText.includes('简历及当前正文已删除')`, 'deleted resume')
  assert.equal(await evaluate(`window.p2Hooks.csrfCalls > ${beforeDeleteCsrf.csrf}`), true)
  assert.equal(await evaluate(`document.body.innerText.includes(${JSON.stringify(synthetic)})`), false)
  await send('Page.reload', {}, sessionId)
  await waitUntil(`location.pathname === '/app' && !!document.querySelector('#resume-body')`, 'workspace reload')
  assert.equal(await evaluate(`document.body.innerText.includes(${JSON.stringify(synthetic)})`), false)
  process.stdout.write('P2-DT-01 real Chrome material checks passed.\n')
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
