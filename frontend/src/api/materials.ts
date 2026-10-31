import { clearCsrf, csrfHeader, getIdentity } from './auth'
import { ApiError, getJson, request, requireRecord } from './http'

export interface ResumeSummary {
  resumeId: string
  versionId: string
  versionNo: number
  codepointCount: number
  confirmedAt: string
}

export interface ResumeDetail extends ResumeSummary {
  body: string
}

export interface DeletionImpact {
  versionCount: number
  jdVersionCount: number
  fileCount: number
}

export interface DeletionStatus {
  requestId: string
  state: string
  requestedAt: string
  deadlineAt: string
  cleanedAt: string | null
}

function parseSummary(payload: unknown): ResumeSummary {
  const value = requireRecord(payload)
  if (
    typeof value.resumeId !== 'string' ||
    typeof value.versionId !== 'string' ||
    typeof value.versionNo !== 'number' ||
    typeof value.codepointCount !== 'number' ||
    typeof value.confirmedAt !== 'string'
  ) {
    throw new ApiError(null, 'INVALID_RESPONSE', '资料响应格式无法识别')
  }
  return {
    resumeId: value.resumeId,
    versionId: value.versionId,
    versionNo: value.versionNo,
    codepointCount: value.codepointCount,
    confirmedAt: value.confirmedAt,
  }
}

function parseDetail(payload: unknown): ResumeDetail {
  const summary = parseSummary(payload)
  const value = requireRecord(payload)
  if (typeof value.body !== 'string') {
    throw new ApiError(null, 'INVALID_RESPONSE', '资料正文响应格式无法识别')
  }
  return { ...summary, body: value.body }
}

function parseDeletion(payload: unknown): DeletionStatus {
  const value = requireRecord(payload)
  if (
    typeof value.requestId !== 'string' ||
    typeof value.state !== 'string' ||
    typeof value.requestedAt !== 'string' ||
    typeof value.deadlineAt !== 'string' ||
    (value.cleanedAt !== null && typeof value.cleanedAt !== 'string')
  ) {
    throw new ApiError(null, 'INVALID_RESPONSE', '删除状态响应格式无法识别')
  }
  return {
    requestId: value.requestId,
    state: value.state,
    requestedAt: value.requestedAt,
    deadlineAt: value.deadlineAt,
    cleanedAt: value.cleanedAt,
  }
}

async function readSuccess<T>(response: Response, parse: (payload: unknown) => T): Promise<T> {
  let payload: unknown
  try {
    payload = await response.json()
  } catch {
    throw new ApiError(null, 'RESULT_UNKNOWN', '资料操作结果暂时无法确认')
  }
  return parse(payload)
}

export interface MaterialWriteOwner {
  accountId: string
  isCurrent: () => boolean
}

export async function materialWriteRequest(
  path: string,
  init: RequestInit,
  owner: MaterialWriteOwner,
): Promise<Response> {
  try {
    let current
    try {
      current = await getIdentity()
    } catch (error: unknown) {
      if (error instanceof ApiError && error.status === 401) throw error
      throw new ApiError(409, 'IDENTITY_UNVERIFIED', '暂时无法核实登录身份，请重试')
    }
    if (!owner.isCurrent() || current.accountId !== owner.accountId) {
      throw new ApiError(409, 'ACCOUNT_CHANGED', '登录身份已变化，请核实后操作')
    }
    return await request(path, {
      ...init,
      headers: { ...init.headers, 'X-Expected-Account-Id': owner.accountId },
    })
  } catch (error: unknown) {
    if (error instanceof ApiError && error.code === 'CSRF_INVALID') clearCsrf()
    throw error
  }
}

export interface UploadStatus {
  uploadId: string
  operationId: string
  resumeId: string | null
  name: string
  state: string
  body: string | null
  errorCode: string | null
  versionId: string | null
}

function parseUpload(payload: unknown): UploadStatus {
  const v = requireRecord(payload)
  if (
    typeof v.uploadId !== 'string' ||
    typeof v.operationId !== 'string' ||
    typeof v.name !== 'string' ||
    typeof v.state !== 'string' ||
    (v.resumeId !== null && typeof v.resumeId !== 'string') ||
    (v.body !== null && typeof v.body !== 'string') ||
    (v.errorCode !== null && typeof v.errorCode !== 'string') ||
    (v.versionId !== null && typeof v.versionId !== 'string')
  ) {
    throw new ApiError(null, 'RESULT_UNKNOWN', '无法核实上传结果')
  }
  return v as unknown as UploadStatus
}

