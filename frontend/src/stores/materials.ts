import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Identity, SessionStatus } from './session'
import type { ResumeDetail, ResumeSummary, VersionKind } from '../api/materials'

interface PendingVersionSave {
  resumeId: string
  kind: VersionKind
  body: string
  operationId: string
}

export const useMaterialsStore = defineStore('materials', () => {
  const draft = ref('')
  const items = ref<ResumeSummary[]>([])
  const selected = ref<ResumeDetail | null>(null)
  const materialsBusy = ref(false)
  const materialsMessage = ref('')
  const pendingOperation = ref<string | null>(null)
  const pendingDelete = ref<string | null>(null)
  const confirmDeleteId = ref<string | null>(null)
  const deleteVersionCount = ref(0)
  const deleteJdVersionCount = ref(0)
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

  function invalidateListRequests(): void {
    listRequestId.value += 1
  }

  function beginListRequest(): number {
    invalidateListRequests()
    return listRequestId.value
  }

  function isLatestListRequest(requestId: number): boolean {
    return requestId === listRequestId.value
  }

  function clearPrivate(): void {
    generation.value += 1
    invalidateListRequests()
    draft.value = ''
    items.value = []
    selected.value = null
    materialsBusy.value = false
    materialsMessage.value = ''
    pendingOperation.value = null
    pendingDelete.value = null
    confirmDeleteId.value = null
    deleteVersionCount.value = 0
    deleteJdVersionCount.value = 0
    selectVersionRoot(null)
  }

  function selectVersionRoot(resumeId: string | null): void {
    versionRequestId.value += 1
    if (versionResumeId.value === resumeId && resumeId !== null) return
    versionResumeId.value = resumeId
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
    return true
  }

  return {
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
