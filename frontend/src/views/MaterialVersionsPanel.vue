<script setup lang="ts">
import { watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useMaterialsStore } from '../stores/materials'
import { useSessionStore } from '../stores/session'
import { ApiError } from '../api/http'
import {
  confirmVersion,
  getVersion,
  getVersionOperation,
  listVersions,
  type ResumeDetail,
  type VersionKind,
} from '../api/materials'

const props = defineProps<{ resumeId: string }>()
const emit = defineEmits<{ saved: [] }>()
const materials = useMaterialsStore()
const session = useSessionStore()
const {
  materialsBusy,
  resumeVersions,
  jdVersions,
  selectedJd,
  resumeVersionDraft,
  jdDraft,
  pendingVersionSave,
  pendingOperation,
  versionMessage,
} = storeToRefs(materials)

function current(generation: number, resumeId: string): boolean {
  return (
    generation === materials.generation &&
    props.resumeId === resumeId &&
    materials.versionResumeId === resumeId
  )
}

function showError(error: unknown): void {
  versionMessage.value = error instanceof ApiError ? error.message : '版本操作暂时无法完成，请重试'
  if (
    error instanceof ApiError &&
    (error.status === 401 ||
      ['CSRF_INVALID', 'ACCOUNT_CHANGED', 'IDENTITY_UNVERIFIED'].includes(error.code))
  ) {
    void session.refreshIdentity()
  }
}

async function refreshVersions(): Promise<void> {
  const generation = materials.generation
  const resumeId = props.resumeId
  const requestId = ++materials.versionRequestId
  try {
    const [resumes, jds] = await Promise.all([
      listVersions(resumeId, 'resume'),
      listVersions(resumeId, 'jd'),
    ])
    if (!current(generation, resumeId) || requestId !== materials.versionRequestId) return
    resumeVersions.value = resumes
    jdVersions.value = jds
  } catch (error: unknown) {
    if (current(generation, resumeId) && requestId === materials.versionRequestId) showError(error)
  }
}

watch(
  () => props.resumeId,
  (resumeId) => {
    materials.selectVersionRoot(resumeId)
    void refreshVersions()
  },
  { immediate: true },
)

async function selectVersion(kind: VersionKind, versionId: string): Promise<void> {
  if (materialsBusy.value || pendingVersionSave.value) return
  const generation = materials.generation
  const resumeId = props.resumeId
  materialsBusy.value = true
  try {
    const detail = await getVersion(resumeId, kind, versionId)
    if (!current(generation, resumeId)) return
    if (kind === 'resume') materials.selected = detail
    else selectedJd.value = detail
  } catch (error: unknown) {
    if (current(generation, resumeId)) showError(error)
  } finally {
    if (current(generation, resumeId)) materialsBusy.value = false
  }
}

async function acceptResult(detail: ResumeDetail, kind: VersionKind): Promise<void> {
  pendingVersionSave.value = null
  if (kind === 'resume') {
    materials.selected = detail
    resumeVersionDraft.value = ''
  } else {
    selectedJd.value = detail
    jdDraft.value = ''
  }
  versionMessage.value = `${kind === 'jd' ? 'JD' : '简历'}第 ${detail.versionNo} 版已确认保存。`
  emit('saved')
  await refreshVersions()
}

async function saveVersion(kind: VersionKind): Promise<void> {
  // 新建结果未决时，其恢复可能切换资料根；先核实它，避免产生第二个未决版本操作。
  if (
    materialsBusy.value ||
    materials.pendingUpload !== null ||
    pendingOperation.value !== null ||
    session.status !== 'authenticated' ||
    !session.identity
  )
    return
  const retrying = pendingVersionSave.value !== null
  if (!pendingVersionSave.value) {
    pendingVersionSave.value = {
      resumeId: props.resumeId,
      kind,
      body: kind === 'jd' ? jdDraft.value : resumeVersionDraft.value,
      operationId: crypto.randomUUID(),
    }
  }
  const operation = pendingVersionSave.value
  const generation = materials.generation
  const accountId = session.identity.accountId
  materialsBusy.value = true
  materials.invalidateListRequests()
  materials.versionRequestId += 1
  versionMessage.value = ''
  try {
    const detail = await confirmVersion(
      operation.resumeId,
      operation.kind,
      operation.body,
      operation.operationId,
      {
        accountId,
        isCurrent: () =>
          current(generation, operation.resumeId) &&
          session.status === 'authenticated' &&
          session.identity?.accountId === accountId,
      },
    )
    if (current(generation, operation.resumeId)) await acceptResult(detail, operation.kind)
  } catch (error: unknown) {
    if (!current(generation, operation.resumeId)) return
    // 本次预检失败不能证明此前同一操作未提交；未决正文与标识一起保留。
    if (!retrying && (!(error instanceof ApiError) || error.code !== 'RESULT_UNKNOWN'))
      pendingVersionSave.value = null
    showError(error)
  } finally {
    if (current(generation, operation.resumeId)) materialsBusy.value = false
  }
}

