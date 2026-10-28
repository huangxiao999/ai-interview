import { getJson } from './http'

export interface HealthStatus {
  status: string
}

function parseHealthStatus(payload: unknown): HealthStatus {
  if (
    typeof payload !== 'object' ||
    payload === null ||
    !('status' in payload) ||
    typeof payload.status !== 'string'
  ) {
    throw new Error('健康检查响应格式无法识别')
  }

  return { status: payload.status }
}

export function getBackendHealth(): Promise<HealthStatus> {
  return getJson('/actuator/health', parseHealthStatus)
}
