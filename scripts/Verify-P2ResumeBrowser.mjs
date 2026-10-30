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
  await evaluate(`Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('退出登录')).click()`)
  await login()
  await waitUntil(`!!document.querySelector('.material-list li')`, 'resume history after re-login')
  await evaluate(`document.querySelector('.material-list li button').click()`)
  await waitUntil(`document.body.innerText.includes(${JSON.stringify(synthetic)})`, 'saved resume detail')
  await evaluate(`Array.from(document.querySelectorAll('.material-list li button'))
    .find(button => button.textContent.includes('删除整份')).click()`)
  await waitUntil(`document.body.innerText.includes('确认删除整份简历')`, 'deletion confirmation')
  assert.equal(await evaluate(`document.body.innerText.includes('当前的 1 个正文版本')`), true)
  await evaluate(`Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent.includes('确认删除')).click()`)
  await waitUntil(`document.body.innerText.includes('简历及当前正文已删除')`, 'deleted resume')
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
  fs.rmSync(resolvedProfile, { recursive: true, force: true })
}
