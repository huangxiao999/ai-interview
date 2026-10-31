<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { useMaterialsStore } from '../stores/materials'
import { ApiError } from '../api/http'
import MaterialVersionsPanel from './MaterialVersionsPanel.vue'
import MaterialUploadPanel from './MaterialUploadPanel.vue'
import {
  createResume,
  deleteResume,
  deletionImpact,
  getCreatedOperation,
  getDeletion,
  getResume,
  listResumes,
} from '../api/materials'

const router = useRouter()
const route = useRoute()
const deleteDialog = ref<HTMLDialogElement | null>(null)
let deleteTrigger: HTMLElement | null = null
const session = useSessionStore()
const { status, identity, message, busy } = storeToRefs(session)
const materials = useMaterialsStore()
const {
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
  pendingVersionSave,
  listState,
} = storeToRefs(materials)

watch(
  [confirmDeleteId, deleteDialog],
  ([id]) => {
    if (!id) {
      deleteDialog.value?.close()
      void nextTick(() => {
        if (deleteTrigger?.isConnected) deleteTrigger.focus()
        else document.querySelector<HTMLElement>('#workspace-heading')?.focus()
      })
    } else {
      void nextTick(() => {
        if (deleteDialog.value && !deleteDialog.value.open) deleteDialog.value.showModal()
      })
    }
  },
  { flush: 'sync' },
)

function warnBeforeUnload(event: BeforeUnloadEvent): void {
  if (status.value !== 'authenticated' || !materials.hasUnsaved) return
  event.preventDefault()
  event.returnValue = ''
}
onMounted(() => window.addEventListener('beforeunload', warnBeforeUnload))
onUnmounted(() => window.removeEventListener('beforeunload', warnBeforeUnload))
onBeforeRouteLeave(() => status.value !== 'authenticated' || materials.allowLeave())
onBeforeRouteUpdate(() => status.value !== 'authenticated' || materials.allowLeave())

watch(
  () => [status.value, identity.value?.accountId] as const,
  () => {
    const ownerChanged = materials.syncIdentity(status.value, identity.value)
    if (ownerChanged && identity.value?.authorities.includes('TRIAL_USER')) {
      void loadItems(materials.generation)
    }
  },
  { immediate: true },
)

watch(
  () => [route.params.resumeId, status.value] as const,
  ([resumeId, state]) => {
    if (
      typeof resumeId === 'string' &&
      state === 'authenticated' &&
      identity.value?.authorities.includes('TRIAL_USER')
    )
      void openResume(resumeId)
  },
  { immediate: true },
)

function showError(error: unknown): void {
  if (
    error instanceof ApiError &&
    (error.status === 401 ||
      error.code === 'CSRF_INVALID' ||
      error.code === 'ACCOUNT_CHANGED' ||
      error.code === 'IDENTITY_UNVERIFIED')
  ) {
    void session.refreshIdentity()
  }
  materialsMessage.value =
    error instanceof ApiError ? error.message : '资料操作暂时无法完成，请重试'
}

async function loadItems(generation = materials.generation): Promise<void> {
  const requestId = materials.beginListRequest()
  if (listState.value === 'failed') materialsMessage.value = ''
  listState.value = 'loading'
  try {
    const loaded = await listResumes()
    if (generation === materials.generation && materials.isLatestListRequest(requestId)) {
      items.value = loaded.filter((item) => !materials.deletionReceipts[item.resumeId])
      listState.value = 'ready'
    }
  } catch (error: unknown) {
    if (generation === materials.generation && materials.isLatestListRequest(requestId)) {
      listState.value = 'failed'
      showError(error)
    }
  }
}

