import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { ApiError } from '../api/http'
import { listPlanHistory, type PlanHistoryEntry } from '../api/planHistory'
import { useSessionStore } from './session'

export const usePlanHistoryStore = defineStore('planHistory', () => {
  const session = useSessionStore()
  const items = ref<PlanHistoryEntry[]>([]),
    nextCursor = ref<string | null>(null)
  const state = ref<'loading' | 'ready' | 'failed'>('loading'),
    message = ref(''),
    busy = ref(false)
  let epoch = 0,
    root = ''
  function hide(): void {
    epoch++
    items.value = []
    nextCursor.value = null
    state.value = 'loading'
    busy.value = false
    message.value = ''
  }
  watch(() => [session.status, session.identity?.accountId] as const, hide, { flush: 'sync' })
  async function load(resume = '', more = false): Promise<void> {
    if (
      session.status !== 'authenticated' ||
      (more && (busy.value || !nextCursor.value || resume !== root))
    )
      return
    if (!more) {
      hide()
      root = resume
    }
    const current = epoch,
      cursor = more ? nextCursor.value : null
    busy.value = true
    try {
      const result = await listPlanHistory(resume, cursor)
      if (current !== epoch) return
      items.value = more
        ? [
            ...items.value,
            ...result.items.filter(
              (r) => !items.value.some((old) => old.workspaceId === r.workspaceId),
            ),
          ]
        : result.items
      nextCursor.value = result.nextCursor
      state.value = 'ready'
      message.value = ''
    } catch (e: unknown) {
      if (current === epoch) {
        state.value = 'failed'
        message.value = e instanceof ApiError ? e.message : '暂时无法核实历史，请重试'
      }
    } finally {
      if (current === epoch) busy.value = false
    }
  }
  return { items, nextCursor, state, message, busy, load, hide }
})
