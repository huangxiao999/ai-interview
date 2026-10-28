export async function getJson<T>(path: string, parse: (payload: unknown) => T): Promise<T> {
  const response = await fetch(path, {
    method: 'GET',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal: AbortSignal.timeout(5000),
  })

  if (!response.ok) {
    throw new Error(`健康检查请求失败（HTTP ${response.status}）`)
  }

  const payload: unknown = await response.json()
  return parse(payload)
}