export function getUploadOperation(operation: string): Promise<UploadStatus> {
  return getJson(`/api/materials/uploads/operations/${encodeURIComponent(operation)}`, parseUpload)
}

export function getVersionUpload(versionId: string): Promise<UploadStatus> {
  return getJson(`/api/materials/uploads/versions/${encodeURIComponent(versionId)}`, parseUpload)
}

export function getUploadConfirmedVersion(upload: UploadStatus): Promise<ResumeDetail> {
  if (upload.state !== 'CONFIRMED' || !upload.resumeId || !upload.versionId) {
    throw new ApiError(409, 'UPLOAD_NOT_READY', '此上传尚未确认版本')
  }
  return getJson(
    `/api/materials/resumes/${encodeURIComponent(upload.resumeId)}/versions/${encodeURIComponent(upload.versionId)}`,
    parseDetail,
  )
}

export function listUploadDrafts(): Promise<UploadStatus[]> {
  return getJson('/api/materials/uploads', (payload: unknown) => {
    if (!Array.isArray(payload)) throw new ApiError(null, 'INVALID_RESPONSE', '无法核实上传列表')
    return payload.map(parseUpload)
  })
}

export async function uploadFile(
  file: File,
  root: string | null,
  operation: string,
  owner: MaterialWriteOwner,
): Promise<UploadStatus> {
  const form = new FormData()
  form.append('file', file)
  if (root) form.append('resumeId', root)
  return readSuccess(
    await materialWriteRequest(
      '/api/materials/uploads',
      {
        method: 'POST',
        headers: { ...(await csrfHeader()), 'X-Operation-Id': operation },
        body: form,
      },
      owner,
    ),
    parseUpload,
  )
}

export async function confirmUpload(
  id: string,
  body: string,
  operation: string,
  owner: MaterialWriteOwner,
): Promise<ResumeDetail> {
  return readSuccess(
    await materialWriteRequest(
      `/api/materials/uploads/${encodeURIComponent(id)}/confirmation`,
      {
        method: 'POST',
        headers: {
          ...(await csrfHeader()),
          'X-Operation-Id': operation,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ body }),
      },
      owner,
    ),
    parseDetail,
  )
}

export async function cancelUpload(id: string, owner: MaterialWriteOwner): Promise<void> {
  await materialWriteRequest(
    `/api/materials/uploads/${encodeURIComponent(id)}`,
    {
      method: 'DELETE',
      headers: { ...(await csrfHeader()) },
    },
    owner,
  )
}

export async function downloadUpload(id: string): Promise<Blob> {
  return (await request(`/api/materials/uploads/${encodeURIComponent(id)}/original`)).blob()
}

export async function listResumes(): Promise<ResumeSummary[]> {
  return getJson('/api/materials/resumes', (payload: unknown) => {
    if (!Array.isArray(payload)) {
      throw new ApiError(null, 'INVALID_RESPONSE', '资料列表格式无法识别')
    }
    return payload.map(parseSummary)
  })
}

export function getResume(resumeId: string): Promise<ResumeDetail> {
  return getJson(`/api/materials/resumes/${encodeURIComponent(resumeId)}`, parseDetail)
}

export function getCreatedOperation(operationId: string): Promise<ResumeDetail> {
  return getJson(
    `/api/materials/resumes/operations/${encodeURIComponent(operationId)}`,
    parseDetail,
  )
}

export async function createResume(
  body: string,
  operationId: string,
  owner: MaterialWriteOwner,
): Promise<ResumeDetail> {
  let attempted = false
  try {
    const response = await materialWriteRequest(
      '/api/materials/resumes',
      {
        method: 'POST',
        headers: {
          ...(await csrfHeader()),
          'Content-Type': 'application/json',
          'X-Operation-Id': operationId,
        },
        body: JSON.stringify({ body }),
      },
      owner,
    )
    attempted = true
    return await readSuccess(response, parseDetail)
  } catch (error: unknown) {
    if (!attempted && error instanceof ApiError && error.status !== null && error.status < 500) {
      throw error
    }
    try {
      return await getCreatedOperation(operationId)
    } catch {
      throw new ApiError(null, 'RESULT_UNKNOWN', '暂时无法确认是否保存；重试会沿用同一次操作')
    }
  }
}

