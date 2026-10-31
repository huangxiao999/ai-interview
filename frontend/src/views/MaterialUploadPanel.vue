<script setup lang="ts">
import { useMaterialsStore } from '../stores/materials'
import { useSessionStore } from '../stores/session'
import { ApiError } from '../api/http'
import {
  cancelUpload,
  confirmUpload,
  downloadUpload,
  getUploadOperation,
  getVersionUpload,
  getUploadConfirmedVersion,
  listUploadDrafts,
  getDeletion,
  uploadFile,
} from '../api/materials'
import type { UploadStatus } from '../api/materials'

const materials = useMaterialsStore()
const session = useSessionStore()
const emit = defineEmits<{ saved: [] }>()
const stateNames: Record<string, string> = {
  RECEIVING: '正在接收',
  QUEUED: '等待解析',
  PARSING: '解析中',
  READY: '等待核对正文',
  CONFIRMED: '已经确认版本',
  FAILED: '解析失败',
}
const failureNames: Record<string, string> = {
  PDF_PAGE_LIMIT: 'PDF 超过20页',
  NO_TEXT: '未提取到可读取正文',
  TEXT_TOO_LONG: '解析正文超过20,000码点，不会自动截断',
  PARSE_TIMEOUT: '解析超过30秒',
  PARSE_INTERRUPTED: '解析因应用中断而结束',
  PARSE_FAILED: '文件损坏、不受支持或安全检查未通过',
}

function owner() {
  const accountId = session.identity?.accountId ?? ''
  const generation = materials.generation
  return {
    accountId,
    ownsState: () => materials.generation === generation,
    isCurrent: () =>
      session.status === 'authenticated' &&
      session.identity?.accountId === accountId &&
      materials.generation === generation,
  }
}

function failure(error: unknown): void {
  materials.uploadMessage =
    error instanceof ApiError ? error.message : '暂时无法核实，请保留原操作重试'
  if (
    error instanceof ApiError &&
    (error.status === 401 ||
      ['CSRF_INVALID', 'ACCOUNT_CHANGED', 'IDENTITY_UNVERIFIED'].includes(error.code))
  ) {
    void session.refreshIdentity()
  }
}

async function choose(event: Event): Promise<void> {
  if (
    materials.pendingUpload ||
    materials.materialsBusy ||
    materials.pendingOperation ||
    materials.pendingVersionSave ||
    materials.pendingDelete
  )
    return
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 10000000 || file.size === 0 || !/\.(pdf|docx)$/i.test(file.name)) {
    materials.uploadMessage = '请选择不超过 10,000,000 字节的 PDF 或 DOCX'
    return
  }
  if (
    (materials.draft || materials.resumeVersionDraft) &&
    !window.confirm('上传文件将替换尚未保存的简历正文草稿，JD草稿会保留。确定替换？')
  )
    return
  materials.draft = ''
  materials.resumeVersionDraft = ''
  materials.pendingUpload = {
    operationId: crypto.randomUUID(),
    file,
    root: materials.selected?.resumeId ?? null,
    status: null,
    body: '',
    confirmOperation: null,
    confirmedBody: null,
  }
  await send()
}

async function send(): Promise<void> {
  const pending = materials.pendingUpload
  if (!pending || materials.materialsBusy) return
  const actor = owner()
  materials.materialsBusy = true
  try {
    const result = await uploadFile(pending.file, pending.root, pending.operationId, actor)
    if (actor.isCurrent() && materials.pendingUpload === pending) {
      pending.status = result
      if (result.state === 'READY') pending.body = result.body ?? ''
    }
  } catch (error: unknown) {
    if (actor.isCurrent()) {
      failure(error)
      if (error instanceof ApiError && [400, 413].includes(error.status ?? 0)) {
        // 文件固定且服务器在登记前完成类型/大小校验；确定拒绝可重新选文件。
        materials.pendingUpload = null
      }
    }
  } finally {
    if (actor.ownsState()) materials.materialsBusy = false
  }
}

