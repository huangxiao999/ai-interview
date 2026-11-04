import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { useSessionStore } from './session'
import { ApiError } from '../api/http'
import { getIdentity } from '../api/auth'
import { getDeletion } from '../api/materials'
import {
  getPlanOperation,
  getPlanWorkspace,
  writePlan,
  type PlanInput,
  type PlanOption,
  type PlanView,
} from '../api/plans'

interface Receipt {
  owner: string
  operation: string
  kind: 'CREATE_WORKSPACE' | 'GENERATE_PLAN' | 'RESUME'
  workspace: string | null
  resumeId: string
}
const key = 'interview-coach-plan-receipt-v1'
export const usePlansStore = defineStore('plans', () => {
  const session = useSessionStore()
  const view = ref<PlanView | null>(null)
  const receipt = ref<Receipt | null>(null)
  const busy = ref(false)
  const message = ref('')
  const unknown = ref(false)
  // 只能丢弃本人已核实终态的本地回执；未知、在途和跨账号状态没有新建出口。
  const canNewPlan = computed(
    () =>
      !busy.value &&
      !unknown.value &&
      session.status === 'authenticated' &&
      receipt.value?.owner === session.identity?.accountId &&
      !!view.value &&
      receipt.value?.workspace === view.value.workspaceId &&
      ['SUCCEEDED', 'FAILED_FINAL', 'INVALIDATED'].includes(view.value.state),
  )
  let epoch = 0
  function restore(): void {
    receipt.value = null
    unknown.value = false
    if (session.status !== 'authenticated' || !session.identity) return
    try {
      const raw: unknown = JSON.parse(sessionStorage.getItem(key) ?? 'null')
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return
      const r = raw as Record<string, unknown>
      if (
        r.owner === session.identity.accountId &&
        typeof r.operation === 'string' &&
        typeof r.resumeId === 'string' &&
        (r.workspace === null || typeof r.workspace === 'string') &&
        ['CREATE_WORKSPACE', 'GENERATE_PLAN', 'RESUME'].includes(String(r.kind))
      ) {
        receipt.value = {
          owner: r.owner,
          operation: r.operation,
          resumeId: r.resumeId,
          workspace: r.workspace as string | null,
          kind: r.kind as Receipt['kind'],
        }
        unknown.value = true
      } else sessionStorage.removeItem(key)
    } catch {
      sessionStorage.removeItem(key)
    }
  }
  watch(
    () => [session.status, session.identity?.accountId] as const,
    () => {
      epoch++
      view.value = null
      busy.value = false
      message.value = ''
      if (session.status === 'anonymous') sessionStorage.removeItem(key)
      restore()
    },
    { flush: 'sync' },
  )
  restore()
  function save(r: Receipt): void {
    receipt.value = r
    sessionStorage.setItem(key, JSON.stringify(r))
  }
  async function query(show = true): Promise<void> {
    const r = receipt.value
    if (!r || busy.value || session.status !== 'authenticated') return
    const current = epoch
    busy.value = true
    try {
      let result: PlanView
      try {
        result = await getPlanOperation(r.operation, r.kind)
      } catch (error: unknown) {
        if (!(error instanceof ApiError) || error.status !== 404 || !r.workspace) throw error
        result = await getPlanWorkspace(r.workspace)
      }
      if (
        current !== epoch ||
        receipt.value?.operation !== r.operation ||
        receipt.value?.kind !== r.kind
      )
        return
      if (show) view.value = result
      unknown.value = false
      message.value = ''
      if (r.kind === 'GENERATE_PLAN' && result.operationId && result.operationId !== r.operation)
        save({ ...r, operation: result.operationId })
    } catch (error: unknown) {
      if (current !== epoch) return
      unknown.value = true
      view.value = null
      if (error instanceof ApiError && error.status === 404) {
        try {
          await getDeletion(r.resumeId)
          if (current === epoch) {
            sessionStorage.removeItem(key)
            receipt.value = null
            unknown.value = false
            message.value = '资料已删除，相关计划不可访问'
          }
        } catch {
          if (current === epoch) message.value = '暂未查得原操作，尚不能认定未受理；请继续核实'
        }
      } else message.value = error instanceof ApiError ? error.message : '暂时无法核实原计划操作'
    } finally {
      if (current === epoch) busy.value = false
    }
  }
  async function send(r: Receipt, path: string, body: unknown): Promise<PlanView | null> {
    const owner = session.identity?.accountId
    if (!owner || owner !== r.owner) return null
    const current = epoch
    busy.value = true
    message.value = ''
    let sent = false
    try {
      // 成功预检只核身份，不清空自己的在途状态，避免CREATE旧读回覆盖GENERATE未知回执。
      const identity = await getIdentity().catch(async (error: unknown) => {
        // 预检失败仍进入现有身份核实/隐藏流程，但绝不重发业务POST或抹除旧未知回执。
        await session.refreshIdentity()
        throw error
      })
      if (current !== epoch) return null
      if (identity.accountId !== owner) {
        await session.refreshIdentity()
        return null
      }
      const result = await writePlan(path, body, owner, r.operation, () => {
        if (current !== epoch || session.identity?.accountId !== owner)
          throw new ApiError(409, 'ACCOUNT_CHANGED', '身份已变化，请核实')
        save(r)
        unknown.value = true
        sent = true
      })
      if (current !== epoch) return null
      view.value = result
      unknown.value = false
      if (r.kind === 'GENERATE_PLAN' && result.operationId)
        save({ ...r, workspace: result.workspaceId, operation: result.operationId })
      return result
    } catch (error: unknown) {
      if (current === epoch)
        message.value = sent
          ? '结果尚待核实，请查询原操作；勿另建请求'
          : '本次安全预检未完成，尚未发送；原回执已保留'
      if (error instanceof ApiError && current === epoch && error.status !== null)
        message.value = error.message
      return null
    } finally {
      if (current === epoch) busy.value = false
    }
  }
  async function start(input: PlanInput): Promise<void> {
    const owner = session.identity?.accountId
    if (!owner || busy.value || unknown.value) return
    let draft = view.value?.state === 'DRAFT' ? view.value : null
    if (
      draft &&
      (draft.resumeId !== input.resumeId ||
        draft.resumeVersionId !== input.resumeVersionId ||
        draft.jdVersionId !== input.jdVersionId)
    ) {
      message.value = '当前工作稿属于另一固定资料，请先返回原记录核实'
      return
    }
    if (!draft) {
      if (receipt.value) return
      draft = await send(
        {
          owner,
          operation: crypto.randomUUID(),
          kind: 'CREATE_WORKSPACE',
          workspace: null,
          resumeId: input.resumeId,
        },
        '/api/plan-workspaces',
        input,
      )
    }
    if (!draft || draft.state !== 'DRAFT' || session.identity?.accountId !== owner) return
    await send(
      {
        owner,
        operation: crypto.randomUUID(),
        kind: 'GENERATE_PLAN',
        workspace: draft.workspaceId,
        resumeId: input.resumeId,
      },
      `/api/plan-workspaces/${draft.workspaceId}/generation`,
      {},
    )
  }
  async function resume(
    action: string,
    acceptUnknownCost: boolean,
    binding?: PlanOption,
  ): Promise<void> {
    const current = view.value
    const r = receipt.value
    if (
      !current?.operationId ||
      !r ||
      busy.value ||
      unknown.value ||
      r.workspace !== current.workspaceId
    )
      return
    await send(
      { ...r, kind: 'RESUME', operation: crypto.randomUUID(), workspace: current.workspaceId },
      `/api/plan-operations/${current.operationId}/resume`,
      {
        action,
        acceptUnknownCost,
        findingDigest: current.materialReview.digest,
        evidenceCollectionId: binding?.collectionId ?? null,
        evidenceConfigDigest: binding?.configDigest ?? null,
      },
    )
  }
  function newPlan(): void {
    if (!canNewPlan.value) return
    epoch++
    view.value = null
    receipt.value = null
    sessionStorage.removeItem(key)
    message.value = '这是另一份计划，原操作及其预算记录保留'
  }
  async function openLink(operation: string, kind: string): Promise<void> {
    if (session.status !== 'authenticated' || !session.identity) return
    const current = ++epoch
    view.value = null
    busy.value = true
    try {
      const result = await getPlanOperation(operation, kind)
      if (current !== epoch) return
      select(result)
    } catch {
      if (current === epoch) message.value = '原操作链接不可访问或暂不能核实'
    } finally {
      if (current === epoch) busy.value = false
    }
  }
  // 历史读取与待核实写回执分开；读取另一记录不能抹除原未知请求。
  function select(result: PlanView): void {
    view.value = result
    if (receipt.value && unknown.value) {
      message.value = '原请求仍待核实，历史记录只供回看；请先核实原操作，不能覆盖原回执'
      return
    }
    save({
      owner: session.identity!.accountId,
      workspace: result.workspaceId,
      resumeId: result.resumeId,
      operation: result.operationId ?? result.createOperationId,
      kind: result.operationId ? 'GENERATE_PLAN' : 'CREATE_WORKSPACE',
    })
    message.value = ''
  }
  async function openWorkspace(workspace: string): Promise<void> {
    if (session.status !== 'authenticated') return
    const current = ++epoch
    view.value = null
    busy.value = true
    message.value = ''
    try {
      const result = await getPlanWorkspace(workspace)
      if (current === epoch) select(result)
    } catch (e: unknown) {
      if (current === epoch) {
        view.value = null
        message.value = e instanceof ApiError ? e.message : '原工作稿暂不能核实'
      }
    } finally {
      if (current === epoch) busy.value = false
    }
  }
  function hide(): void {
    epoch++
    view.value = null
    busy.value = false
  }
  return {
    view,
    receipt,
    busy,
    message,
    unknown,
    query,
    start,
    resume,
    newPlan,
    canNewPlan,
    openLink,
    openWorkspace,
    hide,
  }
})
