<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { usePlanHistoryStore } from '../stores/planHistory'
const route = useRoute(),
  session = useSessionStore(),
  history = usePlanHistoryStore()
function root(): string {
  return typeof route.query.resumeId === 'string' ? route.query.resumeId : ''
}
function load(): void {
  void history.load(root())
}
function displayTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}
const labels: Record<string, string> = {
  DRAFT: '工作稿，尚未生成',
  QUEUED: '排队中',
  PREPARING_EVIDENCE: '准备依据中',
  RUNNING: '生成中',
  CANDIDATE_READY: '候选待接收',
  WAITING_INPUT: '等待核对资料',
  BLOCKED_KNOWLEDGE: '知识依据不足',
  WAITING_MANUAL: '等待手动恢复',
  PAUSED_LOCAL: '本地暂停，需核实',
  SUCCEEDED: '计划已生成，尚未开始面试',
  FAILED_FINAL: '生成明确失败',
  INVALIDATED: '原操作已失效',
}
watch(
  () => [route.query.resumeId, session.status, session.identity?.accountId] as const,
  () => {
    if (session.status === 'authenticated') load()
  },
)
onMounted(() => {
  load()
})
onUnmounted(() => {
  history.hide()
})
</script>
<template>
  <main class="workspace-shell">
    <header>
      <h1>本人计划历史</h1>
      <RouterLink to="/app">返回本人资料</RouterLink>
    </header>
    <template v-if="session.status === 'authenticated'">
      <p>包含工作稿和失败操作；历史计划保持固定资料版本。工程结果不可用于真实面试。</p>
      <button id="history-refresh" type="button" :disabled="history.busy" @click="load">
        重新核实列表
      </button>
      <RouterLink v-if="root()" :to="{ name: 'planHistory' }">查看本人全部计划</RouterLink>
      <p v-if="history.state === 'loading'" role="status">正在读取历史</p>
      <p v-if="history.state === 'failed'" role="alert">
        {{ history.message }}；未将核实失败当作空列表。
      </p>
      <p v-if="history.state === 'ready' && !history.items.length" id="history-empty">
        当前范围暂无计划工作稿。
      </p>
      <article
        v-for="item in history.items"
        :key="item.workspaceId"
        class="plan-history-item"
        :data-workspace="item.workspaceId"
      >
        <h2>{{ labels[item.state] ?? '状态需核实' }}</h2>
        <p>
          创建于 {{ displayTime(item.createdAt) }}（UTC+8）；简历 v{{ item.resumeVersion }} / JD v{{
            item.jdVersion
          }}
        </p>
        <p v-if="!item.materialsCurrent">资料已换版，原记录只读回看；新资料需准备另一计划。</p>
        <p v-if="item.reason">原操作原因：{{ item.reason }}</p>
        <p v-if="item.planId">
          当前保存修订：{{ item.revisionId }} · {{ item.confirmed ? '已确认' : '尚未确认' }}
        </p>
        <RouterLink
          :to="{
            name: 'plan',
            query: {
              workspaceId: item.workspaceId,
              ...(item.revisionId ? { revisionId: item.revisionId } : {}),
            },
          }"
          >查看固定记录</RouterLink
        >
      </article>
      <button
        v-if="history.nextCursor"
        id="history-more"
        type="button"
        :disabled="history.busy"
        @click="history.load(root(), true)"
      >
        继续读取历史
      </button>
    </template>
    <p v-else>正在核实身份，私有历史已隐藏。</p>
  </main>
</template>

<style scoped>
.workspace-shell {
  max-width: 960px;
  margin: 0 auto;
  padding: 32px 24px;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}
.plan-history-item {
  margin: 20px 0;
  padding: 20px;
  border: 1px solid #d4dfdc;
  border-radius: 12px;
  background: white;
  overflow-wrap: anywhere;
}
.plan-history-item h2 {
  margin-top: 0;
  font-size: 20px;
}
</style>