async function saveDraft(): Promise<void> {
  if (materialsBusy.value || materials.pendingUpload || status.value !== 'authenticated') return
  const retryingUnresolvedOperation = pendingOperation.value !== null
  if (!pendingOperation.value) pendingOperation.value = crypto.randomUUID()
  const operationId = pendingOperation.value
  const generation = materials.generation
  const accountId = identity.value?.accountId
  if (!accountId) return
  materialsBusy.value = true
  materials.invalidateListRequests()
  materialsMessage.value = ''
  try {
    const detail = await createResume(draft.value, operationId, {
      accountId,
      isCurrent: () =>
        generation === materials.generation &&
        status.value === 'authenticated' &&
        identity.value?.accountId === accountId,
    })
    if (generation !== materials.generation) return
    pendingOperation.value = null
    draft.value = ''
    selected.value = detail
    materialsMessage.value = '简历正文已保存为第 1 版。可继续确认 JD 或追加资料版本。'
    await loadItems(generation)
  } catch (error: unknown) {
    if (generation === materials.generation) {
      // A preflight rejection says nothing about an earlier submission of this operation.
      if (
        !retryingUnresolvedOperation &&
        (!(error instanceof ApiError) || error.code !== 'RESULT_UNKNOWN')
      )
        pendingOperation.value = null
      showError(error)
    }
  } finally {
    if (generation === materials.generation) materialsBusy.value = false
  }
}

async function verifySave(): Promise<void> {
  if (!pendingOperation.value || materialsBusy.value) return
  const generation = materials.generation
  materialsBusy.value = true
  materials.invalidateListRequests()
  try {
    const detail = await getCreatedOperation(pendingOperation.value)
    if (generation !== materials.generation) return
    selected.value = detail
    draft.value = ''
    pendingOperation.value = null
    materialsMessage.value = '已核实原保存操作成功。'
    await loadItems(generation)
  } catch (error: unknown) {
    if (generation === materials.generation) {
      materialsMessage.value =
        error instanceof ApiError && error.status === 404
          ? '尚未查到原操作结果；可用同一操作标识重试保存。'
          : '暂时无法核实原操作，请稍后重试。'
    }
  } finally {
    if (generation === materials.generation) materialsBusy.value = false
  }
}

async function openResume(resumeId: string): Promise<void> {
  if (materialsBusy.value || !materials.allowRootChange(resumeId)) return
  const generation = materials.generation
  const requestId = ++materials.detailRequestId
  selected.value = null
  materials.selectVersionRoot(resumeId)
  materialsBusy.value = true
  materialsMessage.value = ''
  try {
    const detail = await getResume(resumeId)
    if (generation === materials.generation && requestId === materials.detailRequestId)
      selected.value = detail
  } catch (error: unknown) {
    if (generation === materials.generation && requestId === materials.detailRequestId) {
      const deleted =
        error instanceof ApiError &&
        error.status === 404 &&
        (await materials.reconcileDeletedRoot(resumeId, generation))
      if (generation === materials.generation)
        materialsMessage.value = deleted
          ? '此简历已删除，资料已禁止访问。'
          : '不存在或无权访问此记录；可返回本人简历列表。'
      if (!deleted && (!(error instanceof ApiError) || error.status !== 404)) showError(error)
    }
  } finally {
    if (generation === materials.generation) materialsBusy.value = false
  }
}

async function prepareDelete(resumeId: string): Promise<void> {
  if (materialsBusy.value) return
  const generation = materials.generation
  materialsBusy.value = true
  deleteTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  confirmDeleteId.value = null
  try {
    const impact = await deletionImpact(resumeId)
    if (generation !== materials.generation) return
    confirmDeleteId.value = resumeId
    deleteVersionCount.value = impact.versionCount
    deleteJdVersionCount.value = impact.jdVersionCount
    deleteFileCount.value = impact.fileCount
  } catch (error: unknown) {
    if (generation === materials.generation) showError(error)
  } finally {
    if (generation === materials.generation) materialsBusy.value = false
  }
}

async function confirmDelete(): Promise<void> {
  const resumeId = confirmDeleteId.value
  if (!resumeId || materialsBusy.value) return
  const generation = materials.generation
  const accountId = identity.value?.accountId
  if (!accountId) return
  materialsBusy.value = true
  materials.invalidateListRequests()
  pendingDelete.value = resumeId
  materials.rememberDeletionTarget(resumeId)
  try {
    const result = await deleteResume(resumeId, {
      accountId,
      isCurrent: () =>
        generation === materials.generation &&
        status.value === 'authenticated' &&
        identity.value?.accountId === accountId,
    })
    if (generation !== materials.generation) return
    materials.recordDeletion(resumeId, result)
    materialsMessage.value =
      result.state === 'CLEANED' ? '简历及当前正文已删除。' : '删除已受理，资料已禁访。'
    await loadItems(generation)
  } catch (error: unknown) {
    if (generation === materials.generation) showError(error)
  } finally {
    if (generation === materials.generation) materialsBusy.value = false
  }
}

