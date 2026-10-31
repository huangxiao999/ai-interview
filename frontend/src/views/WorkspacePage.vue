<script setup lang="ts">
import { watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { useMaterialsStore } from '../stores/materials'
import { ApiError } from '../api/http'
import MaterialVersionsPanel from './MaterialVersionsPanel.vue'
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
  pendingVersionSave,
} = storeToRefs(materials)

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
  try {
    const loaded = await listResumes()
    if (generation === materials.generation && materials.isLatestListRequest(requestId))
      items.value = loaded
  } catch (error: unknown) {
    if (generation === materials.generation && materials.isLatestListRequest(requestId))
      showError(error)
  }
}

async function saveDraft(): Promise<void> {
  if (materialsBusy.value || status.value !== 'authenticated') return
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
  if (materialsBusy.value) return
  const generation = materials.generation
  materialsBusy.value = true
  materialsMessage.value = ''
  try {
    const detail = await getResume(resumeId)
    if (generation === materials.generation) selected.value = detail
  } catch (error: unknown) {
    if (generation === materials.generation) showError(error)
  } finally {
    if (generation === materials.generation) materialsBusy.value = false
  }
}

async function prepareDelete(resumeId: string): Promise<void> {
  if (materialsBusy.value) return
  const generation = materials.generation
  materialsBusy.value = true
  try {
    const impact = await deletionImpact(resumeId)
    if (generation !== materials.generation) return
    confirmDeleteId.value = resumeId
    deleteVersionCount.value = impact.versionCount
    deleteJdVersionCount.value = impact.jdVersionCount
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
  try {
    const result = await deleteResume(resumeId, {
      accountId,
      isCurrent: () =>
        generation === materials.generation &&
        status.value === 'authenticated' &&
        identity.value?.accountId === accountId,
    })
    if (generation !== materials.generation) return
    confirmDeleteId.value = null
    pendingDelete.value = null
    // 删除确认可能来自另一份简历；不得清除当前根的草稿和未决版本操作。
    if (selected.value?.resumeId === resumeId) selected.value = null
    if (materials.versionResumeId === resumeId) materials.selectVersionRoot(null)
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
    pendingDelete.value = null
    confirmDeleteId.value = null
    if (selected.value?.resumeId === resumeId) selected.value = null
    if (materials.versionResumeId === resumeId) materials.selectVersionRoot(null)
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
  if (await session.logout()) await router.replace('/login')
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
      <h1 id="workspace-heading">我的简历</h1>
      <template v-if="identity?.authorities.includes('TRIAL_USER')">
        <p>可粘贴简历、确认 JD 并查看资料版本。PDF/DOCX 和面试训练将在后续任务中开放。</p>
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
            <p v-if="items.length === 0">暂无已保存简历。</p>
            <ul v-else class="material-list">
              <li v-for="item in items" :key="item.resumeId">
                <span>第 {{ item.versionNo }} 版 · {{ item.codepointCount }} 字符</span>
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
          <pre>{{ selected.body }}</pre>
        </section>
        <MaterialVersionsPanel
          v-if="selected"
          :resume-id="selected.resumeId"
          @saved="loadItems()"
        />
        <section
          v-if="confirmDeleteId"
          class="material-delete"
          role="alertdialog"
          aria-label="确认删除简历"
        >
          <h2>确认删除整份简历？</h2>
          <p>
            将删除该简历当前的 {{ deleteVersionCount }} 个正文版本和
            {{ deleteJdVersionCount }} 个JD版本。删除受理后立即禁访且无法撤销。
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
            @click="confirmDeleteId = null"
          >
            取消
          </button>
        </section>
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
      </template>
      <p v-else>账号身份已核实。当前身份没有试用训练权限。</p>
      <p class="subtle">本页不展示模拟记录或其他用户资料。</p>
      <p v-if="message" class="form-error" role="alert">{{ message }}</p>
    </section>
  </main>
</template>
