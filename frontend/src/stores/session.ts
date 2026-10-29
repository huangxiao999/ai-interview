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
    initialized = true
    return status.value
  }

  async function ensureIdentity(): Promise<SessionStatus> {
    return initialized ? status.value : refreshIdentity()
  }

  async function login(loginName: string, password: string): Promise<boolean> {
    if (busy.value || status.value !== 'anonymous') return false
    busy.value = true
    message.value = ''
    let submitted = false
    try {
      await refreshCsrf()
      submitted = true
      await submitLogin(loginName, password)
      const verified = await refreshIdentity()
      if (verified === 'authenticated') {
        try {
          await refreshCsrf() // The login response invalidates the anonymous session token.
        } catch {
          clearCsrf() // A later write will request a fresh token before submission.
        }
        return true
      }
      if (verified === 'anonymous') message.value = '登录状态已失效，请重新登录'
      return false
    } catch (error: unknown) {
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
          return true
        }
        if (verified === 'anonymous') message.value = '未确认登录成功，请检查后重试'
      } else {
        message.value = errorMessage(error)
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
    let submitted = false
    try {
      await refreshCsrf()
      submitted = true
      await submitLogout()
      setAnonymous()
      return true
    } catch (error: unknown) {
      if (
        submitted &&
        error instanceof ApiError &&
        (error.status === null || error.status >= 500)
      ) {
        const verified = await refreshIdentity()
        if (verified === 'anonymous') return true
        if (verified === 'authenticated') message.value = '尚未退出，请重试'
      } else {
        message.value = errorMessage(error)
      }
      return false
    } finally {
      busy.value = false
    }
  }

  return { status, identity, message, busy, refreshIdentity, ensureIdentity, login, logout }
})
