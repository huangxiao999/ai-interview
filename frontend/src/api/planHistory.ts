import { ApiError, getJson, requireRecord } from './http'
export interface PlanHistoryEntry {
  workspaceId: string
  createOperationId: string
  operationId: string | null
  resumeId: string
  resumeVersionId: string
  jdVersionId: string
  resumeVersion: number
  jdVersion: number
  createdAt: string
  state: string
  reason: string | null
  materialsCurrent: boolean
  planId: string | null
  revisionId: string | null
  confirmed: boolean
  eligibility: string
}
export interface PlanHistoryPage {
  items: PlanHistoryEntry[]
  nextCursor: string | null
}
function text(v: unknown): string {
  if (typeof v !== 'string') throw new ApiError(null, 'INVALID_RESPONSE', '历史身份无法核实')
  return v
}
function nullable(v: unknown): string | null {
  return v === null ? null : text(v)
}
function flag(v: unknown): boolean {
  if (typeof v !== 'boolean') throw new ApiError(null, 'INVALID_RESPONSE', '历史状态无法核实')
  return v
}
function version(v: unknown): number {
  if (typeof v !== 'number' || !Number.isInteger(v) || v < 1)
    throw new ApiError(null, 'INVALID_RESPONSE', '历史版本无法核实')
  return v
}
export function listPlanHistory(root: string, cursor: string | null): Promise<PlanHistoryPage> {
  const params = new URLSearchParams({ limit: '20' })
  if (root) params.set('resumeId', root)
  if (cursor) params.set('cursor', cursor)
  return getJson(`/api/plan-workspaces?${params}`, (raw) => {
    const p = requireRecord(raw)
    if (!Array.isArray(p.items)) throw new ApiError(null, 'INVALID_RESPONSE', '历史列表无法核实')
    return {
      nextCursor: nullable(p.nextCursor),
      items: p.items.map((raw) => {
        const r = requireRecord(raw)
        return {
          workspaceId: text(r.workspaceId),
          createOperationId: text(r.createOperationId),
          operationId: nullable(r.operationId),
          resumeId: text(r.resumeId),
          resumeVersionId: text(r.resumeVersionId),
          jdVersionId: text(r.jdVersionId),
          resumeVersion: version(r.resumeVersion),
          jdVersion: version(r.jdVersion),
          createdAt: text(r.createdAt),
          state: text(r.state),
          reason: nullable(r.reason),
          materialsCurrent: flag(r.materialsCurrent),
          planId: nullable(r.planId),
          revisionId: nullable(r.revisionId),
          confirmed: flag(r.confirmed),
          eligibility: text(r.eligibility),
        }
      }),
    }
  })
}
