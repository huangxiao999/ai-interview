import { csrfHeader } from './auth'
import { ApiError, getJson, request, requireRecord } from './http'
import type { PlanOption } from './plans'

export interface RevisionTopic {
  topicId: string
  order: number
  name: string
  coverage: PlanOption
  questionCount: number
  difficulty: string
  reason: string
  resumeQuote: string
  jdQuote: string
  evidenceRefs: string[]
}
export interface RevisionView {
  planId: string
  workspaceId: string
  resumeId: string
  currentRevisionId: string
  revision: {
    id: string
    sequence: number
    parentRevisionId: string | null
    createdAt: string
    content: {
      revisionPolicy: string
      totalQuestions: number
      estimatedMinutes: number | null
      topics: RevisionTopic[]
    }
  }
  current: boolean
  confirmed: boolean
  confirmationId: string | null
  confirmedAt: string | null
  canEdit: boolean
  canConfirm: boolean
  eligibility: string
  options: { binding: PlanOption; name: string; evidenceRefs: string[] }[]
}
function str(v: unknown): string {
  if (typeof v !== 'string') throw invalid()
  return v
}
function num(v: unknown): number {
  if (typeof v !== 'number' || !Number.isFinite(v)) throw invalid()
  return v
}
function bool(v: unknown): boolean {
  if (typeof v !== 'boolean') throw invalid()
  return v
}
function nullable(v: unknown): string | null {
  return v === null ? null : str(v)
}
function array(v: unknown): unknown[] {
  if (!Array.isArray(v)) throw invalid()
  return v
}
function invalid(): ApiError {
  return new ApiError(null, 'INVALID_RESPONSE', '计划修订响应无法核实')
}
function binding(raw: unknown): PlanOption {
  const b = requireRecord(raw)
  return {
    collectionId: str(b.collectionId),
    configDigest: str(b.configDigest),
    technology: str(b.technology),
    technologyVersion: str(b.technologyVersion),
    scenario: str(b.scenario),
    claimKey: str(b.claimKey),
  }
}
function parse(raw: unknown): RevisionView {
  const v = requireRecord(raw),
    r = requireRecord(v.revision),
    c = requireRecord(r.content)
  return {
    planId: str(v.planId),
    workspaceId: str(v.workspaceId),
    resumeId: str(v.resumeId),
    currentRevisionId: str(v.currentRevisionId),
    revision: {
      id: str(r.id),
      sequence: num(r.sequence),
      parentRevisionId: nullable(r.parentRevisionId),
      createdAt: str(r.createdAt),
      content: {
        revisionPolicy: str(c.revisionPolicy),
        totalQuestions: num(c.totalQuestions),
        estimatedMinutes: c.estimatedMinutes === null ? null : num(c.estimatedMinutes),
        topics: array(c.topics).map((raw) => {
          const t = requireRecord(raw)
          return {
            topicId: str(t.topicId),
            order: num(t.order),
            name: str(t.name),
            coverage: binding(t.coverage),
            questionCount: num(t.questionCount),
            difficulty: str(t.difficulty),
            reason: str(t.reason),
            resumeQuote: str(t.resumeQuote),
            jdQuote: str(t.jdQuote),
            evidenceRefs: array(t.evidenceRefs).map(str),
          }
        }),
      },
    },
    current: bool(v.current),
    confirmed: bool(v.confirmed),
    confirmationId: nullable(v.confirmationId),
    confirmedAt: nullable(v.confirmedAt),
    canEdit: bool(v.canEdit),
    canConfirm: bool(v.canConfirm),
    eligibility: str(v.eligibility),
    options: array(v.options).map((raw) => {
      const o = requireRecord(raw)
      return {
        binding: binding(o.binding),
        name: str(o.name),
        evidenceRefs: array(o.evidenceRefs).map(str),
      }
    }),
  }
}
export function currentRevision(plan: string): Promise<RevisionView> {
  return getJson(`/api/plans/${encodeURIComponent(plan)}/revisions/current`, parse)
}
export function revisionOperation(operation: string): Promise<RevisionView> {
  return getJson(`/api/plan-revision-operations/${encodeURIComponent(operation)}`, parse)
}
export async function writeRevision(
  plan: string,
  kind: 'SAVE' | 'CONFIRM',
  body: unknown,
  owner: string,
  operation: string,
  beforeSend: () => void,
): Promise<RevisionView> {
  const security = await csrfHeader()
  beforeSend()
  const response = await request(
    `/api/plans/${encodeURIComponent(plan)}/${kind === 'SAVE' ? 'revisions' : 'confirmations'}`,
    {
      method: 'POST',
      headers: {
        ...security,
        'Content-Type': 'application/json',
        'X-Expected-Account-Id': owner,
        'X-Operation-Id': operation,
      },
      body: JSON.stringify(body),
    },
  )
  return parse(await response.json())
}