export function deletionImpact(resumeId: string): Promise<DeletionImpact> {
  return getJson(
    `/api/materials/resumes/${encodeURIComponent(resumeId)}/deletion-impact`,
    (payload) => {
      const value = requireRecord(payload)
      if (typeof value.versionCount !== 'number' || typeof value.jdVersionCount !== 'number') {
        throw new ApiError(null, 'INVALID_RESPONSE', '删除范围响应格式无法识别')
      }
      return {
        versionCount: value.versionCount,
        jdVersionCount: value.jdVersionCount,
        fileCount: typeof value.fileCount === 'number' ? value.fileCount : 0,
      }
    },
  )
}

export type VersionKind = 'resume' | 'jd'

function versionPath(resumeId: string, kind: VersionKind): string {
  return `/api/materials/resumes/${encodeURIComponent(resumeId)}/${kind === 'jd' ? 'jd-versions' : 'versions'}`
}

export function listVersions(resumeId: string, kind: VersionKind): Promise<ResumeSummary[]> {
  return getJson(versionPath(resumeId, kind), (payload) => {
    if (!Array.isArray(payload))
      throw new ApiError(null, 'INVALID_RESPONSE', '版本列表格式无法识别')
    return payload.map(parseSummary)
  })
}

export function getVersion(
  resumeId: string,
  kind: VersionKind,
  versionId: string,
): Promise<ResumeDetail> {
  return getJson(`${versionPath(resumeId, kind)}/${encodeURIComponent(versionId)}`, parseDetail)
}

export function getVersionOperation(
  resumeId: string,
  kind: VersionKind,
  operationId: string,
): Promise<ResumeDetail> {
  const operationPath = kind === 'jd' ? 'jd-operations' : 'operations'
  return getJson(
    `/api/materials/resumes/${encodeURIComponent(resumeId)}/${operationPath}/${encodeURIComponent(operationId)}`,
    parseDetail,
  )
}

export async function confirmVersion(
  resumeId: string,
  kind: VersionKind,
  body: string,
  operationId: string,
  owner: MaterialWriteOwner,
): Promise<ResumeDetail> {
  let acceptedResponse = false
  try {
    const response = await materialWriteRequest(
      versionPath(resumeId, kind),
      {
        method: 'POST',
        headers: {
          ...(await csrfHeader()),
          'Content-Type': 'application/json',
          'X-Operation-Id': operationId,
        },
        body: JSON.stringify({ body }),
      },
      owner,
    )
    acceptedResponse = true
    return await readSuccess(response, parseDetail)
  } catch (error: unknown) {
    if (
      !acceptedResponse &&
      error instanceof ApiError &&
      error.status !== null &&
      error.status < 500
    )
      throw error
    try {
      return await getVersionOperation(resumeId, kind, operationId)
    } catch {
      throw new ApiError(null, 'RESULT_UNKNOWN', '暂时无法确认版本是否保存，请核实原操作')
    }
  }
}

export function getDeletion(resumeId: string): Promise<DeletionStatus> {
  return getJson(`/api/materials/resumes/${encodeURIComponent(resumeId)}/deletion`, parseDeletion)
}

export async function deleteResume(
  resumeId: string,
  owner: MaterialWriteOwner,
): Promise<DeletionStatus> {
  try {
    const response = await materialWriteRequest(
      `/api/materials/resumes/${encodeURIComponent(resumeId)}`,
      {
        method: 'DELETE',
        headers: await csrfHeader(),
      },
      owner,
    )
    return await readSuccess(response, parseDeletion)
  } catch (error: unknown) {
    if (error instanceof ApiError && error.status !== null && error.status < 500) throw error
    try {
      return await getDeletion(resumeId)
    } catch {
      throw new ApiError(null, 'RESULT_UNKNOWN', '暂时无法确认删除结果，请先核实状态')
    }
  }
}
