// Exercises browser-managed Cookie and CSRF behavior against the DT-03 isolated test server.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import fs from 'node:fs'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

const chrome = process.env.DT03_CHROME_PATH ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const base = process.env.DT03_BROWSER_BASE
const username = process.env.DT03_BROWSER_USER
const password = process.env.DT03_BROWSER_PASSWORD
const accountId = process.env.DT03_BROWSER_ACCOUNT_ID
assert.ok(base && username && password && accountId, 'Browser verification inputs are required')
assert.ok(fs.existsSync(chrome), 'Chrome executable is required for real-browser verification')

const profileRoot = fs.realpathSync(path.resolve('../.cache/tmp'))
const profile = fs.mkdtempSync(path.join(profileRoot, 'dt03-chrome-'))
const browser = spawn(chrome, [
  '--headless=new',
  '--no-first-run',
  '--no-default-browser-check',
  '--remote-debugging-port=0',
  `--user-data-dir=${profile}`,
  'about:blank',
], { stdio: 'ignore' })

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
  let failedLogoutResponse = false
  let failureResult
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data)
    if (message.method === 'Fetch.requestPaused' && message.params?.responseStatusCode &&
        message.params.request.url.endsWith('/api/auth/logout')) {
      failedLogoutResponse = true
      failureResult = send('Fetch.failRequest', {
        requestId: message.params.requestId, errorReason: 'Failed',
      }, message.sessionId)
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

  async function openTab() {
    const { targetId } = await send('Target.createTarget', { url: `${base}/api/auth/csrf` })
    const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true })
    for (let i = 0; i < 50; i += 1) {
      const result = await send('Runtime.evaluate', {
        expression: 'location.origin', returnByValue: true,
      }, sessionId)
      if (result.result?.value === base) return sessionId
      await delay(100)
    }
    throw new Error('Browser tab did not navigate to the test server')
  }

  async function evaluate(sessionId, expression) {
    const result = await send('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true,
    }, sessionId)
    if (result.exceptionDetails) throw new Error('Browser script failed')
    return result.result.value
  }

  const first = await openTab()
  const second = await openTab()
  const anonymous = await evaluate(first, `(async () => (await fetch('/api/auth/me')).status)()`)
  assert.equal(anonymous, 401)
  const csrf = await evaluate(first, `(async () => await (await fetch('/api/auth/csrf')).json())()`)
  assert.equal(csrf.headerName, 'X-CSRF-TOKEN')
  const invalidCsrf = await evaluate(first, `(async () => (await fetch('/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username: ${JSON.stringify(username)}, password: ${JSON.stringify(password)} })
  })).status)()`)
  assert.equal(invalidCsrf, 403)
  const loggedIn = await evaluate(first, `(async () => {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded',
        [${JSON.stringify(csrf.headerName)}]: ${JSON.stringify(csrf.token)} },
      body: new URLSearchParams({ username: ${JSON.stringify(username)}, password: ${JSON.stringify(password)} })
    })
    return { status: response.status, body: await response.json(), visibleCookie: document.cookie }
  })()`)
  assert.equal(loggedIn.status, 200)
  assert.equal(loggedIn.body.accountId, accountId)
  assert.ok(!loggedIn.visibleCookie.includes('SESSION='), 'HttpOnly Cookie is visible to page script')
  const otherTabIdentity = await evaluate(second,
    `(async () => ({ status: (await fetch('/api/auth/me')).status }))()`)
  assert.equal(otherTabIdentity.status, 200)
  const rotated = await evaluate(first, `(async () => await (await fetch('/api/auth/csrf')).json())()`)
  assert.notEqual(rotated.token, csrf.token)
  await send('Fetch.enable', { patterns: [{
    urlPattern: '*api/auth/logout', requestStage: 'Response',
  }] }, first)
  const logout = await evaluate(first, `(async () => {
    try {
      await fetch('/api/auth/logout', {
        method: 'POST', headers: { [${JSON.stringify(rotated.headerName)}]: ${JSON.stringify(rotated.token)} }
      })
      return 'delivered'
    } catch {
      return 'response-lost'
    }
  })()`)
  assert.equal(logout, 'response-lost')
  assert.equal(failedLogoutResponse, true)
  await failureResult
  await send('Fetch.disable', {}, first)
  assert.equal(await evaluate(second, `(async () => (await fetch('/api/auth/me')).status)()`), 401)
  process.stdout.write('Chrome browser Cookie, CSRF, login, shared-tab session and lost-logout-response checks passed.\n')
} finally {
  socket?.close()
  browser.kill()
  if (browser.exitCode === null) {
    await Promise.race([once(browser, 'exit'), delay(5000)])
  }
  const resolvedProfile = fs.realpathSync(profile)
  if (path.dirname(resolvedProfile) !== profileRoot ||
      !path.basename(resolvedProfile).startsWith('dt03-chrome-')) {
    throw new Error('Refusing to remove a browser profile outside the DT-03 test directory')
  }
  fs.rmSync(resolvedProfile, { recursive: true, force: true })
}
