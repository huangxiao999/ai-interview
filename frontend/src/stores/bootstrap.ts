import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getBackendHealth } from '../api/health'

export type ConnectionStatus = 'idle' | 'checking' | 'connected' | 'unavailable'

export const useBootstrapStore = defineStore('bootstrap', () => {
  const connectionStatus = ref<ConnectionStatus>('idle')
  const message = ref('尚未检查后端应用状态')

  async function checkBackend(): Promise<void> {
    if (connectionStatus.value === 'checking') return

    connectionStatus.value = 'checking'
    message.value = '正在检查后端应用状态'

    try {
      const health = await getBackendHealth()
      connectionStatus.value = health.status === 'UP' ? 'connected' : 'unavailable'
      message.value = health.status === 'UP' ? '后端应用已连接' : '后端尚未就绪'
    } catch (error: unknown) {
      connectionStatus.value = 'unavailable'
      message.value =
        error instanceof Error && error.name === 'TimeoutError'
          ? '检查超时，请确认后端已启动后重试'
          : '未能连接后端，请确认启动状态及代理地址后重试'
    }
  }

  return { connectionStatus, message, checkBackend }
})
