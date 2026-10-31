import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { getIdentity } from '../api/auth'
import { ApiError } from '../api/http'
import {
  listKnowledge,
  readKnowledgeOperation,
  submitKnowledge,
  type KnowledgeInput,
  type KnowledgeOperation,
  type KnowledgeVersion,
} from '../api/knowledge'
import { useSessionStore } from './session'

export const useKnowledgeStore = defineStore('knowledge', () => {
  const session = useSessionStore()
  const empty = (): KnowledgeInput => ({
    title: '',
    body: '',
    sourceUrl: '',
    sourceAnchor: '',
    applicability: '',
    usageNote: '',
    verificationNote: '',
    verifiedOn: '',
  })
  const draft = ref<KnowledgeInput>(empty())
  const items = ref<KnowledgeVersion[]>([])
  const selected = ref<KnowledgeVersion | null>(null)
  const root = ref<string | null>(null)
  const reason = ref('')
  const pending = ref<KnowledgeOperation | null>(null)
  const receipt = ref<{ id: string; accountId: string } | null>(null)
  const unresolved = computed(() => pending.value !== null || receipt.value !== null)
  const receiptKey = 'knowledge-original-operation'
  function restoreReceipt(): void {
    receipt.value = null
    try {
      const raw: unknown = JSON.parse(sessionStorage.getItem(receiptKey) ?? 'null')
      if (session.status === 'anonymous') {
        sessionStorage.removeItem(receiptKey)
        return
      }
      if (session.status !== 'authenticated' || !session.identity) return
      if (
        typeof raw === 'object' &&
        raw !== null &&
        'id' in raw &&
        'accountId' in raw &&
        typeof raw.id === 'string' &&
        /^[a-f0-9-]{36}$/i.test(raw.id) &&
        raw.accountId === session.identity?.accountId
      ) {
        receipt.value = { id: raw.id, accountId: raw.accountId as string }
      } else sessionStorage.removeItem(receiptKey)
    } catch {
      /* 存储不可用不影响内存核实，不持久化任何正文或凭据。 */
    }
  }
  function saveReceipt(op: KnowledgeOperation): void {
    receipt.value = { id: op.id, accountId: op.accountId }
    try {
      sessionStorage.setItem(receiptKey, JSON.stringify(receipt.value))
    } catch {
      /* 保留内存状态。 */
    }
  }
  function clearReceipt(): void {
    receipt.value = null
    try {
      sessionStorage.removeItem(receiptKey)
    } catch {
      /* 无正文残留。 */
    }
  }
  const busy = ref(false)
  const message = ref('')
  const listState = ref<'empty' | 'loading' | 'ready' | 'failed'>('empty')
  let generation = 0

  // 账号核实中、匿名或切换身份即清内存；不将正文/凭据写入浏览器持久存储。
  watch(
    () => [session.status, session.identity?.accountId] as const,
    () => {
      generation++
      draft.value = empty()
      items.value = []
      selected.value = null
      root.value = null
      pending.value = null
      reason.value = ''
      message.value = ''
      busy.value = false
      listState.value = 'empty'
      restoreReceipt()
    },
    { flush: 'sync' },
  )
  restoreReceipt()

  async function refresh(): Promise<void> {
    if (session.status !== 'authenticated') return
    const epoch = generation
    listState.value = 'loading'
    try {
      const result = await listKnowledge()
      if (epoch === generation) {
        items.value = result
        listState.value = result.length ? 'ready' : 'empty'
      }
    } catch {
      if (epoch === generation) {
        listState.value = 'failed'
        message.value = '版本列表读取失败，可重新加载'
      }
    }
  }
  function select(version: KnowledgeVersion): void {
    if (unresolved.value || busy.value) return
    selected.value = version
    root.value = version.documentId
    draft.value = {
      title: version.title,
      body: version.body,
      sourceUrl: version.sourceUrl,
      sourceAnchor: version.sourceAnchor,
      applicability: version.applicability,
      usageNote: version.usageNote,
      verificationNote: version.verificationNote,
      verifiedOn: version.verifiedOn,
    }
    reason.value = ''
    message.value = ''
  }
  function newDocument(): void {
    if (unresolved.value || busy.value) return
    selected.value = null
    root.value = null
    draft.value = empty()
    reason.value = ''
    message.value = ''
  }
  function beginSubmit(): Promise<void> {
    if (busy.value || unresolved.value || !session.identity) return Promise.resolve()
    pending.value = {
      id: crypto.randomUUID(),
      accountId: session.identity.accountId,
      kind: 'SUBMIT',
      root: root.value,
      input: { ...draft.value },
    }
    saveReceipt(pending.value)
    return sendOriginal()
  }
  function beginReview(decision: 'APPROVE' | 'REJECT'): Promise<void> {
    if (busy.value || unresolved.value || !session.identity || !selected.value)
      return Promise.resolve()
    const v = selected.value
    pending.value = {
      id: crypto.randomUUID(),
      accountId: session.identity.accountId,
      kind: 'REVIEW',
      root: v.documentId,
      version: v.versionId,
      digest: v.digest,
      decision,
      reason: reason.value,
    }
    saveReceipt(pending.value)
    return sendOriginal()
  }
  async function sendOriginal(): Promise<void> {
    const op = pending.value
    if (!op || busy.value) return
    const epoch = generation
    busy.value = true
    message.value = '正在提交，请等待服务端确认'
    try {
      // 独立读me不清空当前同账号的未决操作；服务端仍在账号锁内复核凭证代次。
      const actor = await getIdentity()
      if (epoch !== generation) return
      if (actor.accountId !== op.accountId || !actor.authorities.includes('KNOWLEDGE_MAINTAINER')) {
        await session.refreshIdentity()
        return
      }
      const result = await submitKnowledge(op)
      if (epoch !== generation || pending.value?.id !== op.id) return
      pending.value = null
      clearReceipt()
      busy.value = false
      select(result)
      message.value = '服务端已确认保存'
      await refresh()
    } catch (error: unknown) {
      if (epoch !== generation) return
      if (error instanceof ApiError && error.status !== null && error.status < 500) {
        pending.value = null
        clearReceipt()
        message.value = error.message
        if (error.status === 401 || error.status === 403) await session.refreshIdentity()
      } else message.value = '结果未知：请核实原操作；不能创建另一份请求猜测结果'
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  async function verify(): Promise<void> {
    const op = pending.value ?? receipt.value
    if (!op || busy.value) return
    const epoch = generation
    busy.value = true
    try {
      const actor = await getIdentity()
      if (epoch !== generation) return
      if (actor.accountId !== op.accountId || !actor.authorities.includes('KNOWLEDGE_MAINTAINER')) {
        await session.refreshIdentity()
        return
      }
      const result = await readKnowledgeOperation(op.id)
      if (epoch !== generation || (pending.value?.id ?? receipt.value?.id) !== op.id) return
      pending.value = null
      clearReceipt()
      busy.value = false
      select(result)
      message.value = '已核实原操作'
      await refresh()
    } catch (error: unknown) {
      if (epoch === generation)
        message.value = '仍无法确认；查不到不等于未接受，可稍后核实或重发同一固定请求'
      if (
        epoch === generation &&
        error instanceof ApiError &&
        (error.status === 401 || error.status === 403)
      ) {
        await session.refreshIdentity()
      }
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  return {
    draft,
    items,
    selected,
    root,
    reason,
    pending,
    receipt,
    unresolved,
    busy,
    message,
    listState,
    refresh,
    select,
    newDocument,
    beginSubmit,
    beginReview,
    verify,
    sendOriginal,
  }
})
