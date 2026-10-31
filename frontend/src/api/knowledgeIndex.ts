import { csrfHeader, getIdentity } from './auth'
import { ApiError, getJson, request, requireRecord } from './http'

export interface IndexBatch {
  batchId: string
  ordinal: number
  state: string
  attempts: number
  remaining: number
  generation: number
  attemptId: string | null
  attemptState: string | null
  deadline: string | null
}
export interface IndexBuild {
  buildId: string
  documentId: string
  versionId: string
  state: string
  errorCategory: string | null
  versionDigest: string
  configDigest: string
  policy: string
  batches: IndexBatch[]
}
export type IndexOperation = {
  id: string
  accountId: string
} & (
  | { kind: 'CREATE'; root: string; version: string; digest: string }
  | { kind: 'RESUME'; build: string; attemptId: string | null; acknowledge: boolean }
)
const base = '/api/maintenance/knowledge'
function parseBuild(payload: unknown): IndexBuild {
  const v = requireRecord(payload)
  if (
    ['buildId', 'documentId', 'versionId', 'state', 'versionDigest', 'configDigest', 'policy'].some(
      (k) => typeof v[k] !== 'string',
    ) ||
    (v.errorCategory !== null && typeof v.errorCategory !== 'string') ||
    !Array.isArray(v.batches)
  )
    throw new ApiError(null, 'INVALID_RESPONSE', '构建响应无法识别')
  for (const value of v.batches) {
    const b = requireRecord(value)
    if (
      ['batchId', 'state'].some((k) => typeof b[k] !== 'string') ||
      ['ordinal', 'attempts', 'remaining', 'generation'].some((k) => typeof b[k] !== 'number') ||
      ['attemptId', 'attemptState', 'deadline'].some(
        (k) => b[k] !== null && typeof b[k] !== 'string',
      )
    )
      throw new ApiError(null, 'INVALID_RESPONSE', '批次响应无法识别')
  }
  return v as unknown as IndexBuild
}
export function indexBuilds(root: string, version: string): Promise<IndexBuild[]> {
  return getJson(`${base}/documents/${root}/versions/${version}/index-builds`, (payload) => {
    if (!Array.isArray(payload)) throw new ApiError(null, 'INVALID_RESPONSE', '构建列表无法识别')
    return payload.map(parseBuild)
  })
}
export function readIndexBuild(id: string): Promise<IndexBuild> {
  return getJson(`${base}/index-builds/${id}`, parseBuild)
}
export function readIndexOperation(id: string): Promise<IndexBuild> {
  return getJson(`${base}/index-operations/${id}`, parseBuild)
}
export async function sendIndexOperation(
  op: IndexOperation,
  beforeDispatch: () => void,
): Promise<IndexBuild> {
  const identity = await getIdentity()
  if (identity.accountId !== op.accountId || !identity.authorities.includes('KNOWLEDGE_MAINTAINER'))
    throw new ApiError(409, 'ACCOUNT_CHANGED', '身份已变化，请重新核实')
  const headers = await csrfHeader()
  const path =
    op.kind === 'CREATE'
      ? `/documents/${op.root}/versions/${op.version}/index-builds`
      : `/index-builds/${op.build}/resume`
  const body =
    op.kind === 'CREATE'
      ? { digest: op.digest }
      : { attemptId: op.attemptId, acceptPossibleDuplicateCharge: op.acknowledge }
  // me和CSRF预检都已成功；从这一刻起才需要跨刷新保留结果未知回执。
  beforeDispatch()
  const response = await request(base + path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...headers,
      'X-Expected-Account-Id': op.accountId,
      'X-Operation-Id': op.id,
    },
    body: JSON.stringify(body),
  })
  try {
    return parseBuild(await response.json())
  } catch {
    throw new ApiError(null, 'RESULT_UNKNOWN', '原构建操作结果待核实')
  }
}
