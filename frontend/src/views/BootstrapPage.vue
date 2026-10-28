<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { onMounted } from 'vue'
import { useBootstrapStore } from '../stores/bootstrap'

const bootstrap = useBootstrapStore()
const { connectionStatus, message } = storeToRefs(bootstrap)

onMounted(() => {
  void bootstrap.checkBackend()
})
</script>

<template>
  <main class="bootstrap-shell">
    <header class="page-header">
      <span class="stage-label">开发基础 · 工程初始化</span>
      <h1>AI 智能面试教练</h1>
      <p>当前页面用于检查工程启动与前后端连通性，面试业务功能尚未实现。</p>
    </header>

    <section class="status-panel" aria-labelledby="connection-heading">
      <h2 id="connection-heading">后端应用连接</h2>
      <p
        class="connection-message"
        :data-status="connectionStatus"
        role="status"
        aria-live="polite"
      >
        {{ message }}
      </p>
      <button :disabled="connectionStatus === 'checking'" @click="bootstrap.checkBackend">
        {{ connectionStatus === 'checking' ? '正在检查…' : '检查后端健康状态' }}
      </button>
      <p class="supporting-text">
        默认 bootstrap 模式只检查应用启动。显示已连接，不表示数据库、模型服务或业务验收已通过。
      </p>
    </section>

    <section class="scope-panel" aria-labelledby="scope-heading">
      <h2 id="scope-heading">本轮初始化范围</h2>
      <p>已建立前端入口、基础路由、请求适配和状态管理，后续按详细设计逐步开发。</p>
      <p>计划采用已审核知识库的 RAG 检索加大模型生成与评价，当前不提供实时搜索。</p>
      <p class="supporting-text">
        登录、简历与 JD 输入、面试计划、问答、报告、额度和删除能力均尚未实现。
      </p>
    </section>
  </main>
</template>