async function verifyDelete(): Promise<void> {
  const resumeId = pendingDelete.value
  if (!resumeId || materialsBusy.value) return
  const generation = materials.generation
  materialsBusy.value = true
  materials.invalidateListRequests()
  try {
    const result = await getDeletion(resumeId)
    if (generation !== materials.generation) return
    materials.recordDeletion(resumeId, result)
    materialsMessage.value = `已核实删除状态：${result.state === 'CLEANED' ? '当前正文已清理' : '资料已禁访'}`
    await loadItems(generation)
  } catch (error: unknown) {
    if (generation === materials.generation) {
      materialsMessage.value =
        error instanceof ApiError && error.status === 404
          ? '未查到删除受理结果，可重新提交删除确认。'
          : '暂时无法核实删除状态，请稍后重试。'
    }
  } finally {
    if (generation === materials.generation) materialsBusy.value = false
  }
}

async function signOut(): Promise<void> {
  if (!materials.allowLeave()) return
  if (await session.logout()) await router.replace('/login')
}

function startNew(): void {
  if (materialsBusy.value || !materials.allowRootChange(null)) return
  materials.detailRequestId += 1
  selected.value = null
  materials.selectVersionRoot(null)
  document.querySelector<HTMLTextAreaElement>('#resume-body')?.focus()
}

function cancelDeleteDialog(): void {
  if (!materialsBusy.value) confirmDeleteId.value = null
}

async function refreshCleanup(resumeId: string): Promise<void> {
  if (materialsBusy.value) return
  const generation = materials.generation
  materialsBusy.value = true
  try {
    const receipt = await getDeletion(resumeId)
    if (generation === materials.generation) materials.recordDeletion(resumeId, receipt)
  } catch (error: unknown) {
    if (generation === materials.generation) {
      materials.deletionMessages[resumeId] = '暂时无法核实最新清理状态；保留上次回执，稍后刷新。'
      showError(error)
    }
  } finally {
    if (generation === materials.generation) materialsBusy.value = false
  }
}

function displayTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value))
}

async function retryIdentity(): Promise<void> {
  if ((await session.refreshIdentity()) === 'anonymous') await router.replace('/login')
}
</script>