async function verifyVersion(): Promise<void> {
  const operation = pendingVersionSave.value
  if (!operation || materialsBusy.value) return
  const generation = materials.generation
  materialsBusy.value = true
  materials.invalidateListRequests()
  materials.versionRequestId += 1
  try {
    const detail = await getVersionOperation(
      operation.resumeId,
      operation.kind,
      operation.operationId,
    )
    if (current(generation, operation.resumeId)) await acceptResult(detail, operation.kind)
  } catch (error: unknown) {
    if (current(generation, operation.resumeId)) showError(error)
  } finally {
    if (current(generation, operation.resumeId)) materialsBusy.value = false
  }
}
</script>

<template>
  <section class="material-detail" aria-label="资料历史版本">
    <h2>资料版本</h2>
    <p>确认修改会追加新版本，历史正文保持不变。保存资料不代表已通过岗位语义分析。</p>
    <p v-if="pendingOperation" role="status">
      新建简历的保存结果尚未核实，请先核实原保存结果或沿用原操作重试，再保存资料版本。
    </p>
    <h3>简历版本</h3>
    <button
      v-for="version in resumeVersions"
      :key="version.versionId"
      type="button"
      class="button-secondary"
      :disabled="materialsBusy || !!pendingVersionSave"
      :data-resume-version="version.versionNo"
      @click="selectVersion('resume', version.versionId)"
    >
      查看简历第 {{ version.versionNo }} 版
    </button>
    <button
      type="button"
      class="button-secondary"
      :disabled="materialsBusy || !!pendingVersionSave"
      @click="resumeVersionDraft = materials.selected?.body ?? ''"
    >
      基于当前简历版本编辑
    </button>
    <label for="resume-version-body">简历新版本草稿（100～20,000 个 Unicode 码点）</label>
    <textarea
      id="resume-version-body"
      v-model="resumeVersionDraft"
      rows="5"
      :disabled="materialsBusy || !!pendingVersionSave"
    />
    <button
      type="button"
      class="button-primary"
      :disabled="materialsBusy || !!pendingVersionSave || pendingOperation !== null"
      @click="saveVersion('resume')"
    >
      保存简历新版本
    </button>
    <h3>JD 版本</h3>
    <p v-if="jdVersions.length === 0">暂无已确认 JD。</p>
    <button
      v-for="version in jdVersions"
      :key="version.versionId"
      type="button"
      class="button-secondary"
      :disabled="materialsBusy || !!pendingVersionSave"
      :data-jd-version="version.versionNo"
      @click="selectVersion('jd', version.versionId)"
    >
      查看JD第 {{ version.versionNo }} 版
    </button>
    <section v-if="selectedJd" aria-label="JD详情">
      <h3>已确认 JD · 第 {{ selectedJd.versionNo }} 版</h3>
      <p>JD版本ID：{{ selectedJd.versionId }} · {{ selectedJd.codepointCount }} 字符</p>
      <pre>{{ selectedJd.body }}</pre>
      <button
        type="button"
        class="button-secondary"
        :disabled="materialsBusy || !!pendingVersionSave"
        @click="jdDraft = selectedJd.body"
      >
        基于此JD版本编辑
      </button>
    </section>
    <label for="jd-body">JD 草稿（50～10,000 个 Unicode 码点）</label>
    <textarea
      id="jd-body"
      v-model="jdDraft"
      rows="5"
      :disabled="materialsBusy || !!pendingVersionSave"
    />
    <button
      type="button"
      class="button-primary"
      :disabled="materialsBusy || !!pendingVersionSave || pendingOperation !== null"
      @click="saveVersion('jd')"
    >
      确认并保存JD版本
    </button>
    <template v-if="pendingVersionSave">
      <p>原版本保存尚未核实，正文和操作标识已保留。请核实或沿用原操作重试。</p>
      <button
        type="button"
        class="button-secondary"
        :disabled="materialsBusy"
        @click="verifyVersion"
      >
        核实版本保存结果
      </button>
      <button
        type="button"
        class="button-secondary"
        :disabled="materialsBusy"
        @click="saveVersion(pendingVersionSave.kind)"
      >
        沿用原操作重试版本
      </button>
    </template>
    <button
      type="button"
      class="button-secondary"
      :disabled="materialsBusy"
      @click="refreshVersions"
    >
      刷新版本列表
    </button>
    <p v-if="versionMessage" role="status">{{ versionMessage }}</p>
  </section>
</template>
