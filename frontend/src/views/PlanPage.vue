<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { usePlansStore } from '../stores/plans'
import { listVersions, type ResumeSummary } from '../api/materials'
import { planPreparation, type PlanOption } from '../api/plans'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const plans = usePlansStore()
const resumeId = ref(typeof route.query.resumeId === 'string' ? route.query.resumeId : '')
const resumeVersion = ref(
  typeof route.query.resumeVersionId === 'string' ? route.query.resumeVersionId : '',
)
const jdVersion = ref('')
const resumes = ref<ResumeSummary[]>([])
const jobs = ref<ResumeSummary[]>([])
const options = ref<PlanOption[]>([])
const selectedOption = ref('0')
const available = ref(false)
const confirmed = ref(false)
const acceptCost = ref(false)
const inputConfirmed = ref(false)
const preparationMessage = ref('')
let generation = 0
const replacements = computed(() => {
  const binding = plans.view?.knowledgeBinding
  return binding
    ? options.value.filter(
        (o) =>
          o.collectionId !== binding.collectionId &&
          o.configDigest === binding.configDigest &&
          o.technology === binding.technology &&
          o.technologyVersion === binding.technologyVersion &&
          o.scenario === binding.scenario &&
          o.claimKey === binding.claimKey,
      )
    : []
})
function sourceHref(url: string): string | undefined {
  try {
    const parsed = new URL(url)
    return ['https:', 'http:'].includes(parsed.protocol) ? parsed.href : undefined
  } catch {
    return undefined
  }
}
async function load(): Promise<void> {
  const current = ++generation
  available.value = false
  options.value = []
  resumes.value = []
  jobs.value = []
  if (session.status !== 'authenticated') return
  try {
    if (typeof route.query.operationId === 'string' && !plans.receipt)
      await plans.openLink(
        route.query.operationId,
        typeof route.query.kind === 'string' ? route.query.kind : 'GENERATE_PLAN',
      )
    if (plans.receipt) await plans.query()
    if (current !== generation) return
    if (plans.view) {
      resumeId.value = plans.view.resumeId
      resumeVersion.value = plans.view.resumeVersionId
      jdVersion.value = plans.view.jdVersionId
    }
    const readiness = await planPreparation()
    if (current !== generation) return
    options.value = readiness.options
    available.value = readiness.available
    preparationMessage.value = readiness.available
      ? '当前为隔离工程验证，计划不可用于真实面试'
      : '计划生成服务尚未开放，已确认资料不会丢失'
    if (resumeId.value) {
      const [r, j] = await Promise.all([
        listVersions(resumeId.value, 'resume'),
        listVersions(resumeId.value, 'jd'),
      ])
      if (current !== generation) return
      resumes.value = r
      jobs.value = j
      resumeVersion.value = resumeVersion.value || r[0]?.versionId || ''
      jdVersion.value = plans.view?.jdVersionId || j[0]?.versionId || ''
    }
    await plans.query()
  } catch {
    if (current === generation)
      preparationMessage.value = '资料或知识范围暂不可核实，请返回资料页或重试'
  }
}
async function start(): Promise<void> {
  const option = options.value[Number(selectedOption.value)]
  if (!option || !confirmed.value || !resumeVersion.value || !jdVersion.value) return
  await plans.start({
    ...option,
    resumeId: resumeId.value,
    resumeVersionId: resumeVersion.value,
    jdVersionId: jdVersion.value,
    materialsConfirmed: true,
    restrictedCoverageConfirmed: true,
    newPlanIntent: true,
  })
}
async function prepareNew(): Promise<void> {
  const root = plans.view?.resumeId
  if (!root || !plans.canNewPlan) return
  plans.newPlan()
  confirmed.value = false
  acceptCost.value = false
  inputConfirmed.value = false
  resumeId.value = root
  resumeVersion.value = ''
  jdVersion.value = ''
  // 去掉旧操作和旧版本地址，再重新读当前本人版本；这一步只准备，不自动生成。
  await router.replace({ name: 'plan', query: { resumeId: root } })
  await load()
}
watch(
  () => [session.status, session.identity?.accountId] as const,
  () => {
    generation++
    confirmed.value = false
    acceptCost.value = false
    inputConfirmed.value = false
    if (session.status === 'authenticated') void load()
    else {
      available.value = false
      options.value = []
      resumes.value = []
      jobs.value = []
    }
  },
)
watch(
  () => plans.view?.generation,
  () => {
    acceptCost.value = false
    inputConfirmed.value = false
  },
)
const timer = setInterval(() => {
  if (
    session.status === 'authenticated' &&
    !plans.busy &&
    plans.receipt &&
    (!plans.view ||
      ['QUEUED', 'PREPARING_EVIDENCE', 'RUNNING', 'CANDIDATE_READY'].includes(plans.view.state))
  )
    void plans.query()
}, 1000)
onMounted(() => {
  void load()
})
onUnmounted(() => {
  generation++
  clearInterval(timer)
})
onBeforeRouteLeave(() => {
  plans.hide()
})
</script>

