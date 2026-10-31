<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { useKnowledgeStore } from '../stores/knowledge'
import { useSessionStore } from '../stores/session'
const knowledge = useKnowledgeStore()
const session = useSessionStore()
watch(
  () => session.identity?.accountId,
  () => {
    void knowledge.refresh()
  },
)
onMounted(() => {
  void knowledge.refresh()
})
function safeUrl(value: string): string | undefined {
  try {
    const u = new URL(value)
    return ['http:', 'https:'].includes(u.protocol) ? u.href : undefined
  } catch {
    return undefined
  }
}
</script>

<template>
  <main class="knowledge-page">
    <RouterLink to="/app">返回工作区</RouterLink>
    <h1>公共知识版本与审核</h1>
    <template
      v-if="
        session.status === 'authenticated' &&
        session.identity?.authorities.includes('KNOWLEDGE_MAINTAINER')
      "
    >
      <p>人工核实来源、原文与适用条件后再审核。批准不代表已向量化或评分质量通过。</p>
      <p role="status">{{ knowledge.message }}</p>
      <div v-if="knowledge.unresolved">
        <p>原操作待核实；当前输入已固定。</p>
        <button :disabled="knowledge.busy" @click="knowledge.verify">核实原操作</button>
        <button :disabled="knowledge.busy || !knowledge.pending" @click="knowledge.sendOriginal">
          重发同一固定请求
        </button>
        <p v-if="!knowledge.pending">
          刷新后仅恢复原操作编号用于查询，不恢复正文草稿，也不重新提交。
        </p>
      </div>
      <button :disabled="knowledge.busy || knowledge.unresolved" @click="knowledge.newDocument">
        新建知识资料
      </button>
      <button :disabled="knowledge.busy" @click="knowledge.refresh">重新加载版本列表</button>
      <p v-if="knowledge.listState === 'loading'">正在读取版本…</p>
      <p v-else-if="knowledge.listState === 'empty'">暂无知识版本</p>
      <p v-else-if="knowledge.listState === 'failed'">读取失败，请重新加载</p>
      <ul>
        <li v-for="item in knowledge.items" :key="item.versionId">
          <button
            :data-knowledge-version="item.versionId"
            :disabled="knowledge.busy || knowledge.unresolved"
            @click="knowledge.select(item)"
          >
            {{ item.title }} · V{{ item.versionNo }} · {{ item.state }}
          </button>
        </li>
      </ul>
      <form @submit.prevent="knowledge.beginSubmit">
        <h2>{{ knowledge.root ? '追加该资料的新版本' : '提交知识资料' }}</h2>
        <fieldset :disabled="knowledge.busy || knowledge.unresolved">
          <label>标题<input id="knowledge-title" v-model="knowledge.draft.title" required /></label>
          <label
            >原文<textarea
              id="knowledge-body"
              v-model="knowledge.draft.body"
              required
              rows="10"
            ></textarea>
          </label>
          <label
            >来源链接<input
              id="knowledge-url"
              v-model="knowledge.draft.sourceUrl"
              required
              type="url"
          /></label>
          <label
            >章节或段落定位<input
              id="knowledge-anchor"
              v-model="knowledge.draft.sourceAnchor"
              required
          /></label>
          <label
            >技术版本、适用及不适用场景<textarea
              id="knowledge-applicability"
              v-model="knowledge.draft.applicability"
              required
            ></textarea>
          </label>
          <label
            >来源可用范围或许可说明<input
              id="knowledge-usage"
              v-model="knowledge.draft.usageNote"
              required
          /></label>
          <label
            >人工核实说明<input
              id="knowledge-verification"
              v-model="knowledge.draft.verificationNote"
              required
          /></label>
          <label
            >核实日期<input
              id="knowledge-date"
              v-model="knowledge.draft.verifiedOn"
              type="date"
              required
          /></label>
          <p>
            原文最多100,000码点；标题200、链接2,048、定位1,000、适用条件4,000、许可和核实说明各2,000码点，超限拒绝，不截断。
          </p>
          <button type="submit">提交不可变版本</button>
        </fieldset>
      </form>
      <section v-if="knowledge.selected" aria-label="固定版本审核">
        <h2>固定版本 V{{ knowledge.selected.versionNo }} · {{ knowledge.selected.state }}</h2>
        <p>{{ knowledge.selected.versionId }} · 摘要 {{ knowledge.selected.digest }}</p>
        <pre id="knowledge-original">{{ knowledge.selected.body }}</pre>
        <a :href="safeUrl(knowledge.selected.sourceUrl)" target="_blank" rel="noopener noreferrer"
          >查看人工登记来源</a
        >
        <p>定位：{{ knowledge.selected.sourceAnchor }}</p>
        <p>适用条件：{{ knowledge.selected.applicability }}</p>
        <p>许可：{{ knowledge.selected.usageNote }}</p>
        <p>核实：{{ knowledge.selected.verificationNote }} · {{ knowledge.selected.verifiedOn }}</p>
        <p v-if="knowledge.selected.reviewReason">
          审核说明：{{ knowledge.selected.reviewReason }} · {{ knowledge.selected.reviewedAt }}
        </p>
        <form v-if="knowledge.selected.state === 'PENDING_REVIEW'" @submit.prevent>
          <label
            >审核说明<input
              id="knowledge-review-reason"
              v-model="knowledge.reason"
              :disabled="knowledge.busy || knowledge.unresolved"
          /></label>
          <button
            :disabled="knowledge.busy || knowledge.unresolved || !knowledge.reason.trim()"
            @click="knowledge.beginReview('APPROVE')"
          >
            确认已核实并批准该版本
          </button>
          <button
            :disabled="knowledge.busy || knowledge.unresolved || !knowledge.reason.trim()"
            @click="knowledge.beginReview('REJECT')"
          >
            拒绝该版本
          </button>
        </form>
      </section>
    </template>
    <p v-else>当前身份不能访问知识维护；请返回工作区核实登录状态。</p>
  </main>
</template>

<style scoped>
.knowledge-page {
  max-width: 1100px;
  margin: 2rem auto;
  padding: 1rem;
}
label {
  display: block;
  margin: 1rem 0;
}
input,
textarea {
  display: block;
  width: 100%;
  padding: 0.6rem;
}
button {
  margin: 0.3rem;
  padding: 0.6rem;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f1f4f8;
  padding: 1rem;
}
</style>