async function verify(): Promise<void> {
  const pending = materials.pendingUpload
  if (!pending || materials.materialsBusy) return
  const actor = owner()
  materials.materialsBusy = true
  try {
    const result = await getUploadOperation(pending.operationId)
    if (!actor.isCurrent() || materials.pendingUpload !== pending) return
    if (result.state === 'READY' && pending.status?.state !== 'READY')
      pending.body = result.body ?? ''
    pending.status = result
    if (['CLEANED', 'DELETE_PENDING'].includes(result.state)) {
      if (result.resumeId && materials.selected?.resumeId === result.resumeId)
        materials.selected = null
      if (result.resumeId && materials.versionResumeId === result.resumeId)
        materials.selectVersionRoot(null)
      materials.pendingUpload = null
      materials.uploadMessage = '已核实原上传已放弃，可继续其他资料操作'
      materials.invalidateListRequests()
      emit('saved')
      return
    }
    if (result.state === 'FAILED') {
      pending.confirmOperation = null
      pending.confirmedBody = null
    }
    materials.uploadMessage =
      result.state === 'FAILED' ? '解析失败，请放弃此上传后粘贴正文继续。' : '已核实文件状态'
  } catch (error: unknown) {
    if (actor.isCurrent()) {
      if (error instanceof ApiError && error.status === 404 && pending.status?.resumeId) {
        try {
          await getDeletion(pending.status.resumeId)
          if (actor.isCurrent()) {
            materials.pendingUpload = null
            materials.uploadMessage = '所属简历已删除，原上传已禁访'
          }
        } catch {
          failure(error)
        }
      } else failure(error)
    }
  } finally {
    if (actor.ownsState()) materials.materialsBusy = false
  }
}

async function loadDrafts(): Promise<void> {
  if (materials.materialsBusy) return
  const actor = owner()
  materials.materialsBusy = true
  try {
    const drafts = await listUploadDrafts()
    if (actor.isCurrent()) {
      materials.uploadDrafts = drafts
      materials.uploadListLoaded = true
    }
  } catch (error: unknown) {
    if (actor.isCurrent()) failure(error)
  } finally {
    if (actor.ownsState()) materials.materialsBusy = false
  }
}

function restore(draft: UploadStatus): void {
  if (
    materials.pendingUpload ||
    materials.materialsBusy ||
    materials.pendingOperation ||
    materials.pendingVersionSave ||
    materials.pendingDelete
  )
    return
  materials.pendingUpload = {
    operationId: draft.operationId,
    file: new File([], draft.name),
    root: draft.resumeId,
    status: draft,
    body: draft.body ?? '',
    confirmOperation: null,
    confirmedBody: null,
  }
  materials.uploadDrafts = []
}

async function confirm(): Promise<void> {
  const pending = materials.pendingUpload
  if (!pending || materials.materialsBusy) return
  const actor = owner()
  // 规范化与码点边界统一由既有服务端规则裁决；拒绝后保留上传并允许继续编辑。
  pending.confirmOperation ??= crypto.randomUUID()
  pending.confirmedBody ??= pending.body
  const id = pending.status?.uploadId
  if (!id) return
  materials.materialsBusy = true
  materials.invalidateListRequests()
  try {
    const detail = await confirmUpload(id, pending.confirmedBody, pending.confirmOperation, actor)
    if (!actor.isCurrent() || materials.pendingUpload !== pending) return
    materials.pendingUpload = null
    materials.selected = detail
    materials.uploadMessage = '正文已确认保存；原文件通过鉴权接口读取'
    emit('saved')
  } catch (error: unknown) {
    if (actor.isCurrent()) {
      failure(error)
      if (error instanceof ApiError && ['FILE_UNAVAILABLE', 'INVALID_INPUT'].includes(error.code)) {
        // 这些拒绝发生在版本接受前；仍保留上传本身，允许核对或放弃。
        pending.confirmOperation = null
        pending.confirmedBody = null
      }
    }
  } finally {
    if (actor.ownsState()) materials.materialsBusy = false
  }
}