<template>
  <main class="workspace-shell">
    <header>
      <h1>单场面试计划</h1>
      <RouterLink to="/app">返回本人资料</RouterLink>
    </header>
    <template v-if="session.status === 'authenticated'">
      <p>{{ preparationMessage }}</p>
      <section v-if="resumeId && !plans.view?.plan">
        <label for="plan-resume-version">固定简历版本</label>
        <select id="plan-resume-version" v-model="resumeVersion" :disabled="!!plans.receipt">
          <option v-for="v in resumes" :key="v.versionId" :value="v.versionId">
            简历 v{{ v.versionNo }}
          </option>
        </select>
        <label for="plan-jd-version">固定JD版本</label>
        <select id="plan-jd-version" v-model="jdVersion" :disabled="!!plans.receipt">
          <option v-for="v in jobs" :key="v.versionId" :value="v.versionId">
            JD v{{ v.versionNo }}
          </option>
        </select>
        <label for="plan-knowledge">已审核知识覆盖范围</label>
        <select id="plan-knowledge" v-model="selectedOption" :disabled="!!plans.receipt">
          <option v-for="(o, i) in options" :key="`${o.collectionId}-${i}`" :value="String(i)">
            {{ o.technology }} {{ o.technologyVersion }} / {{ o.scenario }}
          </option>
        </select>
        <p>
          默认8道主问题、中等难度；本切片只查看默认安排，不提前展示题目或答案。生成不占面试次数。
        </p>
        <label
          ><input
            id="plan-confirm"
            v-model="confirmed"
            type="checkbox"
          />我已核对资料，并确认只在选定知识覆盖范围创建这份计划</label
        >
        <button
          id="plan-generate"
          type="button"
          :disabled="
            !available ||
            !confirmed ||
            !jdVersion ||
            !options.length ||
            plans.busy ||
            (!!plans.receipt && plans.view?.state !== 'DRAFT')
          "
          @click="start"
        >
          生成默认计划
        </button>
      </section>
      <p v-else-if="!plans.receipt">请从本人资料页选择已确认的简历和JD。</p>
      <p role="status">{{ plans.message }}</p>
      <button
        v-if="plans.receipt"
        id="plan-query"
        type="button"
        :disabled="plans.busy"
        @click="plans.query"
      >
        核实原操作
      </button>
      <section v-if="plans.view" id="plan-status">
        <p>固定资料：简历 {{ plans.view.resumeVersionId }} / JD {{ plans.view.jdVersionId }}</p>
        <RouterLink
          v-if="plans.view.operationId"
          :to="{
            name: 'plan',
            query: {
              resumeId: plans.view.resumeId,
              operationId: plans.view.operationId,
              kind: 'GENERATE_PLAN',
            },
          }"
          >原操作恢复链接</RouterLink
        >
        <p>
          状态：{{ plans.view.state }}
          <span v-if="plans.view.reason">（{{ plans.view.reason }}）</span>
        </p>
        <p>
          初始 {{ plans.view.initialUsed }}/1；自动 {{ plans.view.autoUsed }}/1；手动
          {{ plans.view.manualUsed }}/2。查询准备 {{ plans.view.queryUsed }}/3，独立计数。
        </p>
        <p v-if="!plans.view.materialsCurrent">
          资料已更新，本计划不与新版本混用，请返回资料准备新计划。
        </p>
        <template
          v-if="['WAITING_MANUAL', 'BLOCKED_KNOWLEDGE', 'PAUSED_LOCAL'].includes(plans.view.state)"
        >
          <label v-if="plans.view.requiresCostConfirmation"
            ><input
              id="plan-cost-confirm"
              v-model="acceptCost"
              type="checkbox"
            />我确认上次可能已执行并收费，再次调用可能产生重复费用</label
          >
          <button
            v-if="plans.view.canAcceptCandidate"
            id="plan-accept"
            type="button"
            :disabled="plans.busy"
            @click="plans.resume('ACCEPT', false)"
          >
            恢复接收原候选
          </button>
          <button
            v-if="plans.view.canRetry"
            id="plan-retry"
            type="button"
            :disabled="plans.busy || (plans.view.requiresCostConfirmation && !acceptCost)"
            @click="plans.resume('RETRY', acceptCost)"
          >
            按原预算手动重试
          </button>
          <template v-if="plans.view.state === 'BLOCKED_KNOWLEDGE'">
            <button
              v-for="replacement in replacements"
              :key="replacement.collectionId"
              type="button"
              :disabled="plans.busy"
              @click="plans.resume('RETRY', acceptCost, replacement)"
            >
              明确改用同条件新审核集合（保留原预算）
            </button>
            <button type="button" :disabled="plans.busy" @click="load">重新核实可用知识</button>
          </template>
        </template>
        <template
          v-if="
            plans.view.state === 'WAITING_INPUT' &&
            plans.view.materialReview.state === 'REVIEW_REQUIRED'
          "
        >
          <p>资料存在待核对条件，请确认当前核对结果后继续；不替你补充经历。</p>
          <ul>
            <li v-for="(finding, i) in plans.view.materialReview.findings" :key="i">
              {{ finding.kind }} · {{ finding.source }}：{{ finding.quote }}
            </li>
          </ul>
          <label><input v-model="inputConfirmed" type="checkbox" />我确认当前资料核对条件</label>
          <button
            type="button"
            :disabled="!inputConfirmed || plans.busy"
            @click="plans.resume('CONFIRM_INPUT', false)"
          >
            确认条件后继续原操作
          </button>
        </template>
        <section v-if="plans.view.plan" id="plan-result">
          <h2>默认考察安排</h2>
          <p>
            {{ plans.view.plan.content.totalQuestions }}道主问题；预计
            {{ plans.view.plan.content.estimatedMinutes ?? '暂不可用' }} 分钟，不强制结束。
          </p>
          <article v-for="(topic, i) in plans.view.plan.content.topics" :key="`${topic.name}-${i}`">
            <h3>{{ topic.name }} · {{ topic.questionCount }}题 · 中等</h3>
            <p>{{ topic.reason }}</p>
            <p>简历关联：{{ topic.resumeQuote }}</p>
            <p>JD关联：{{ topic.jdQuote }}</p>
          </article>
          <ul>
            <li v-for="source in plans.view.plan.sources" :key="source.versionId">
              <a
                v-if="sourceHref(source.sourceUrl)"
                :href="sourceHref(source.sourceUrl)"
                target="_blank"
                rel="noopener noreferrer"
                >{{ source.sourceAnchor || source.sourceUrl }}</a
              ><span v-else>来源链接协议不可访问</span>
            </li>
          </ul>
          <p>当前为工程验证结果，尚未开放真实面试、调整和确认功能。</p>
        </section>
        <button v-if="plans.canNewPlan" id="plan-new" type="button" @click="prepareNew">
          {{ plans.view.state === 'INVALIDATED' ? '按更新资料准备新计划' : '准备另一份计划' }}
        </button>
      </section>
    </template>
    <p v-else>正在核实身份，私有计划已隐藏。</p>
  </main>
</template>
