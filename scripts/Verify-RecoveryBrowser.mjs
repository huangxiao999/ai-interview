// DT-06: real Chrome, shared Cookie jar, controlled response loss and an isolated PG16 backend.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import fs from 'node:fs'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

const chrome = process.env.DT06_CHROME_PATH ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const base = process.env.DT06_FRONTEND_BASE
const control = process.env.DT06_CONTROL_URL
const userA = process.env.DT06_USER_A
const passwordA = process.env.DT06_PASSWORD_A
const accountA = process.env.DT06_ACCOUNT_A
const userB = process.env.DT06_USER_B
const passwordB = process.env.DT06_PASSWORD_B
const accountB = process.env.DT06_ACCOUNT_B
assert.ok(base && control && userA && passwordA && accountA && userB && passwordB && accountB)
assert.ok(fs.existsSync(chrome), 'Real Chrome is required')

const profileRoot = fs.realpathSync(path.resolve('../.cache/tmp'))
const profile = fs.mkdtempSync(path.join(profileRoot, 'dt06-chrome-'))
const browser = spawn(chrome, [
  '--headless=new', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank',
], { stdio: 'ignore' })

let socket
try {
  let endpoint
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const activePort = path.join(profile, 'DevToolsActivePort')
    if (fs.existsSync(activePort)) {
      const [port, browserPath] = fs.readFileSync(activePort, 'utf8').trim().split('\n')
      endpoint = `ws://127.0.0.1:${port}${browserPath}`
      break
    }
    if (browser.exitCode !== null) throw new Error('Chrome exited before debugging was ready')
    await delay(100)
  }
  assert.ok(endpoint, 'Chrome debugging did not start')
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

  async function openTab(url) {
    const { targetId } = await send('Target.createTarget', { url })
    const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true })
    return sessionId
  }

  async function evaluate(tab, expression) {
    const result = await send('Runtime.evaluate', {
      expression, awaitPromise: true, returnByValue: true,
    }, tab)
    if (result.exceptionDetails) throw new Error('Browser page script failed')
    return result.result.value
  }

  async function waitUntil(tab, expression, label) {
    for (let attempt = 0; attempt < 150; attempt += 1) {
      try {
        if (await evaluate(tab, expression)) return
      } catch {
        // A full navigation replaces the JavaScript execution context.
      }
      await delay(100)
    }
    const state = await evaluate(tab, `({ path: location.pathname,
      heading: document.querySelector('h1')?.textContent,
      alert: document.querySelector('[role=alert]')?.textContent })`).catch(() => null)
    throw new Error(`Timed out waiting for ${label}: ${JSON.stringify(state)}`)
  }

  async function fill(tab, login, password) {
    await evaluate(tab, `(() => {
      const name = document.querySelector('#login-name')
      const secret = document.querySelector('#login-password')
      name.value = ${JSON.stringify(login)}
      secret.value = ${JSON.stringify(password)}
      name.dispatchEvent(new Event('input', { bubbles: true }))
      secret.dispatchEvent(new Event('input', { bubbles: true }))
      document.querySelector('form').requestSubmit()
    })()`)
  }

  async function signOut(tab) {
    await evaluate(tab, `Array.from(document.querySelectorAll('button'))
      .find(button => button.textContent.includes('退出登录')).click()`)
    await waitUntil(tab, `location.pathname === '/login' && !!document.querySelector('#login-name')`, 'logout page')
  }

  async function controlDatabase(action) {
    const response = await fetch(`${control}${action}`, { method: 'POST' })
    assert.equal(response.status, 204, `isolated PG control ${action}`)
  }

  const tabA = await openTab(`${base}/app`)
  await waitUntil(tabA, `location.pathname === '/login' && !!document.querySelector('#login-name')`, 'protected redirect')
  assert.equal(await evaluate(tabA, `new URLSearchParams(location.search).get('redirect')`), '/app')
  await fill(tabA, userA, passwordA)
  await waitUntil(tabA, `location.pathname === '/app' && document.body.innerText.includes(${JSON.stringify(userA)})`, 'A login')
  assert.equal(await evaluate(tabA, `document.cookie.includes('SESSION=')`), false)

  // The logout POST reaches the server; only the browser-visible response is lost.
  await evaluate(tabA, `(() => {
    const nativeFetch = window.fetch.bind(window)
    window.dt06 = { loginPosts: 0, logoutPosts: 0, meChecks: 0, loseLogin: false,
      loseLogout: true, failNextMe: false, holdNextMe: false, releaseHeldMe: null }
    window.fetch = async (input, init) => {
      const target = new URL(input instanceof Request ? input.url : input, location.href).pathname
      if (target === '/api/auth/login') window.dt06.loginPosts += 1
      if (target === '/api/auth/logout') window.dt06.logoutPosts += 1
      if (target === '/api/auth/me') {
        window.dt06.meChecks += 1
        if (window.dt06.failNextMe) {
          window.dt06.failNextMe = false
          throw new TypeError('synthetic identity network loss')
        }
      }
      const response = await nativeFetch(input, init)
      if (target === '/api/auth/login' && window.dt06.loseLogin) {
        window.dt06.loseLogin = false
        throw new TypeError('synthetic login response loss')
      }
      if (target === '/api/auth/logout' && window.dt06.loseLogout) {
        window.dt06.loseLogout = false
        throw new TypeError('synthetic logout response loss')
      }
      if (target === '/api/auth/me' && window.dt06.holdNextMe && response.ok) {
        window.dt06.holdNextMe = false
        return await new Promise(resolve => { window.dt06.releaseHeldMe = () => resolve(response) })
      }
      return response
    }
  })()`)
  await signOut(tabA)
  assert.equal(await evaluate(tabA, `window.dt06.logoutPosts`), 1)
  assert.ok(await evaluate(tabA, `window.dt06.meChecks`) >= 1)
  assert.equal(await evaluate(tabA, `(async () => (await fetch('/api/auth/me')).status)()`), 401)

  // Login result and the first verification both disappear: keep unknown and never replay POST.
  await evaluate(tabA, `(() => { window.dt06.loginPosts = 0;
    window.dt06.loseLogin = true; window.dt06.failNextMe = true })()`)
  await fill(tabA, userA, passwordA)
  await waitUntil(tabA, `document.body.innerText.includes('暂时无法核实登录状态')`, 'unknown login result')
  assert.equal(await evaluate(tabA, `window.dt06.loginPosts`), 1)
  assert.equal(await evaluate(tabA, `!!document.querySelector('#login-name')`), false)
  await evaluate(tabA, `Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('重新核实')).click()`)
  await waitUntil(tabA, `location.pathname === '/app' && document.body.innerText.includes(${JSON.stringify(userA)})`, 'login verification retry')
  assert.equal(await evaluate(tabA, `window.dt06.loginPosts`), 1)

  const tabB = await openTab(`${base}/app`)
  await waitUntil(tabB, `location.pathname === '/app' && document.body.innerText.includes(${JSON.stringify(userA)})`, 'second tab A')
  await evaluate(tabA, `window.dt06.holdNextMe = true; window.dispatchEvent(new Event('focus'))`)
  await waitUntil(tabA, `typeof window.dt06.releaseHeldMe === 'function'`, 'held A identity response')
  await signOut(tabB)
  await waitUntil(tabA, `!document.body.innerText.includes(${JSON.stringify(userA)})`, 'A hidden after other tab logout')
  await fill(tabB, userB, passwordB)
  await waitUntil(tabB, `location.pathname === '/app' && document.body.innerText.includes(${JSON.stringify(userB)})`, 'B login')
  await waitUntil(tabA, `document.body.innerText.includes(${JSON.stringify(userB)})`, 'other tab follows B')
  await evaluate(tabA, `window.dt06.releaseHeldMe()`)
  await delay(300)
  assert.equal(await evaluate(tabA, `document.body.innerText.includes(${JSON.stringify(userA)})`), false)
  assert.equal(await evaluate(tabA, `document.body.innerText.includes(${JSON.stringify(userB)})`), true)
  assert.equal(await evaluate(tabA, `(async () => (await (await fetch('/api/auth/me')).json()).accountId)()`), accountB)

  // Real isolated PG structure is temporarily unavailable; the page must not reuse B from memory.
  await controlDatabase('unavailable')
  try {
    await evaluate(tabA, `window.dispatchEvent(new Event('focus'))`)
    await waitUntil(tabA, `document.body.innerText.includes('暂时无法核实登录状态')`, 'database unavailable')
    assert.equal(await evaluate(tabA, `document.body.innerText.includes(${JSON.stringify(userB)})`), false)
  } finally {
    await controlDatabase('restore')
  }
  await evaluate(tabA, `Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('重新核实')).click()`)
  await waitUntil(tabA, `document.body.innerText.includes(${JSON.stringify(userB)})`, 'database recovery')

  await controlDatabase('expire')
  assert.equal(await evaluate(tabA, `(async () => (await fetch('/api/auth/me')).status)()`), 401,
    'expired PostgreSQL session must be rejected by the backend')
  await evaluate(tabA, `window.dispatchEvent(new Event('focus'))`)
  await waitUntil(tabA, `document.body.innerText.includes('登录已失效')`, 'expired session')
  assert.equal(await evaluate(tabA, `document.body.innerText.includes(${JSON.stringify(userB)})`), false)
  assert.equal(await evaluate(tabA, `(async () => (await fetch('/api/auth/me')).status)()`), 401)

  // A return target supplied in the URL cannot navigate outside the one allowed protected route.
  await send('Page.navigate', { url: `${base}/login?redirect=%2F%2Fevil.example` }, tabA)
  await waitUntil(tabA, `location.pathname === '/login' && !!document.querySelector('#login-name')`, 'expired re-login')
  assert.deepEqual(await evaluate(tabA, `(async () => {
    const { safeReturnPath } = await import('/src/router/returnPath.ts')
    return ['/app', '//evil.example', 'https://evil.example', 'javascript:alert(1)',
      '/app/../admin', '/%2Fexample.com'].map(safeReturnPath)
  })()`), Array(6).fill('/app'))
  await fill(tabA, userB, passwordB)
  await waitUntil(tabA, `location.pathname === '/app' && document.body.innerText.includes(${JSON.stringify(userB)})`, 'safe return')
  assert.equal(await evaluate(tabA, `location.origin`), new URL(base).origin)
  assert.equal(await evaluate(tabA, `(async () => (await (await fetch('/api/auth/me')).json()).accountId)()`), accountB)

  process.stdout.write('DT-06 real Chrome recovery checks passed.\n')
} finally {
  socket?.close()
  browser.kill()
  if (browser.exitCode === null) await Promise.race([once(browser, 'exit'), delay(5000)])
  const resolvedProfile = fs.realpathSync(profile)
  if (path.dirname(resolvedProfile) !== profileRoot || !path.basename(resolvedProfile).startsWith('dt06-chrome-')) {
    throw new Error('Refusing to remove a browser profile outside the DT-06 test directory')
  }
  fs.rmSync(resolvedProfile, { recursive: true, force: true })
}