async function discard(): Promise<void> {
  const pending = materials.pendingUpload
  if (!pending || !pending.status || materials.materialsBusy || pending.confirmOperation) return
  const actor = owner()
  materials.materialsBusy = true
  try {
    await cancelUpload(pending.status.uploadId, actor)
    if (actor.isCurrent()) {
      materials.pendingUpload = null
      materials.uploadMessage = '上传已放弃，可粘贴正文继续'
    }
  } catch (error: unknown) {
    if (actor.isCurrent()) failure(error)
  } finally {
    if (actor.ownsState()) materials.materialsBusy = false
  }
}

async function openConfirmed(): Promise<void> {
  const pending = materials.pendingUpload
  if (!pending?.status || pending.status.state !== 'CONFIRMED' || materials.materialsBusy) return
  const actor = owner()
  materials.materialsBusy = true
  try {
    const saved = await getUploadConfirmedVersion(pending.status)
    if (!actor.isCurrent() || materials.pendingUpload !== pending) return
    materials.selected = saved
    materials.pendingUpload = null
    materials.uploadMessage = '已打开此上传绑定的确认版本，未覆盖旧版'
    emit('saved')
  } catch (error: unknown) {
    if (actor.isCurrent()) failure(error)
  } finally {
    if (actor.ownsState()) materials.materialsBusy = false
  }
}

async function download(): Promise<void> {
  const pending = materials.pendingUpload
  if (!pending?.status) return
  const actor = owner()
  try {
    const blob = await downloadUpload(pending.status.uploadId)
    if (!actor.isCurrent()) return
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = pending.file.name
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error: unknown) {
    if (actor.isCurrent()) failure(error)
  }
}

async function downloadVersion(): Promise<void> {
  const selected = materials.selected
  if (!selected) return
  const actor = owner()
  try {
    const upload = await getVersionUpload(selected.versionId)
    const blob = await downloadUpload(upload.uploadId)
    if (!actor.isCurrent() || materials.selected?.versionId !== selected.versionId) return
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = upload.name
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error: unknown) {
    if (actor.isCurrent()) {
      failure(error)
      materials.uploadMessage =
        error instanceof ApiError && error.status === 404
          ? '所选版本没有可访问的原文件'
          : '暂时无法读取原文件，请核实后重试'
    }
  }
}
</script>

