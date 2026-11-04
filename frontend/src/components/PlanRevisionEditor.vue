<script setup lang="ts">
import { onUnmounted, watch } from 'vue'
import { usePlanRevisionsStore } from '../stores/planRevisions'
const props = defineProps<{ planId: string; revisionId?: string; readOnly?: boolean }>()
const revisions = usePlanRevisionsStore()
watch(
  () => [props.planId, props.revisionId] as const,
  ([id, revision]) => void revisions.open(id, revision ?? ''),
  { immediate: true },
)
// 删除/身份核实会移除编辑器；失效所有在途响应，禁止恢复私有草稿。
onUnmounted(() => revisions.hide())
function choose(index: number, event: Event): void {
  const option = revisions.view?.options[Number((event.target as HTMLSelectElement).value)]
  const topic = revisions.draft[index]
  if (!option || !topic) return
  topic.coverage = option.binding
  topic.name = option.name
  topic.evidenceRefs = option.evidenceRefs
  revisions.dirty = true
}
function add(): void {
  const option = revisions.view?.options.find(
    (o) => !revisions.draft.some((t) => JSON.stringify(t.coverage) === JSON.stringify(o.binding)),
  )
  const first = revisions.draft[0]
  if (!option || !first) return
  revisions.draft.push({
    ...first,
    topicId: crypto.randomUUID(),
    order: revisions.draft.length + 1,
    name: option.name,
    coverage: option.binding,
    evidenceRefs: option.evidenceRefs,
    questionCount: 1,
  })
  revisions.dirty = true
}
function move(index: number, direction: number): void {
  const other = index + direction
  const a = revisions.draft[index],
    b = revisions.draft[other]
  if (!a || !b) return
  revisions.draft[index] = b
  revisions.draft[other] = a
  revisions.dirty = true
}
function remove(index: number): void {
  revisions.draft.splice(index, 1)
  revisions.dirty = true
}
</script>

<template>
  <section id="plan-revision-editor">
    <h2>调整与确认计划</h2>
    <p id="revision-message" role="status">{{ revisions.message }}</p>
    <button
      id="revision-query"
      type="button"
      :disabled="revisions.busy"
      @click="revisions.open(planId)"
    >
      核实原操作
    </button>
    <button
      id="revision-current"
      type="button"
      :disabled="revisions.busy || revisions.unknown"
      @click="revisions.refresh"
    >
      核实当前修订
    </button>
    <template v-if="revisions.view">
      <p id="revision-status">
        修订 {{ revisions.view.revision.sequence }} ·
        {{ revisions.view.current ? '当前' : '历史' }} ·
        {{ revisions.view.confirmed ? '已确认' : '未确认' }} · 仅工程验证
      </p>
      <p>
        主问题合计
        {{ revisions.total }}
        道（允许5～15）；调整后预计时长暂不可用，不调用模型估时。确认不开始面试、不扣次。
      </p>
      <p v-if="!revisions.view.canEdit">
        当前修订不能调整；请核实当前版本、资料换版及知识停用情况。
      </p>
      <p v-if="revisions.dirty" id="revision-dirty">草稿未保存，确认只接受已保存的修订。</p>
      <button
        id="revision-reload"
        type="button"
        :disabled="revisions.busy || revisions.unknown"
        @click="revisions.reloadDraft"
      >
        放弃草稿并载入已核实修订
      </button>
      <fieldset
        v-for="(topic, i) in revisions.draft"
        :key="topic.topicId"
        :disabled="readOnly || !revisions.editable"
        @input="revisions.dirty = true"
      >
        <legend>主题 {{ i + 1 }}：{{ topic.name }}</legend>
        <label
          >审核主题<select
            :value="
              revisions.view.options.findIndex(
                (o) => JSON.stringify(o.binding) === JSON.stringify(topic.coverage),
              )
            "
            :data-topic="i"
            @change="choose(i, $event)"
          >
            <option value="-1" disabled>原主题已不可用，请选择审核主题</option>
            <option
              v-for="(o, j) in revisions.view.options"
              :key="JSON.stringify(o.binding)"
              :value="j"
            >
              {{ o.name }}
            </option>
          </select></label
        >
        <label
          >题量<input
            v-model.number="topic.questionCount"
            class="revision-count"
            type="number"
            min="1"
            max="15"
            step="1"
        /></label>
        <label
          >难度<select v-model="topic.difficulty" class="revision-difficulty">
            <option value="BASIC">基础</option>
            <option value="MEDIUM">中等</option>
            <option value="ADVANCED">进阶</option>
          </select></label
        >
        <label>安排原因<textarea v-model="topic.reason" maxlength="2000" /></label>
        <label>固定简历引文<textarea v-model="topic.resumeQuote" maxlength="1000" /></label>
        <label>固定JD引文<textarea v-model="topic.jdQuote" maxlength="1000" /></label>
        <button type="button" :disabled="i === 0" @click="move(i, -1)">上移</button>
        <button type="button" :disabled="i === revisions.draft.length - 1" @click="move(i, 1)">
          下移
        </button>
        <button type="button" :disabled="revisions.draft.length === 1" @click="remove(i)">
          移除主题
        </button>
      </fieldset>
      <button
        id="revision-add"
        type="button"
        :disabled="
          readOnly ||
          !revisions.editable ||
          revisions.draft.length >= revisions.view.options.length ||
          revisions.draft.length >= 15
        "
        @click="add"
      >
        添加审核主题
      </button>
      <button
        id="revision-save"
        type="button"
        :disabled="readOnly || !revisions.editable || !revisions.dirty"
        @click="revisions.send('SAVE')"
      >
        保存新修订
      </button>
      <button
        id="revision-confirm"
        type="button"
        :disabled="
          readOnly ||
          revisions.busy ||
          revisions.unknown ||
          revisions.dirty ||
          !revisions.view.canConfirm
        "
        @click="revisions.send('CONFIRM')"
      >
        确认已保存修订
      </button>
    </template>
  </section>
</template>
