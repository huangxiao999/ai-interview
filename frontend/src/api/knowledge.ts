import { csrfHeader } from './auth'
import { ApiError, getJson, request, requireRecord } from './http'

export interface KnowledgeInput {
  title: string
  body: string
  sourceUrl: string
  sourceAnchor: string
  applicability: string
  usageNote: string
  verificationNote: string
  verifiedOn: string
}
export interface KnowledgeVersion extends KnowledgeInput {
  documentId: string
  versionId: string
  versionNo: number
  digest: string
  state: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED'
  submittedAt: string
  reviewReason: string | null
  reviewerId: string | null
  reviewedAt: string | null
}
export type KnowledgeOperation =
  | { id: string; accountId: string; kind: 'SUBMIT'; root: string | null; input: KnowledgeInput }
  | {
      id: string
      accountId: string
      kind: 'REVIEW'
      root: string
      version: string
      digest: string
      decision: 'APPROVE' | 'REJECT'
      reason: string
    }

function parseVersion(payload: unknown): KnowledgeVersion {
  const v = requireRecord(payload)
  const keys = [
    'title',
    'body',
    'sourceUrl',
    'sourceAnchor',
    'applicability',
    'usageNote',
    'verificationNote',
    'verifiedOn',
    'documentId',
    'versionId',
    'digest',
    'submittedAt',
  ] as const
  if (
    keys.some((key) => typeof v[key] !== 'string') ||
    typeof v.versionNo !== 'number' ||
    !['PENDING_REVIEW', 'APPROVED', 'REJECTED'].includes(String(v.state)) ||
    ['reviewReason', 'reviewerId', 'reviewedAt'].some(
      (key) => v[key] !== null && typeof v[key] !== 'string',
    )
  ) {
    throw new ApiError(null, 'INVALID_RESPONSE', '知识版本响应格式无法识别')
  }
  return v as unknown as KnowledgeVersion
}

const base = '/api/maintenance/knowledge'
export function listKnowledge(): Promise<KnowledgeVersion[]> {
  return getJson(`${base}/documents`, (payload) => {
    if (!Array.isArray(payload))
      throw new ApiError(null, 'INVALID_RESPONSE', '版本列表格式无法识别')
    return payload.map(parseVersion)
  })
}
export async function submitKnowledge(op: KnowledgeOperation): Promise<KnowledgeVersion> {
  const path =
    op.kind === 'SUBMIT'
      ? op.root
        ? `/documents/${op.root}/versions`
        : '/documents'
      : `/documents/${op.root}/versions/${op.version}/review`
  const body =
    op.kind === 'SUBMIT'
      ? op.input
      : { digest: op.digest, decision: op.decision, reason: op.reason }
  const response = await request(base + path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(await csrfHeader()),
      'X-Expected-Account-Id': op.accountId,
      'X-Operation-Id': op.id,
    },
    body: JSON.stringify(body),
  })
  try {
    return parseVersion(await response.json())
  } catch {
    throw new ApiError(null, 'RESULT_UNKNOWN', '未收到完整结果，请核实原操作')
  }
}
export function readKnowledgeOperation(id: string): Promise<KnowledgeVersion> {
  return getJson(`${base}/operations/${id}`, (payload) => {
    const value = requireRecord(payload)
    if (value.operationId !== id) throw new ApiError(null, 'INVALID_RESPONSE', '操作身份不一致')
    return parseVersion(value.version)
  })
}