<template>
  <section class="material-detail" aria-label="文件简历上传">
    <h2>上传 PDF / DOCX</h2>
    <p>
      最多 10,000,000 字节；PDF 最多 20 页。解析最多 30 秒，不识别扫描图片；必须核对正文后确认。
    </p>
    <p>当前目标：{{ materials.selected ? '为选中简历追加版本' : '新建简历' }}</p>
    <p v-if="materials.materialsBusy" role="status">正在处理资料请求，请等待当前操作结束。</p>
    <p
      v-if="
        materials.uploadListLoaded &&
        !materials.pendingUpload &&
        materials.uploadDrafts.length === 0
      "
    >
      没有待恢复的上传。可选择新文件或粘贴正文。
    </p>
    <button
      v-if="!materials.pendingUpload"
      type="button"
      :disabled="materials.materialsBusy"
      @click="loadDrafts"
    >
      查找未完成上传
    </button>
    <ul v-if="!materials.pendingUpload">
      <li v-for="draft in materials.uploadDrafts" :key="draft.uploadId">
        {{ draft.name }} · {{ stateNames[draft.state] ?? '等待核实' }}
        <button
          type="button"
          :disabled="
            materials.materialsBusy ||
            !!materials.pendingOperation ||
            !!materials.pendingVersionSave ||
            !!materials.pendingDelete
          "
          @click="restore(draft)"
        >
          恢复核对或放弃
        </button>
      </li>
    </ul>
    <button
      v-if="materials.selected && !materials.pendingUpload"
      type="button"
      @click="downloadVersion"
    >
      读取所选版本原文件
    </button>
    <input
      type="file"
      accept=".pdf,.docx"
      aria-label="选择简历文件"
      :disabled="
        !!materials.pendingUpload ||
        materials.materialsBusy ||
        !!materials.pendingOperation ||
        !!materials.pendingVersionSave ||
        !!materials.pendingDelete
      "
      @change="choose"
    />
    <template v-if="materials.pendingUpload">
      <p>
        文件：{{ materials.pendingUpload.file.name }}；<span
          :data-upload-state="materials.pendingUpload.status?.state ?? 'UNKNOWN'"
          >状态：{{
            stateNames[materials.pendingUpload.status?.state ?? ''] ?? '上传结果待核实'
          }}</span
        >
      </p>
      <p
        v-if="
          ['RECEIVING', 'QUEUED', 'PARSING'].includes(materials.pendingUpload.status?.state ?? '')
        "
      >
        文件已登记，尚未保存简历版本。单次解析最多30秒；请核实状态后核对正文。
      </p>
      <p v-if="!materials.pendingUpload.status" role="status">
        上传结果未知，请先核实原操作；网络失败不能证明文件未被接受。
      </p>
      <button type="button" :disabled="materials.materialsBusy" @click="verify">
        核实上传与解析状态
      </button>
      <button
        v-if="!materials.pendingUpload.status"
        type="button"
        :disabled="materials.materialsBusy"
        @click="send"
      >
        沿用原上传操作重试
      </button>
      <template v-if="['READY', 'CONFIRMED'].includes(materials.pendingUpload.status?.state ?? '')">
        <label
          >核对并编辑解析正文
          <textarea
            v-model="materials.pendingUpload.body"
            rows="10"
            :disabled="materials.materialsBusy"
            :readonly="
              !!materials.pendingUpload.confirmOperation ||
              materials.pendingUpload.status?.state === 'CONFIRMED'
            "
          />
        </label>
        <p>
          待确认正文 · {{ materials.codepoints(materials.pendingUpload.body) }} / 20,000 码点<span
            v-if="materials.codepoints(materials.pendingUpload.body) > 20000"
            >（超出
            {{
              materials.codepoints(materials.pendingUpload.body) - 20000
            }}，请修改，正文不会截断）</span
          >
        </p>
        <button
          v-if="
            materials.pendingUpload.status?.state === 'READY' ||
            materials.pendingUpload.confirmOperation
          "
          type="button"
          :disabled="materials.materialsBusy"
          @click="confirm"
        >
          {{
            materials.pendingUpload.confirmOperation ? '核实并沿用原确认操作' : '确认正文并保存版本'
          }}
        </button>
        <template v-if="materials.pendingUpload.status?.state === 'CONFIRMED'">
          <p>此上传已经确认。未保存的改动可先复制；打开确认版会结束本次核对。</p>
          <button type="button" :disabled="materials.materialsBusy" @click="openConfirmed">
            查看已确认版本并结束核对
          </button>
        </template>
        <button type="button" :disabled="materials.materialsBusy" @click="download">
          读取原文件
        </button>
      </template>
      <p v-if="materials.pendingUpload.status?.state === 'FAILED'">
        {{
          failureNames[materials.pendingUpload.status.errorCode ?? ''] ?? '解析失败'
        }}，请放弃上传后粘贴正文继续。
      </p>
      <button
        v-if="
          materials.pendingUpload.status &&
          materials.pendingUpload.status.state !== 'CONFIRMED' &&
          !materials.pendingUpload.confirmOperation
        "
        type="button"
        :disabled="materials.materialsBusy"
        @click="discard"
      >
        放弃上传，改用粘贴正文
      </button>
    </template>
    <p v-if="materials.uploadMessage" role="status">{{ materials.uploadMessage }}</p>
  </section>
</template>
