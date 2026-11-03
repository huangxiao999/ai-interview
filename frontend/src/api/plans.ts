import { csrfHeader } from './auth'
import { ApiError, getJson, request, requireRecord } from './http'

export interface PlanInput {
  resumeId: string
  resumeVersionId: string
  jdVersionId: string
  collectionId: string
  configDigest: string
  technology: string
  technologyVersion: string
  scenario: string
  claimKey: string
  materialsConfirmed: boolean
  restrictedCoverageConfirmed: boolean
  newPlanIntent: boolean
}
export interface PlanOption {
  collectionId: string
  configDigest: string
  technology: string
  technologyVersion: string
  scenario: string
  claimKey: string
}
export interface PlanTopic {
  name: string
  questionCount: number
  difficulty: string
  reason: string
  resumeQuote: string
  jdQuote: string
}
export interface PlanView {
  workspaceId: string
  operationId: string | null
  resumeId: string
  resumeVersionId: string
  jdVersionId: string
  state: string
  reason: string | null
  initialUsed: number
  autoUsed: number
  manualUsed: number
  queryUsed: number
  generation: number
  eligibility: string
  materialsCurrent: boolean
  knowledgeBinding: PlanOption
  canAcceptCandidate: boolean
  canRetry: boolean
  requiresCostConfirmation: boolean
  materialReview: {
    state: string
    digest: string | null
    findings: { kind: string; source: string; quote: string }[]
  }
  plan: {
    planId: string
    revisionId: string
    sources: { versionId: string; sourceUrl: string; sourceAnchor: string }[]
    content: { totalQuestions: number; estimatedMinutes: number | null; topics: PlanTopic[] }
  } | null
}
function text(value: unknown): string {
  if (typeof value !== 'string') throw new ApiError(null, 'INVALID_RESPONSE', '计划响应无法识别')
  return value
}
function number(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value))
    throw new ApiError(null, 'INVALID_RESPONSE', '计划数值无法识别')
  return value
}
function nullableText(value: unknown): string | null {
  return value === null ? null : text(value)
}
function flag(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new ApiError(null, 'INVALID_RESPONSE', '计划资格无法核实')
  return value
}
function option(payload: unknown): PlanOption {
  const o = requireRecord(payload)
  return {
    collectionId: text(o.collectionId),
    configDigest: text(o.configDigest),
    technology: text(o.technology),
    technologyVersion: text(o.technologyVersion),
    scenario: text(o.scenario),
    claimKey: text(o.claimKey),
  }
}
function topic(payload: unknown): PlanTopic {
  const v = requireRecord(payload)
  return {
    name: text(v.name),
    questionCount: number(v.questionCount),
    difficulty: text(v.difficulty),
    reason: text(v.reason),
    resumeQuote: text(v.resumeQuote),
    jdQuote: text(v.jdQuote),
  }
}
function parseView(payload: unknown): PlanView {
  const v = requireRecord(payload)
  const review = requireRecord(v.materialReview)
  if (review.findings !== null && !Array.isArray(review.findings))
    throw new ApiError(null, 'INVALID_RESPONSE', '资料核对明细无法识别')
  const p = v.plan === null ? null : requireRecord(v.plan)
  const content = p === null ? null : requireRecord(p.content)
  if (p && !Array.isArray(p.sources)) throw new ApiError(null, 'INVALID_RESPONSE', '来源无法核实')
  if (typeof v.materialsCurrent !== 'boolean' || (content && !Array.isArray(content.topics)))
    throw new ApiError(null, 'INVALID_RESPONSE', '计划内容无法核实')
  return {
    workspaceId: text(v.workspaceId),
    operationId: nullableText(v.operationId),
    resumeId: text(v.resumeId),
    resumeVersionId: text(v.resumeVersionId),
    jdVersionId: text(v.jdVersionId),
    state: text(v.state),
    reason: nullableText(v.reason),
    initialUsed: number(v.initialUsed),
    autoUsed: number(v.autoUsed),
    manualUsed: number(v.manualUsed),
    queryUsed: number(v.queryUsed),
    generation: number(v.generation),
    eligibility: text(v.eligibility),
    materialsCurrent: v.materialsCurrent,
    knowledgeBinding: option(v.knowledgeBinding),
    canAcceptCandidate: flag(v.canAcceptCandidate),
    canRetry: flag(v.canRetry),
    requiresCostConfirmation: flag(v.requiresCostConfirmation),
    materialReview: {
      state: text(review.state),
      digest: nullableText(review.digest),
      findings:
        review.findings === null
          ? []
          : (review.findings as unknown[]).map((raw) => {
              const f = requireRecord(raw)
              return { kind: text(f.kind), source: text(f.source), quote: text(f.quote) }
            }),
    },
    plan:
      p && content
        ? {
            planId: text(p.planId),
            revisionId: text(p.revisionId),
            sources: (p.sources as unknown[]).map((raw) => {
              const s = requireRecord(raw)
              return {
                versionId: text(s.versionId),
                sourceUrl: text(s.sourceUrl),
                sourceAnchor: text(s.sourceAnchor),
              }
            }),
            content: {
              totalQuestions: number(content.totalQuestions),
              estimatedMinutes:
                content.estimatedMinutes === null ? null : number(content.estimatedMinutes),
              topics: (content.topics as unknown[]).map(topic),
            },
          }
        : null,
  }
}
export function planPreparation(): Promise<{ available: boolean; options: PlanOption[] }> {
  return getJson('/api/plans/preparation', (payload) => {
    const v = requireRecord(payload)
    if (typeof v.available !== 'boolean' || !Array.isArray(v.options))
      throw new ApiError(null, 'INVALID_RESPONSE', '知识范围响应无法核实')
    return {
      available: v.available,
      options: v.options.map((raw: unknown) => {
        const o = requireRecord(raw)
        return {
          collectionId: text(o.collectionId),
          configDigest: text(o.configDigest),
          technology: text(o.technology),
          technologyVersion: text(o.technologyVersion),
          scenario: text(o.scenario),
          claimKey: text(o.claimKey),
        }
      }),
    }
  })
}
export function getPlanOperation(id: string, kind: string): Promise<PlanView> {
  return getJson(
    `/api/plan-operations/${encodeURIComponent(id)}?kind=${encodeURIComponent(kind)}`,
    parseView,
  )
}
export function getPlanWorkspace(id: string): Promise<PlanView> {
  return getJson(`/api/plan-workspaces/${encodeURIComponent(id)}`, parseView)
}
export async function writePlan(
  path: string,
  body: unknown,
  owner: string,
  operation: string,
  beforeSend: () => void,
): Promise<PlanView> {
  const security = await csrfHeader()
  // 安全预检完成、真正业务POST前才登记回执；抛错不得假称未确认的旧操作未发送。
  beforeSend()
  const response = await request(path, {
    method: 'POST',
    headers: {
      ...security,
      'Content-Type': 'application/json',
      'X-Expected-Account-Id': owner,
      'X-Operation-Id': operation,
    },
    body: JSON.stringify(body),
  })
  return parseView(await response.json())
}
