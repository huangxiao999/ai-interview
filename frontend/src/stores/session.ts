import { defineStore } from 'pinia'
import { ref } from 'vue'
import { ApiError } from '../api/http'
import {
  clearCsrf,
  getIdentity,
  refreshCsrf,
  submitLogin,
  submitLogout,
  type Identity,
} from '../api/auth'
export type { Identity } from '../api/auth'

export type SessionStatus = 'checking' | 'anonymous' | 'authenticated' | 'uncertain'

function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '暂时无法核实登录状态，请重试'
  switch (error.code) {
    case 'INVALID_CREDENTIALS':
      return '账号或密码不正确'
    case 'INVALID_INPUT':
      return '请检查账号与密码输入'
    case 'CSRF_INVALID':
      return '页面安全凭据已更新，请重试'
    default:
      return '暂时无法核实登录状态，请重试'
  }
}

export const useSessionStore = defineStore('session', () => {
  const status = ref<SessionStatus>('checking')
  const identity = ref<Identity | null>(null)
  const message = ref('')
  const busy = ref(false)
  let initialized = false
  let generation = 0
  let identityChannel: BroadcastChannel | null = null

  function startBrowserSync(): void {
    if (identityChannel || typeof BroadcastChannel === 'undefined') return
    identityChannel = new BroadcastChannel('interview-coach-identity')
    identityChannel.onmessage = (event: MessageEvent<unknown>) => {
      if (event.data !== 'identity-changed') return
      // A notification carries no identity; only the server can establish the current account.
      void refreshIdentity()
    }
  }

  function announceIdentityChange(): void {
    identityChannel?.postMessage('identity-changed')
  }

  function setAnonymous(): void {
    identity.value = null
    status.value = 'anonymous'
    clearCsrf()
  }

  async function refreshIdentity(): Promise<SessionStatus> {
    const requestGeneration = ++generation
    identity.value = null
    status.value = 'checking'
    message.value = ''
    clearCsrf()
    try {
      const current = await getIdentity()
      if (requestGeneration === generation) {
        identity.value = current
        status.value = 'authenticated'
      }
    } catch (error: unknown) {
      if (requestGeneration === generation) {
        if (error instanceof ApiError && error.status === 401) setAnonymous()
        else status.value = 'uncertain'
        if (status.value === 'uncertain') message.value = errorMessage(error)
      }
    }
    if (requestGeneration === generation) initialized = true
    return status.value
  }

  async function ensureIdentity(): Promise<SessionStatus> {
    return initialized ? status.value : refreshIdentity()
  }

  async function login(loginName: string, password: string): Promise<boolean> {
    if (busy.value || status.value !== 'anonymous') return false
    busy.value = true
    message.value = ''
    const operationGeneration = ++generation
    let submitted = false
    try {
      await refreshCsrf()
      if (operationGeneration !== generation || status.value !== 'anonymous') return false
      submitted = true
      await submitLogin(loginName, password)
      const verified = await refreshIdentity()
      if (verified === 'authenticated') {
        try {
          await refreshCsrf() // The login response invalidates the anonymous session token.
        } catch {
          clearCsrf() // A later write will request a fresh token before submission.
        }
        announceIdentityChange()
        return true
      }
      if (verified === 'anonymous') message.value = '登录状态已失效，请重新登录'
      return false
    } catch (error: unknown) {
      if (operationGeneration !== generation && !submitted) return false
      if (
        submitted &&
        error instanceof ApiError &&
        (error.status === null || error.status >= 500)
      ) {
        const verified = await refreshIdentity() // A lost response does not justify another POST.
        if (verified === 'authenticated') {
          try {
            await refreshCsrf()
          } catch {
            clearCsrf()
          }
          announceIdentityChange()
          return true
        }
        if (verified === 'anonymous') message.value = '未确认登录成功，请检查后重试'
      } else {
        if (operationGeneration === generation) message.value = errorMessage(error)
      }
      return false
    } finally {
      busy.value = false
    }
  }

  async function logout(): Promise<boolean> {
    if (busy.value || status.value !== 'authenticated') return false
    busy.value = true
    message.value = ''
    const accountId = identity.value?.accountId
    const operationGeneration = ++generation
    let submitted = false
    try {
      await refreshCsrf()
      if (
        operationGeneration !== generation ||
        status.value !== 'authenticated' ||
        identity.value?.accountId !== accountId
      )
        return false
      submitted = true
      await submitLogout()
      const verified = await refreshIdentity()
      if (verified === 'anonymous') {
        announceIdentityChange()
        return true
      }
      if (verified === 'authenticated') message.value = '当前登录身份已变化，请确认后操作'
      return false
    } catch (error: unknown) {
      if (operationGeneration !== generation && !submitted) return false
      if (
        submitted &&
        error instanceof ApiError &&
        (error.status === null || error.status >= 500)
      ) {
        const verified = await refreshIdentity()
        if (verified === 'anonymous') {
          announceIdentityChange()
          return true
        }
        if (verified === 'authenticated') message.value = '尚未退出，请重试'
      } else {
        if (operationGeneration === generation) message.value = errorMessage(error)
      }
      return false
    } finally {
      busy.value = false
    }
  }

  return {
    status,
    identity,
    message,
    busy,
    refreshIdentity,
    ensureIdentity,
    login,
    logout,
    startBrowserSync,
  }
})
