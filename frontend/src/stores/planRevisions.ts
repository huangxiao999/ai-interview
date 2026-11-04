import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { getIdentity } from '../api/auth'
import { ApiError } from '../api/http'
import {
  currentRevision,
  fixedRevision,
  revisionOperation,
  writeRevision,
  type RevisionTopic,
  type RevisionView,
} from '../api/planRevisions'
import { useSessionStore } from './session'

interface Receipt {
  owner: string
  plan: string
  operation: string
  kind: 'SAVE' | 'CONFIRM'
}
const key = 'interview-coach-plan-revision-receipt-v1'
export const usePlanRevisionsStore = defineStore('planRevisions', () => {
  const session = useSessionStore()
  const view = ref<RevisionView | null>(null),
    draft = ref<RevisionTopic[]>([])
  const busy = ref(false),
    dirty = ref(false),
    message = ref(''),
    unknown = ref(false),
    receipt = ref<Receipt | null>(null)
  let epoch = 0,
    target = '',
    selectedRevision = '',
    draftRevision = ''
  const total = computed(() => draft.value.reduce((n, t) => n + Number(t.questionCount), 0))
  const editable = computed(() => !!view.value?.canEdit && !busy.value && !unknown.value)
  function restore(): void {
    receipt.value = null
    try {
      const r: unknown = JSON.parse(sessionStorage.getItem(key) ?? 'null')
      if (!r || typeof r !== 'object' || Array.isArray(r)) return
      const p = r as Record<string, unknown>
      if (
        p.owner === session.identity?.accountId &&
        typeof p.owner === 'string' &&
        typeof p.plan === 'string' &&
        typeof p.operation === 'string' &&
        (p.kind === 'SAVE' || p.kind === 'CONFIRM')
      )
        receipt.value = { owner: p.owner, plan: p.plan, operation: p.operation, kind: p.kind }
      else if (session.status === 'authenticated' || session.status === 'anonymous')
        sessionStorage.removeItem(key)
    } catch {
      sessionStorage.removeItem(key)
    }
  }
  function hide(): void {
    epoch++
    target = ''
    view.value = null
    draft.value = []
    dirty.value = false
    busy.value = false
    unknown.value = false
    message.value = ''
    receipt.value = null
  }
  watch(
    () => [session.status, session.identity?.accountId] as const,
    () => {
      hide()
      if (session.status === 'anonymous') sessionStorage.removeItem(key)
      restore()
    },
    { flush: 'sync' },
  )
  function useView(v: RevisionView, replace: boolean): void {
    view.value = v
    if (replace) {
      draft.value = JSON.parse(JSON.stringify(v.revision.content.topics)) as RevisionTopic[]
      dirty.value = false
      draftRevision = v.revision.id
    }
  }
  function clearReceipt(): void {
    receipt.value = null
    sessionStorage.removeItem(key)
    unknown.value = false
  }
  async function open(plan: string, revision = selectedRevision): Promise<void> {
    if (target !== plan || selectedRevision !== revision) {
      hide()
      target = plan
      selectedRevision = revision
      restore()
    }
    if (busy.value || session.status !== 'authenticated') return
    if (receipt.value && receipt.value.plan !== plan) {
      unknown.value = true
      message.value = '本窗口另有未核实的保存/确认操作，请先返回原计划核实；原回执已保留'
      return
    }
    const current = epoch
    busy.value = true
    try {
      const r = receipt.value?.plan === plan ? receipt.value : null
      const result = r
        ? await revisionOperation(r.operation)
        : selectedRevision
          ? await fixedRevision(plan, selectedRevision)
          : await currentRevision(plan)
      if (current !== epoch) return
      useView(result, !!r || !dirty.value)
      if (r) {
        clearReceipt()
        message.value = '已核实原操作，展示其原修订；可另行核实当前版本'
      }
    } catch (error: unknown) {
      if (current !== epoch) return
      // 404不能证明原POST未受理；保留最小回执，禁止悄悄换新操作。
      unknown.value = receipt.value?.plan === plan
      view.value = null
      message.value = unknown.value
        ? '原操作结果尚未核实，请继续查询；不会重新保存或确认'
        : error instanceof ApiError
          ? error.message
          : '暂时无法读取计划修订'
    } finally {
      if (current === epoch) busy.value = false
    }
  }
  async function refresh(): Promise<void> {
    if (!target || unknown.value || busy.value) return
    const current = epoch
    busy.value = true
    try {
      const v = await currentRevision(target)
      if (current === epoch) {
        useView(v, !dirty.value)
        message.value = dirty.value ? '已核实当前修订；草稿未覆盖，请先明确载入当前修订再编辑' : ''
      }
    } catch (e: unknown) {
      if (current === epoch) {
        view.value = null
        message.value = e instanceof ApiError ? e.message : '核实失败'
      }
    } finally {
      if (current === epoch) busy.value = false
    }
  }
  function reloadDraft(): void {
    if (view.value && !busy.value && !unknown.value) useView(view.value, true)
  }
  async function send(kind: 'SAVE' | 'CONFIRM'): Promise<void> {
    const v = view.value,
      owner = session.identity?.accountId
    if (
      !v ||
      !owner ||
      busy.value ||
      unknown.value ||
      (kind === 'SAVE' ? !v.canEdit : !v.canConfirm || dirty.value)
    )
      return
    const current = epoch,
      plan = target
    const body =
      kind === 'SAVE'
        ? {
            expectedRevisionId: draftRevision,
            topics: draft.value.map((t, i) => ({ ...t, order: i + 1 })),
          }
        : { expectedRevisionId: v.revision.id }
    const r: Receipt = { owner, plan, operation: crypto.randomUUID(), kind }
    busy.value = true
    message.value = ''
    let sent = false
    try {
      const identity = await getIdentity()
      if (current !== epoch) return
      if (identity.accountId !== owner) {
        await session.refreshIdentity()
        return
      }
      const result = await writeRevision(plan, kind, body, owner, r.operation, () => {
        if (current !== epoch || session.identity?.accountId !== owner)
          throw new ApiError(409, 'ACCOUNT_CHANGED', '账号已变化')
        sessionStorage.setItem(key, JSON.stringify(r))
        receipt.value = r
        sent = true
      })
      if (current !== epoch) return
      useView(result, true)
      clearReceipt()
      message.value =
        kind === 'SAVE' ? '已保存新修订，尚未确认' : '已确认此修订；尚未开始面试或扣次'
    } catch (e: unknown) {
      if (current !== epoch) return
      const definite =
        e instanceof ApiError && e.status !== null && [400, 403, 409].includes(e.status)
      if (sent && !definite) {
        unknown.value = true
        message.value = '操作结果未知，请核实原操作，禁止重新提交'
      } else {
        if (sent) clearReceipt()
        message.value = e instanceof ApiError ? e.message : '安全预检未完成，未发送新操作'
      }
    } finally {
      if (current === epoch) busy.value = false
    }
  }
  return {
    view,
    draft,
    dirty,
    busy,
    unknown,
    message,
    total,
    editable,
    open,
    refresh,
    reloadDraft,
    send,
    hide,
  }
})
