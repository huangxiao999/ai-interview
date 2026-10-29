import { ApiError, getJson, request, requireRecord } from './http'

export interface Identity {
  accountId: string
  loginName: string
  authorities: string[]
}

interface CsrfToken {
  headerName: string
  token: string
}

let currentCsrf: CsrfToken | null = null

function parseIdentity(payload: unknown): Identity {
  const value = requireRecord(payload)
  if (
    typeof value.accountId !== 'string' ||
    typeof value.loginName !== 'string' ||
    !Array.isArray(value.authorities) ||
    !value.authorities.every((item: unknown) => typeof item === 'string')
  ) {
    throw new ApiError(null, 'INVALID_RESPONSE', '身份响应格式无法识别')
  }
  return {
    accountId: value.accountId,
    loginName: value.loginName,
    authorities: value.authorities,
  }
}

function parseCsrf(payload: unknown): CsrfToken {
  const value = requireRecord(payload)
  if (typeof value.headerName !== 'string' || typeof value.token !== 'string') {
    throw new ApiError(null, 'INVALID_RESPONSE', '安全凭据响应格式无法识别')
  }
  return { headerName: value.headerName, token: value.token }
}

export async function refreshCsrf(): Promise<void> {
  currentCsrf = await getJson('/api/auth/csrf', parseCsrf)
}

export function clearCsrf(): void {
  currentCsrf = null
}

export function getIdentity(): Promise<Identity> {
  return getJson('/api/auth/me', parseIdentity)
}

async function csrf(): Promise<CsrfToken> {
  if (currentCsrf === null) await refreshCsrf()
  if (currentCsrf === null) throw new ApiError(null, 'INVALID_RESPONSE', '安全凭据不可用')
  return currentCsrf
}

export async function submitLogin(loginName: string, password: string): Promise<Identity> {
  const token = await csrf()
  try {
    const response = await request('/api/auth/login', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        [token.headerName]: token.token,
      },
      body: new URLSearchParams({ username: loginName, password }),
    })
    clearCsrf() // Authentication rotates the session and invalidates its old CSRF token.
    let payload: unknown
    try {
      payload = await response.json()
    } catch {
      // A successful response header does not mean its body arrived; the session may already exist.
      throw new ApiError(null, 'RESULT_UNKNOWN', '登录结果无法确认')
    }
    return parseIdentity(payload)
  } catch (error: unknown) {
    if (error instanceof ApiError && error.code === 'CSRF_INVALID') clearCsrf()
    throw error
  }
}

export async function submitLogout(): Promise<void> {
  const token = await csrf()
  try {
    await request('/api/auth/logout', {
      method: 'POST',
      headers: { [token.headerName]: token.token },
    })
    clearCsrf()
  } catch (error: unknown) {
    if (error instanceof ApiError && error.code === 'CSRF_INVALID') clearCsrf()
    throw error
  }
}
