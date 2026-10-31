<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { useKnowledgeIndexStore } from '../stores/knowledgeIndex'
import { useSessionStore } from '../stores/session'
const session = useSessionStore()
const index = useKnowledgeIndexStore()
onMounted(() => {
  void index.load()
})
watch(
  () => session.identity?.accountId,
  () => {
    void index.load()
  },
)
</script>

<template>
  <main class="index-page">
    <RouterLink to="/app">返回工作区</RouterLink>
    <h1>公共知识索引构建</h1>
    <template
      v-if="
        session.status === 'authenticated' &&
        session.identity?.authorities.includes('KNOWLEDGE_MAINTAINER')
      "
    >
      <p>仅构建审核通过的固定版本。完成不代表已发布、可检索或知识质量通过。</p>
      <p>向量服务未就绪时不会派发请求。每批最多3次，失败后由维护者确认重试。</p>
      <p role="status">{{ index.message }}</p>
      <button :disabled="index.busy" @click="index.load">重新加载批准版本</button>
      <label
        >固定知识版本
        <select
          id="index-version"
          v-model="index.selected"
          :disabled="index.busy || !!index.receipt"
          @change="index.choose"
        >
          <option value="">请选择版本</option>
          <option v-for="v in index.versions" :key="v.versionId" :value="v.versionId">
            {{ v.title }} · V{{ v.versionNo }} · {{ v.versionId }}
          </option>
        </select>
      </label>
      <p v-if="index.versions.length === 0">暂无可构建的批准版本，或版本尚未加载。</p>
      <button :disabled="!index.selected || index.busy || !!index.receipt" @click="index.create">
        提交或核实同绑定构建
      </button>
      <div v-if="index.receipt">
        <p>原请求结果待核实；刷新只保留账号绑定的操作ID。</p>
        <button :disabled="index.busy" @click="index.verify">核实原索引操作</button>
        <button :disabled="index.busy || !index.pending" @click="index.send">
          重发同一固定索引请求
        </button>
      </div>
      <section v-if="index.current" aria-label="原构建状态">
        <h2>构建状态：{{ index.current.state }}</h2>
        <p id="index-build-id">{{ index.current.buildId }}</p>
        <p>
          输入摘要：{{ index.current.versionDigest }} · 配置摘要：{{ index.current.configDigest }}
        </p>
        <p v-if="index.current.errorCategory">当前原因：{{ index.current.errorCategory }}</p>
        <button :disabled="index.busy" @click="index.verify">核实原构建状态</button>
        <ul>
          <li v-for="batch in index.current.batches" :key="batch.batchId">
            批次{{ batch.ordinal + 1 }}：{{ batch.state }}，已用{{ batch.attempts }}次，剩余{{
              batch.remaining
            }}次；尝试{{ batch.attemptState ?? '尚未派发' }}
          </li>
        </ul>
        <template v-if="index.current.state === 'WAITING_MANUAL' && !index.receipt">
          <label
            ><input
              id="index-cost-ack"
              v-model="index.acknowledge"
              type="checkbox"
            />上次可能已执行并收费，我确认本次重试可能重复收费</label
          >
          <p>确认只绑定当前显示的原尝试；恢复已存候选只重新接收，不再次向量化。</p>
          <button :disabled="index.busy" @click="index.resume">恢复原批次</button>
        </template>
      </section>
    </template>
    <p v-else>当前身份无知识维护权限，请返回工作区核实身份。</p>
  </main>
</template>

<style scoped>
.index-page {
  max-width: 1100px;
  margin: 2rem auto;
  padding: 1rem;
  overflow-wrap: anywhere;
}
label {
  display: block;
  margin: 1rem 0;
}
select {
  max-width: 100%;
  padding: 0.5rem;
}
button {
  padding: 0.6rem;
  margin: 0.4rem;
}
</style>
