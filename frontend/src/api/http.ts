export class ApiError extends Error {
  constructor(
    readonly status: number | null,
    readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export async function request(path: string, init: RequestInit = {}): Promise<Response> {
  try {
    const response = await fetch(path, {
      ...init,
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { Accept: 'application/json', ...init.headers },
      signal: init.signal ?? AbortSignal.timeout(10000),
    })
    if (response.ok) return response

    const payload: unknown = await response.json().catch(() => null)
    const code = isRecord(payload) && typeof payload.code === 'string' ? payload.code : 'HTTP_ERROR'
    const message =
      isRecord(payload) && typeof payload.message === 'string'
        ? payload.message
        : `请求未完成（HTTP ${response.status}）`
    throw new ApiError(response.status, code, message)
  } catch (error: unknown) {
    if (error instanceof ApiError) throw error
    throw new ApiError(null, 'NETWORK_ERROR', '网络异常，暂时无法核实请求结果')
  }
}

export async function getJson<T>(path: string, parse: (payload: unknown) => T): Promise<T> {
  const response = await request(path)
  const payload: unknown = await response.json()
  return parse(payload)
}

export function requireRecord(payload: unknown): Record<string, unknown> {
  if (!isRecord(payload)) throw new ApiError(null, 'INVALID_RESPONSE', '服务端响应格式无法识别')
  return payload
}
