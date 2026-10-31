import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { Identity, SessionStatus } from './session'
import {
  getDeletion,
  type DeletionStatus,
  type ResumeDetail,
  type ResumeSummary,
  type VersionKind,
  type UploadStatus,
} from '../api/materials'

interface PendingVersionSave {
  resumeId: string
  kind: VersionKind
  body: string
  operationId: string
}

export const useMaterialsStore = defineStore('materials', () => {
  const draft = ref('')
  const pendingUpload = ref<{
    operationId: string
    file: File
    root: string | null
    status: UploadStatus | null
    body: string
    confirmOperation: string | null
    confirmedBody: string | null
  } | null>(null)
  const uploadMessage = ref('')
  const uploadDrafts = ref<UploadStatus[]>([])
  const uploadListLoaded = ref(false)
  const items = ref<ResumeSummary[]>([])
  const listState = ref<'loading' | 'ready' | 'failed'>('loading')
  const versionsState = ref<'loading' | 'ready' | 'failed'>('loading')
  const detailRequestId = ref(0)
  const detailLoading = ref(false)
  const routeNavigating = ref(false)
  const deletionReceipts = ref<Record<string, DeletionStatus>>({})
  const deletionMessages = ref<Record<string, string>>({})
  const cleanupTargets = ref<string[]>([])
  const selected = ref<ResumeDetail | null>(null)
  const materialsBusy = ref(false)
  const materialsMessage = ref('')
  const pendingOperation = ref<string | null>(null)
  const pendingDelete = ref<string | null>(null)
  const confirmDeleteId = ref<string | null>(null)
  const deleteVersionCount = ref(0)
  const deleteJdVersionCount = ref(0)
  const deleteFileCount = ref(0)
  const versionResumeId = ref<string | null>(null)
  const resumeVersions = ref<ResumeSummary[]>([])
  const jdVersions = ref<ResumeSummary[]>([])
  const selectedJd = ref<ResumeDetail | null>(null)
  const resumeVersionDraft = ref('')
  const jdDraft = ref('')
  const pendingVersionSave = ref<PendingVersionSave | null>(null)
  const versionMessage = ref('')
  const versionRequestId = ref(0)
  const generation = ref(0)
  const listRequestId = ref(0)
  const ownerAccountId = ref<string | null>(null)
  const hasUnsaved = computed(
    () =>
      !!(
        draft.value ||
        resumeVersionDraft.value ||
        jdDraft.value ||
        pendingOperation.value ||
        pendingVersionSave.value ||
        pendingUpload.value ||
        pendingDelete.value
      ),
  )

  function allowLeave(): boolean {
    return (
      !hasUnsaved.value ||
      window.confirm('当前输入尚未提交或结果尚未核实，离开可能丢失。确定离开？')
    )
  }

  function allowRootChange(resumeId: string | null): boolean {
    if (versionResumeId.value === resumeId) return true
    if (pendingVersionSave.value || pendingUpload.value) return false
    return (
      !(resumeVersionDraft.value || jdDraft.value) ||
      window.confirm('当前资料有未提交草稿，切换会丢弃这些修改。确定离开？')
    )
  }

  function codepoints(raw: string): number {
    // 对齐 Java String.strip 的空白集合，避免 JS trim 对不换行空格的口径差异。
    const characters = Array.from(raw.replace(/\r\n?/g, '\n'))
    const whitespace = (value: string): boolean => {
      const c = value.codePointAt(0) ?? 0
      return (
        (c >= 9 && c <= 13) ||
        (c >= 28 && c <= 32) ||
        c === 0x1680 ||
        (c >= 0x2000 && c <= 0x2006) ||
        (c >= 0x2008 && c <= 0x200a) ||
        c === 0x2028 ||
        c === 0x2029 ||
        c === 0x205f ||
        c === 0x3000
      )
    }
    let start = 0
    let end = characters.length
    while (start < end && whitespace(characters[start]!)) start += 1
    while (end > start && whitespace(characters[end - 1]!)) end -= 1
    return end - start
  }

  function recordDeletion(resumeId: string, receipt: DeletionStatus): void {
    rememberDeletionTarget(resumeId)
    deletionReceipts.value[resumeId] = receipt
    deletionMessages.value[resumeId] = ''
    if (selected.value?.resumeId === resumeId || versionResumeId.value === resumeId) {
      invalidateDetailRead()
      versionRequestId.value += 1
    }
    items.value = items.value.filter((item) => item.resumeId !== resumeId)
    if (selected.value?.resumeId === resumeId) selected.value = null
    if (versionResumeId.value === resumeId) selectVersionRoot(null)
    if (
      pendingUpload.value?.root === resumeId ||
      pendingUpload.value?.status?.resumeId === resumeId
    )
      pendingUpload.value = null
    uploadDrafts.value = uploadDrafts.value.filter((item) => item.resumeId !== resumeId)
    if (confirmDeleteId.value === resumeId) confirmDeleteId.value = null
    if (pendingDelete.value === resumeId) pendingDelete.value = null
  }

  async function reconcileDeletedRoot(
    resumeId: string,
    observedGeneration: number,
  ): Promise<boolean> {
    // 404 既可能是删除也可能是不可访问；只有本人的服务端删除回执能终止原未决操作。
    try {
      const receipt = await getDeletion(resumeId)
      if (generation.value !== observedGeneration) return false
      recordDeletion(resumeId, receipt)
      return true
    } catch {
      return false
    }
  }

  function rememberDeletionTarget(resumeId: string): void {
    if (!cleanupTargets.value.includes(resumeId)) cleanupTargets.value.push(resumeId)
    // 刷新只恢复最小删除目标；不持久化正文、草稿、文件或安全凭据，回执仍须由服务端核实。
    if (ownerAccountId.value) {
      try {
        sessionStorage.setItem(
          `material-cleanup:${ownerAccountId.value}`,
          JSON.stringify(cleanupTargets.value),
        )
      } catch {
        /* 浏览器禁用存储时仍保留本页核实入口，不伪称刷新可恢复。 */
      }
    }
  }

  async function restoreDeletionTargets(
    accountId: string,
    observedGeneration: number,
  ): Promise<void> {
    let targets: unknown
    try {
      targets = JSON.parse(sessionStorage.getItem(`material-cleanup:${accountId}`) ?? '[]')
    } catch {
      return
    }
    if (!Array.isArray(targets)) return
    for (const id of targets) {
      if (typeof id !== 'string' || !/^[0-9a-f-]{36}$/i.test(id)) continue
      if (generation.value !== observedGeneration) return
      cleanupTargets.value.push(id)
      deletionMessages.value[id] = '正在核实上次删除操作…'
      try {
        const receipt = await getDeletion(id)
        if (generation.value !== observedGeneration) return
        deletionReceipts.value[id] = receipt
        deletionMessages.value[id] = ''
      } catch {
        if (generation.value !== observedGeneration) return
        deletionMessages.value[id] =
          '暂时无法核实上次删除是否受理，请查询状态；未查到不等于未受理。'
      }
    }
  }

  function invalidateListRequests(): void {
    listRequestId.value += 1
  }

  function invalidateDetailRead(): void {
    detailRequestId.value += 1
    // 导航只解除旧详情读取的占用，不能解除保存/删除等写操作的保护。
    if (detailLoading.value) {
      detailLoading.value = false
      materialsBusy.value = false
    }
  }

  function beginListRequest(): number {
    invalidateListRequests()
    return listRequestId.value
  }

  function isLatestListRequest(requestId: number): boolean {
    return requestId === listRequestId.value
  }

  function clearPrivate(): void {
    if (ownerAccountId.value) {
      try {
        sessionStorage.removeItem(`material-cleanup:${ownerAccountId.value}`)
      } catch {
        /* 存储不可用不影响清除内存私有状态。 */
      }
    }
    generation.value += 1
    invalidateListRequests()
    draft.value = ''
    pendingUpload.value = null
    uploadMessage.value = ''
    uploadDrafts.value = []
    uploadListLoaded.value = false
    items.value = []
    listState.value = 'loading'
    detailRequestId.value += 1
    detailLoading.value = false
    deletionReceipts.value = {}
    deletionMessages.value = {}
    cleanupTargets.value = []
    selected.value = null
    materialsBusy.value = false
    materialsMessage.value = ''
    pendingOperation.value = null
    pendingDelete.value = null
    confirmDeleteId.value = null
    deleteVersionCount.value = 0
    deleteJdVersionCount.value = 0
    deleteFileCount.value = 0
    selectVersionRoot(null)
  }

  function selectVersionRoot(resumeId: string | null): void {
    versionRequestId.value += 1
    if (versionResumeId.value === resumeId && resumeId !== null) return
    versionResumeId.value = resumeId
    versionsState.value = 'loading'
    resumeVersions.value = []
    jdVersions.value = []
    selectedJd.value = null
    resumeVersionDraft.value = ''
    jdDraft.value = ''
    pendingVersionSave.value = null
    versionMessage.value = ''
  }

  // A temporary identity check hides the view, but does not discard an unresolved operation.
  function syncIdentity(status: SessionStatus, identity: Identity | null): boolean {
    if (status === 'anonymous') {
      if (ownerAccountId.value !== null) clearPrivate()
      ownerAccountId.value = null
      return false
    }
    if (status !== 'authenticated' || identity === null) return false
    if (ownerAccountId.value === identity.accountId) return false
    clearPrivate()
    ownerAccountId.value = identity.accountId
    void restoreDeletionTargets(identity.accountId, generation.value)
    return true
  }

  return {
    listState,
    versionsState,
    detailRequestId,
    detailLoading,
    routeNavigating,
    invalidateDetailRead,
    deletionReceipts,
    deletionMessages,
    hasUnsaved,
    allowLeave,
    allowRootChange,
    codepoints,
    recordDeletion,
    rememberDeletionTarget,
    reconcileDeletedRoot,
    pendingUpload,
    uploadMessage,
    uploadDrafts,
    uploadListLoaded,
    draft,
    items,
    selected,
    materialsBusy,
    materialsMessage,
    pendingOperation,
    pendingDelete,
    confirmDeleteId,
    deleteVersionCount,
    deleteJdVersionCount,
    deleteFileCount,
    versionResumeId,
    resumeVersions,
    jdVersions,
    selectedJd,
    resumeVersionDraft,
    jdDraft,
    pendingVersionSave,
    versionMessage,
    versionRequestId,
    selectVersionRoot,
    generation,
    invalidateListRequests,
    beginListRequest,
    isLatestListRequest,
    syncIdentity,
  }
})
