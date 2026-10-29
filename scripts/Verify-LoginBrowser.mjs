// Exercises the actual Vue login page, Vite proxy and backend session in real Chrome.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import fs from 'node:fs'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

const chrome = process.env.DT05_CHROME_PATH ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const base = process.env.DT05_FRONTEND_BASE
const username = process.env.DT05_BROWSER_USER
const password = process.env.DT05_BROWSER_PASSWORD
const accountId = process.env.DT05_BROWSER_ACCOUNT_ID
assert.ok(base && username && password && accountId, 'Browser verification inputs are required')
assert.ok(fs.existsSync(chrome), 'Chrome executable is required for real-browser verification')

const profileRoot = fs.realpathSync(path.resolve('../.cache/tmp'))
const profile = fs.mkdtempSync(path.join(profileRoot, 'dt05-chrome-'))
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
  let failedIdentityCheck = false
  let pausedAction
  let holdNextLogin = false
  let pausedLogin
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (message.method === 'Fetch.requestPaused') {
      if (holdNextLogin && message.params.request.url.endsWith('/api/auth/login')) {
        holdNextLogin = false
        pausedLogin = { requestId: message.params.requestId, sessionId: message.sessionId }
      } else if (!failedIdentityCheck && message.params.request.url.endsWith('/api/auth/me')) {
        failedIdentityCheck = true
        pausedAction = send(
          'Fetch.failRequest',
          { requestId: message.params.requestId, errorReason: 'Failed' },
          message.sessionId,
        )
      } else {
        pausedAction = send(
          'Fetch.continueRequest',
          { requestId: message.params.requestId },
          message.sessionId,
        )
      }
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

  await waitUntil(`location.pathname === '/login' && !!document.querySelector('#login-name')`, 'anonymous login page')
  assert.equal(await evaluate(`document.body.innerText.includes('不开放注册')`), true)
  assert.equal(await evaluate(`document.body.innerText.includes('模拟记录')`), false)

  async function fill(name, secret) {
    await evaluate(`(() => {
      const nameInput = document.querySelector('#login-name')
      const passwordInput = document.querySelector('#login-password')
      nameInput.value = ${JSON.stringify(name)}
      passwordInput.value = ${JSON.stringify(secret)}
      nameInput.dispatchEvent(new Event('input', { bubbles: true }))
      passwordInput.dispatchEvent(new Event('input', { bubbles: true }))
      document.querySelector('form').requestSubmit()
    })()`)
  }

  await fill('', password)
  await waitUntil(`document.body.innerText.includes('账号须为 3～64 位')`, 'empty username validation')
  await fill(username, '')
  await waitUntil(`document.body.innerText.includes('请输入密码')`, 'empty password validation')
  await fill(` ${username}`, password)
  await waitUntil(`document.body.innerText.includes('账号须为 3～64 位')`, 'raw username validation')
  assert.equal(await evaluate(`location.pathname`), '/login')
  await fill(username, 'wrong-synthetic-password')
  await waitUntil(`document.body.innerText.includes('账号或密码不正确')`, 'incorrect credential feedback')
  assert.equal(await evaluate(`location.pathname`), '/login')

  const beforeLoginCsrf = await evaluate(`(async () => (await (await fetch('/api/auth/csrf')).json()).token)()`)
  holdNextLogin = true
  await send('Fetch.enable', { patterns: [{ urlPattern: '*api/auth/login', requestStage: 'Request' }] }, sessionId)
  await fill(username, password)
  for (let i = 0; i < 100 && !pausedLogin; i += 1) await delay(100)
  assert.ok(pausedLogin, 'Correct login was not intercepted for pending-state check')
  assert.equal(await evaluate(`document.querySelector('button[type=submit]').disabled`), true)
  assert.equal(await evaluate(`document.body.innerText.includes('正在登录')`), true)
  await send('Fetch.continueRequest', { requestId: pausedLogin.requestId }, pausedLogin.sessionId)
  await send('Fetch.disable', {}, sessionId)
  await waitUntil(`location.pathname === '/app' && document.body.innerText.includes('已登录')`, 'authenticated workspace')
  assert.equal(await evaluate(`document.body.innerText.includes(${JSON.stringify(username)})`), true)
  assert.equal(await evaluate(`document.cookie.includes('SESSION=')`), false)
  assert.equal(await evaluate(`document.body.innerText.includes('尚未开发')`), true)
  const afterLoginCsrf = await evaluate(`(async () => (await (await fetch('/api/auth/csrf')).json()).token)()`)
  assert.notEqual(afterLoginCsrf, beforeLoginCsrf)
  assert.equal(
    await evaluate(`(async () => (await (await fetch('/api/auth/me')).json()).accountId)()`),
    accountId,
  )

  await send('Fetch.enable', { patterns: [{ urlPattern: '*api/auth/me', requestStage: 'Request' }] }, sessionId)
  await send('Page.reload', {}, sessionId)
  await waitUntil(`document.body.innerText.includes('暂时无法核实登录状态')`, 'unknown identity state')
  assert.equal(failedIdentityCheck, true)
  await pausedAction
  await send('Fetch.disable', {}, sessionId)
  await evaluate(`document.querySelector('button').click()`)
  await waitUntil(`document.body.innerText.includes('已登录')`, 'identity retry')

  await evaluate(`Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('退出登录')).click()`)
  await waitUntil(`location.pathname === '/login' && !!document.querySelector('#login-name')`, 'logout redirect')
  assert.equal(await evaluate(`(async () => (await fetch('/api/auth/me')).status)()`), 401)
  assert.equal(await evaluate(`document.body.innerText.includes(${JSON.stringify(username)})`), false)

  // The real login request completes and sets the browser Cookie, then only its readable body fails.
  await evaluate(`(() => {
    const nativeFetch = window.fetch.bind(window)
    window.loginBodyFailure = { kind: '', loginPosts: 0, meAfterFailure: 0, injected: false }
    window.fetch = async (input, init) => {
      const pathname = new URL(input instanceof Request ? input.url : input, location.href).pathname
      if (pathname === '/api/auth/login') window.loginBodyFailure.loginPosts += 1
      if (pathname === '/api/auth/me' && window.loginBodyFailure.injected) {
        window.loginBodyFailure.meAfterFailure += 1
      }
      const response = await nativeFetch(input, init)
      if (pathname === '/api/auth/login' && response.status === 200 && window.loginBodyFailure.kind) {
        const kind = window.loginBodyFailure.kind
        window.loginBodyFailure.kind = ''
        window.loginBodyFailure.injected = true
        return new Response(new ReadableStream({
          start(controller) {
            controller.error(kind === 'syntax' ? new SyntaxError('synthetic truncated body')
              : new TypeError('synthetic interrupted body'))
          }
        }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return response
    }
  })()`)

  for (const kind of ['type', 'syntax']) {
    await evaluate(`(() => { window.loginBodyFailure.kind = ${JSON.stringify(kind)};
      window.loginBodyFailure.loginPosts = 0;
      window.loginBodyFailure.meAfterFailure = 0;
      window.loginBodyFailure.injected = false })()`)
    await fill(username, password)
    await waitUntil(
      `location.pathname === '/app' || document.body.innerText.includes('暂时无法核实登录状态')`,
      `${kind} login body outcome`,
    )
    const result = await evaluate(`({ path: location.pathname, ...window.loginBodyFailure })`)
    assert.equal(result.injected, true, `${kind}: expected a 200 login response with failed body`)
    assert.equal(result.loginPosts, 1, `${kind}: login must not be submitted twice`)
    assert.ok(result.meAfterFailure >= 1, `${kind}: login result must be checked with me`)
    assert.equal(result.path, '/app', `${kind}: confirmed login must open the workspace`)
    await evaluate(`Array.from(document.querySelectorAll('button')).find(button => button.textContent.includes('退出登录')).click()`)
    await waitUntil(`location.pathname === '/login' && !!document.querySelector('#login-name')`, `${kind} logout`)
  }

  process.stdout.write('DT-05 real Chrome login frontend checks passed.\n')
} finally {
  socket?.close()
  browser.kill()
  if (browser.exitCode === null) await Promise.race([once(browser, 'exit'), delay(5000)])
  const resolvedProfile = fs.realpathSync(profile)
  if (
    path.dirname(resolvedProfile) !== profileRoot ||
    !path.basename(resolvedProfile).startsWith('dt05-chrome-')
  ) {
    throw new Error('Refusing to remove a browser profile outside the DT-05 test directory')
  }
  fs.rmSync(resolvedProfile, { recursive: true, force: true })
}
