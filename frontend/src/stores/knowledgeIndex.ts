import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { ApiError } from '../api/http'
import { listKnowledge, type KnowledgeVersion } from '../api/knowledge'
import {
  indexBuilds,
  readIndexBuild,
  readIndexOperation,
  sendIndexOperation,
  type IndexBuild,
  type IndexOperation,
} from '../api/knowledgeIndex'
import { useSessionStore } from './session'

export const useKnowledgeIndexStore = defineStore('knowledgeIndex', () => {
  const session = useSessionStore()
  const versions = ref<KnowledgeVersion[]>([])
  const selected = ref('')
  const builds = ref<IndexBuild[]>([])
  const current = ref<IndexBuild | null>(null)
  const pending = ref<IndexOperation | null>(null)
  const receipt = ref<{ id: string; accountId: string } | null>(null)
  const busy = ref(false)
  const message = ref('')
  const acknowledge = ref(false)
  const key = 'knowledge-index-original-operation'
  let generation = 0
  function storeReceipt(value: { id: string; accountId: string } | null): void {
    receipt.value = value
    try {
      if (value) sessionStorage.setItem(key, JSON.stringify(value))
      else sessionStorage.removeItem(key)
    } catch {
      /* 仅最小身份；存储不可用仍保留内存核实。 */
    }
  }
  function restore(): void {
    receipt.value = null
    try {
      if (session.status === 'anonymous') {
        sessionStorage.removeItem(key)
        return
      }
      if (session.status !== 'authenticated' || !session.identity) return
      const raw: unknown = JSON.parse(sessionStorage.getItem(key) ?? 'null')
      if (
        typeof raw === 'object' &&
        raw !== null &&
        'id' in raw &&
        'accountId' in raw &&
        typeof raw.id === 'string' &&
        /^[a-f0-9-]{36}$/i.test(raw.id) &&
        raw.accountId === session.identity.accountId
      )
        receipt.value = { id: raw.id, accountId: raw.accountId as string }
      else sessionStorage.removeItem(key)
    } catch {
      /* 不恢复正文、配置、凭据或付费确认。 */
    }
  }
  watch(
    () => [session.status, session.identity?.accountId] as const,
    () => {
      generation++
      versions.value = []
      selected.value = ''
      builds.value = []
      current.value = null
      pending.value = null
      busy.value = false
      message.value = ''
      acknowledge.value = false
      restore()
    },
    { flush: 'sync' },
  )
  restore()
  async function load(): Promise<void> {
    if (
      session.status !== 'authenticated' ||
      !session.identity?.authorities.includes('KNOWLEDGE_MAINTAINER')
    )
      return
    const epoch = generation
    try {
      const result = await listKnowledge()
      if (epoch === generation) versions.value = result.filter((v) => v.state === 'APPROVED')
    } catch {
      if (epoch === generation) message.value = '批准版本读取失败，可重新加载'
    }
  }
  async function choose(): Promise<void> {
    if (receipt.value || busy.value) return
    const v = versions.value.find((v) => v.versionId === selected.value)
    const epoch = generation
    current.value = null
    builds.value = []
    acknowledge.value = false
    if (!v) return
    try {
      const result = await indexBuilds(v.documentId, v.versionId)
      if (epoch === generation && selected.value === v.versionId) {
        builds.value = result
        current.value = result.at(-1) ?? null
      }
    } catch {
      if (epoch === generation) message.value = '构建列表读取失败'
    }
  }
  async function send(): Promise<void> {
    const op = pending.value
    if (!op || busy.value) return
    const epoch = generation
    let dispatched = false
    busy.value = true
    message.value = '正在核实身份与提交原操作'
    try {
      const result = await sendIndexOperation(op, () => {
        if (epoch !== generation) throw new ApiError(409, 'ACCOUNT_CHANGED', '身份核实已更新')
        dispatched = true
        storeReceipt({ id: op.id, accountId: op.accountId })
      })
      if (epoch !== generation) return
      current.value = result
      pending.value = null
      storeReceipt(null)
      acknowledge.value = false
      message.value = '服务端已受理原构建操作；构建结果以查询为准'
    } catch (error: unknown) {
      if (epoch !== generation) return
      const deterministic =
        error instanceof ApiError &&
        ((error.status !== null && error.status < 500) ||
          error.code === 'INDEX_SERVICE_UNAVAILABLE')
      if (!dispatched || deterministic) {
        pending.value = null
        storeReceipt(null)
        message.value = error instanceof Error ? error.message : '请求尚未发送，可重新操作'
      } else message.value = '结果未知，请核实原操作，不能换请求编号猜测结果'
      if (
        error instanceof ApiError &&
        (error.status === 401 || error.status === 403 || error.code === 'ACCOUNT_CHANGED')
      )
        await session.refreshIdentity()
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  function create(): Promise<void> {
    const v = versions.value.find((v) => v.versionId === selected.value)
    if (!v || !session.identity || receipt.value || busy.value) return Promise.resolve()
    pending.value = {
      id: crypto.randomUUID(),
      accountId: session.identity.accountId,
      kind: 'CREATE',
      root: v.documentId,
      version: v.versionId,
      digest: v.digest,
    }
    return send()
  }
  function resume(): Promise<void> {
    const build = current.value
    if (!build || !session.identity || receipt.value || busy.value) return Promise.resolve()
    const batch = build.batches.find((b) => b.state !== 'ACCEPTED')
    if (!batch) return Promise.resolve()
    pending.value = {
      id: crypto.randomUUID(),
      accountId: session.identity.accountId,
      kind: 'RESUME',
      build: build.buildId,
      attemptId: batch.attemptId,
      acknowledge: acknowledge.value,
    }
    return send()
  }
  async function verify(): Promise<void> {
    if (busy.value) return
    const original = receipt.value
    const build = current.value
    if (!original && !build) return
    const epoch = generation
    busy.value = true
    try {
      const result = original
        ? await readIndexOperation(original.id)
        : await readIndexBuild(build!.buildId)
      if (epoch !== generation) return
      current.value = result
      selected.value = result.versionId
      if (original) {
        storeReceipt(null)
        pending.value = null
      }
      acknowledge.value = false
      message.value = '已核实原构建状态'
    } catch {
      if (epoch === generation) message.value = '仍无法核实；查不到不等于未接受，可稍后核实或离开'
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  return {
    versions,
    selected,
    builds,
    current,
    pending,
    receipt,
    busy,
    message,
    acknowledge,
    load,
    choose,
    create,
    resume,
    send,
    verify,
  }
})
