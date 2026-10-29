// Real Chrome: both tabs must lose the old authenticated session after a server-side reset.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import fs from 'node:fs'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

const chrome = process.env.DT04_CHROME_PATH ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const base = process.env.DT04_BROWSER_BASE
const username = process.env.DT04_BROWSER_USER
const password = process.env.DT04_BROWSER_PASSWORD
assert.ok(base && username && password, 'Browser verification inputs are required')
assert.ok(fs.existsSync(chrome), 'Chrome executable is required')

const profileRoot = fs.realpathSync(path.resolve('../.cache/tmp'))
const profile = fs.mkdtempSync(path.join(profileRoot, 'dt04-chrome-'))
const browser = spawn(chrome, [
  '--headless=new', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank',
], { stdio: 'ignore' })

let socket
try {
  let endpoint
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const marker = path.join(profile, 'DevToolsActivePort')
    if (fs.existsSync(marker)) {
      const [port, browserPath] = fs.readFileSync(marker, 'utf8').trim().split('\n')
      endpoint = `ws://127.0.0.1:${port}${browserPath}`
      break
    }
    if (browser.exitCode !== null) throw new Error('Chrome exited before debugging was ready')
    await delay(100)
  }
  assert.ok(endpoint, 'Chrome did not start')
  socket = new WebSocket(endpoint)
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true })
    socket.addEventListener('error', reject, { once: true })
  })
  let nextId = 0
  const pending = new Map()
  socket.addEventListener('message', event => {
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
  async function tab() {
    const { targetId } = await send('Target.createTarget', { url: `${base}/api/auth/csrf` })
    const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true })
    for (let i = 0; i < 50; i += 1) {
      const state = await send('Runtime.evaluate', { expression: 'location.origin', returnByValue: true }, sessionId)
      if (state.result?.value === base) return sessionId
      await delay(100)
    }
    throw new Error('Browser tab did not reach the local server')
  }
  async function evaluate(sessionId, expression) {
    const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, sessionId)
    if (result.exceptionDetails) throw new Error('Browser evaluation failed')
    return result.result.value
  }

  const first = await tab()
  const second = await tab()
  const csrf = await evaluate(first, `(async () => await (await fetch('/api/auth/csrf')).json())()`)
  const loggedIn = await evaluate(first, `(async () => (await fetch('/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded',
      [${JSON.stringify(csrf.headerName)}]: ${JSON.stringify(csrf.token)} },
    body: new URLSearchParams({ username: ${JSON.stringify(username)}, password: ${JSON.stringify(password)} })
  })).status)()`)
  assert.equal(loggedIn, 200)
  assert.equal(await evaluate(second, `(async () => (await fetch('/api/auth/me')).status)()`), 200)
  assert.ok(!await evaluate(first, `document.cookie.includes('SESSION=')`))
  process.stdout.write('BROWSER_READY\n')
  process.stdin.resume()
  await once(process.stdin, 'data')
  process.stdin.pause()
  assert.equal(await evaluate(first, `(async () => (await fetch('/api/auth/me')).status)()`), 401)
  assert.equal(await evaluate(second, `(async () => (await fetch('/api/auth/me')).status)()`), 401)
  process.stdout.write('Chrome reset revocation checks passed.\n')
} finally {
  socket?.close()
  browser.kill()
  if (browser.exitCode === null) {
    await Promise.race([once(browser, 'exit'), delay(5000)])
  }
  const resolvedProfile = fs.realpathSync(profile)
  if (path.dirname(resolvedProfile) !== profileRoot ||
      !path.basename(resolvedProfile).startsWith('dt04-chrome-')) {
    throw new Error('Refusing to remove a browser profile outside the DT-04 test directory')
  }
  fs.rmSync(resolvedProfile, { recursive: true, force: true })
}