<template>
  <main class="workspace-layout">
    <header class="workspace-header">
      <div class="brand-header">
        <span class="brand-mark" aria-hidden="true">AI</span>
        <span>智能面试教练</span>
      </div>
      <div v-if="status === 'authenticated' && identity" class="account-actions">
        <span class="account-name">{{ identity.loginName }}</span>
        <button type="button" class="button-secondary" :disabled="busy" @click="signOut">
          {{ busy ? '正在退出…' : '退出登录' }}
        </button>
      </div>
    </header>

    <section v-if="status === 'checking'" class="workspace-panel" role="status">
      正在核实登录状态…
    </section>
    <section v-else-if="status === 'uncertain'" class="workspace-panel" role="alert">
      <h1>暂时无法核实登录状态</h1>
      <p>{{ message || '请重试身份核实。' }}</p>
      <button type="button" class="button-primary" @click="retryIdentity">重新核实</button>
    </section>
    <section v-else-if="status === 'anonymous'" class="workspace-panel">
      <h1>登录已失效</h1>
      <p>请重新登录后继续。</p>
      <RouterLink to="/login" class="button-link">前往登录</RouterLink>
    </section>
    <section v-else class="workspace-panel" aria-labelledby="workspace-heading">
      <p class="eyebrow">我的练习空间</p>
      <h1 id="workspace-heading" tabindex="-1">我的简历</h1>
      <template v-if="identity?.authorities.includes('TRIAL_USER')">
        <p class="stage-note">
          准备资料 → 保存版本 → 回看历史。本阶段提供私有资料管理，面试计划尚未开放。
        </p>
        <nav class="material-nav" aria-label="资料导航">
          <a href="#resume-history-heading">我的简历与历史</a>
          <button
            type="button"
            class="button-primary"
            :disabled="materialsBusy || !!materials.pendingUpload || !!pendingVersionSave"
            @click="startNew"
          >
            新增简历
          </button>
        </nav>
        <MaterialUploadPanel @saved="loadItems()" />
        <fieldset :disabled="!!materials.pendingUpload" class="material-fields">
          <p>可粘贴或上传简历、确认 JD 并查看资料版本。面试训练将在后续任务中开放。</p>
          <div class="material-grid">
            <section aria-labelledby="resume-input-heading">
              <h2 id="resume-input-heading">新建简历</h2>
              <label for="resume-body">简历正文</label>
              <textarea
                id="resume-body"
                v-model="draft"
                rows="10"
                :disabled="materialsBusy || pendingOperation !== null || !!pendingVersionSave"
                placeholder="粘贴简历正文，确认后保存为第 1 版"
              />
              <p class="field-help">
                去除首尾空白、统一换行后须为 100～20,000 个 Unicode 字符；不会截断。
              </p>
              <p :class="{ 'form-error': materials.codepoints(draft) > 20000 }" data-count="resume">
                草稿 · {{ materials.codepoints(draft) }} / 20,000 码点<span
                  v-if="materials.codepoints(draft) > 20000"
                  >（超出 {{ materials.codepoints(draft) - 20000 }}）</span
                >
                · 尚未保存
              </p>
              <button
                type="button"
                class="button-primary"
                :disabled="materialsBusy || pendingOperation !== null || !!pendingVersionSave"
                @click="saveDraft"
              >
                {{ materialsBusy ? '正在保存…' : '确认并保存简历' }}
              </button>
              <button
                v-if="pendingOperation"
                type="button"
                class="button-secondary"
                :disabled="materialsBusy"
                @click="verifySave"
              >
                核实原保存结果
              </button>
              <button
                v-if="pendingOperation"
                type="button"
                class="button-secondary"
                :disabled="materialsBusy"
                @click="saveDraft"
              >
                沿用原操作重试
              </button>
            </section>
            <section aria-labelledby="resume-history-heading">
              <h2 id="resume-history-heading">已保存的简历</h2>
              <p v-if="listState === 'loading'" role="status">正在加载本人简历…</p>
              <p v-else-if="listState === 'failed'" role="alert">
                简历列表加载失败，请刷新列表；这不表示没有已保存资料。
              </p>
              <p v-if="listState === 'ready' && items.length === 0">
                暂无已保存简历。可以粘贴正文或上传 PDF/DOCX 创建第一份。
              </p>
              <ul v-if="items.length > 0" class="material-list">
                <li v-for="item in items" :key="item.resumeId">
                  <p class="resume-label" :title="item.resumeId">
                    简历 {{ item.resumeId.slice(0, 8) }}
                  </p>
                  <span>第 {{ item.versionNo }} 版 · {{ item.codepointCount }} 字符</span>
                  <small>保存于 {{ displayTime(item.confirmedAt) }}（UTC+8）</small>
                  <button
                    type="button"
                    class="button-secondary"
                    :disabled="materialsBusy || !!pendingVersionSave"
                    @click="openResume(item.resumeId)"
                  >
                    查看
                  </button>
                  <button
                    type="button"
                    class="button-secondary"
                    :disabled="materialsBusy || !!pendingVersionSave"
                    @click="prepareDelete(item.resumeId)"
                  >
                    删除整份
                  </button>
                </li>
              </ul>
              <button
                type="button"
                class="button-secondary"
                :disabled="materialsBusy"
                @click="loadItems()"
              >
                刷新列表
              </button>
            </section>
          </div>
          <section v-if="selected" class="material-detail" aria-label="简历详情">
            <h2>已确认简历 · 第 {{ selected.versionNo }} 版</h2>
            <p>{{ selected.codepointCount }} 字符</p>
            <p>简历版本ID：{{ selected.versionId }}</p>
            <p>历史版本只读 · 确认时间 {{ displayTime(selected.confirmedAt) }}（UTC+8）</p>
            <button
              type="button"
              class="button-secondary"
              data-delete-selected
              :disabled="materialsBusy || !!pendingVersionSave || !!materials.pendingUpload"
              @click="prepareDelete(selected.resumeId)"
            >
              删除当前整份简历
            </button>
            <pre>{{ selected.body }}</pre>
          </section>
          <MaterialVersionsPanel
            v-if="selected"
            :resume-id="selected.resumeId"
            @saved="loadItems()"
          />
          <dialog
            v-if="confirmDeleteId"
            ref="deleteDialog"
            class="material-delete"
            role="alertdialog"
            aria-label="确认删除简历"
            aria-modal="true"
            @cancel.prevent="cancelDeleteDialog"
          >
            <h2>确认删除整份简历？</h2>
            <p>删除目标：简历 {{ confirmDeleteId.slice(0, 8) }}。将影响该根下的全部资料版本。</p>
            <p>
              将删除该简历当前的 {{ deleteVersionCount }} 个正文版本和
              {{ deleteJdVersionCount }} 个JD版本及
              {{ deleteFileCount }}
              份原文件/暂存。删除受理后立即禁访且无法撤销，在线资料须在24小时内清理。
            </p>
            <p>
              范围以服务端核实结果为准；确认前新增的版本也会纳入整份删除。当前尚无计划、面试或报告对象。备份与恢复证明仍待后续任务，不能把在线清理当作备份已清除。
            </p>
            <button
              type="button"
              class="button-primary"
              :disabled="materialsBusy"
              @click="confirmDelete"
            >
              确认删除
            </button>
            <button
              type="button"
              class="button-secondary"
              :disabled="materialsBusy"
              autofocus
              @click="cancelDeleteDialog"
            >
              取消
            </button>
          </dialog>
          <button
            v-if="pendingDelete"
            type="button"
            class="button-secondary"
            :disabled="materialsBusy"
            @click="verifyDelete"
          >
            核实删除状态
          </button>
          <p v-if="materialsMessage" role="status">{{ materialsMessage }}</p>
        </fieldset>
        <section
          v-for="(receipt, resumeId) in materials.deletionReceipts"
          :key="resumeId"
          class="cleanup-receipt"
          aria-label="删除清理状态"
          :data-cleanup-state="receipt.state"
        >
          <h2>{{ receipt.state === 'CLEANED' ? '当前资料已清理' : '资料已禁访，清理尚未完成' }}</h2>
          <p>
            受理时间：{{ displayTime(receipt.requestedAt) }} · 在线清理期限：{{
              displayTime(receipt.deadlineAt)
            }}（UTC+8）
          </p>
          <p v-if="receipt.state !== 'CLEANED'">
            后台继续处理或重试未完成项；刷新状态可核实进度。清理故障不会恢复访问，也不能撤销删除。
          </p>
          <p v-else>已核实当前在线资料清理完成；备份清理需单独验证。</p>
          <button
            type="button"
            class="button-secondary"
            :disabled="materialsBusy"
            @click="refreshCleanup(String(resumeId))"
          >
            刷新清理状态
          </button>
          <p v-if="materials.deletionMessages[resumeId]" role="status">
            {{ materials.deletionMessages[resumeId] }}
          </p>
        </section>
        <template v-for="(notice, resumeId) in materials.deletionMessages" :key="resumeId">
          <section
            v-if="!materials.deletionReceipts[resumeId]"
            class="cleanup-receipt"
            aria-label="待核实删除状态"
          >
            <p role="status">{{ notice }}</p>
            <button
              type="button"
              class="button-secondary"
              :disabled="materialsBusy"
              @click="refreshCleanup(String(resumeId))"
            >
              查询上次删除状态
            </button>
          </section>
        </template>
      </template>
      <p v-else>账号身份已核实。当前身份没有试用训练权限。</p>
      <p class="subtle">本页不展示模拟记录或其他用户资料。</p>
      <p v-if="message" class="form-error" role="alert">{{ message }}</p>
    </section>
  </main>
</template>
