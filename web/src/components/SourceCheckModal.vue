<script setup lang="ts">
import { computed, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import { NButton, NEmpty, NInput, NModal, NProgress, NTag, useDialog, useMessage } from 'naive-ui'
import { sourceApi, type SourceCheckResult } from '@/api'
import AiRepairModal from '@/components/AiRepairModal.vue'
import { isRepairable, useAiRepairQueue, type RepairOutcome, type RepairQueueView } from '@/composables/useAiRepairQueue'
import { useAuthStore } from '@/stores/auth'
import { t } from '@/i18n'

/**
 * 书源校验：依次检查搜索、详情、目录、正文。结果只在弹窗里显示，不修改书源分组；
 * 有问题的书源可以在这里直接删除（单个或全部）
 */
const props = defineProps<{ ids: number[] }>()
const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ done: [] }>()
const message = useMessage()
const auth = useAuthStore()
const repairId = ref(0)
const showRepair = ref(false)

const keyword = ref('')
const running = ref(false)
const results = ref<SourceCheckResult[]>([])
const progress = ref({ done: 0, total: 0 })
const activeChecks = ref(new Map<number, { name: string; stage: string; started: number }>())
const now = ref(Date.now())
const clock = window.setInterval(() => { now.value = Date.now() }, 1000)
onBeforeUnmount(() => window.clearInterval(clock))
function activeStage(stage: string) {
  switch (stage) {
    case 'search': return t('搜索')
    case 'bookInfo': return t('详情')
    case 'toc': return t('目录')
    case 'content': return t('正文')
    default: return stage
  }
}
let controller: AbortController | null = null

const failed = computed(() => results.value.filter((r) => !r.ok))
const passed = computed(() => results.value.filter((r) => r.ok))
const category = ref('all')
const categories = computed(() => [
  { code: 'all', name: t('全部失效') },
  { code: 'dns', name: t('DNS 解析失败') },
  { code: 'timeout', name: t('超时') },
  { code: 'tls', name: t('TLS 握手失败') },
  { code: 'connection', name: t('连接中断') },
  { code: 'http', name: t('HTTP 错误') },
  { code: 'rule', name: t('规则错误') },
  { code: 'blocked', name: t('被拦截') },
  { code: 'noResult', name: t('无结果') },
  { code: 'other', name: t('其他错误') },
])
const invalid = computed(() => failed.value.filter((r) => r.errorType !== 'noResult'))
const selected = computed(() => category.value === 'all' ? invalid.value : failed.value.filter((r) => r.errorType === category.value))
const categoryName = computed(() => categories.value.find((c) => c.code === category.value)?.name ?? t('其他错误'))
function countCategory(code: string) {
  return code === 'all' ? invalid.value.length : failed.value.filter((r) => r.errorType === code).length
}
function resultCategory(r: SourceCheckResult) {
  return categories.value.find((c) => c.code === r.errorType)?.name ?? t('其他错误')
}
function resultStage(stage: string | null) {
  switch (stage) {
    case '搜索失效': return t('搜索失效')
    case '详情失效': return t('详情失效')
    case '目录失效': return t('目录失效')
    case '正文失效': return t('正文失效')
    case '校验超时': return t('校验超时')
    default: return stage
  }
}

watch(show, (v) => {
  if (v) {
    results.value = []
    category.value = 'all'
    removed.value = new Set()
    progress.value = { done: 0, total: props.ids.length }
  } else stop()
})

async function start() {
  controller?.abort()
  controller = new AbortController()
  results.value = []
  category.value = 'all'
  removed.value = new Set()
  progress.value = { done: 0, total: props.ids.length }
  running.value = true
  activeChecks.value.clear()
  queue.clear()
  try {
    await sourceApi.check(
      props.ids,
      keyword.value.trim(),
      (e) => {
        if (e.type === 'progress') {
          const p = e.progress
          activeChecks.value.set(p.id, { name: p.name, stage: p.stage, started: Date.now() - p.elapsed })
          return
        }
        if (e.type !== 'result') return
        activeChecks.value.delete(e.result.id)
        results.value.push(e.result)
        progress.value = { done: e.done, total: e.total }
      },
      controller.signal,
    )
  } catch (e: any) {
    if (e.name !== 'AbortError') message.error(e.message)
  } finally {
    running.value = false
    activeChecks.value.clear()
    controller = null
    emit('done')
  }
}

function stop() {
  controller?.abort()
}
onDeactivated(() => { stop(); show.value = false })
onBeforeUnmount(stop)

// ---------- 删除有问题的书源 ----------
const dialog = useDialog()
const deleting = ref(false)
/** 已删除的书源（从结果中标记出来，不再显示删除按钮） */
const removed = ref(new Set<number>())
const targets = computed(() => selected.value.filter((r) => !removed.value.has(r.id)))
const deletable = computed(() => targets.value.filter((r) => r.editable))

async function removeSources(list: SourceCheckResult[]) {
  if (!list.length) return
  deleting.value = true
  try {
    await sourceApi.batch(list.map((r) => r.id), 'delete')
    const next = new Set(removed.value)
    list.forEach((r) => next.add(r.id))
    removed.value = next
    message.success(t('已删除 {0} 个书源', [list.length]))
    emit('done')
  } catch (e: any) {
    message.error(e.message)
  } finally {
    deleting.value = false
  }
}

function removeOne(r: SourceCheckResult) {
  dialog.warning({
    title: t('删除书源'),
    content: t('确定删除书源「{0}」？', [r.name]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: () => removeSources([r]),
  })
}

// ---------- 按当前错误类型依次 AI 修复 ----------
const canRepair = computed(() => auth.isAdmin || !!auth.me?.canAddSource)
const repairable = computed(() => deletable.value.filter((r) => isRepairable(r.errorType)))
/** 当前分类中因网络/验证问题不能修复的书源数 */
const networkBlocked = computed(() => deletable.value.length - repairable.value.length)
const queue = useAiRepairQueue()
const queueView = computed<RepairQueueView | undefined>(() => {
  const item = queue.current.value
  if (!item) return undefined
  return { name: item.name, position: queue.index.value + 1, total: queue.items.value.length, next: queue.next.value?.name ?? null, cancelled: queue.cancelled.value, autoStart: true }
})
const outcomeLabels: Record<RepairOutcome['kind'], string> = {
  saved: t('已保存'), passed: t('无需修复'), unsaved: t('未保存'), skipped: t('已跳过'), failed: t('失败'),
}
const outcomeCounts = computed(() => {
  const counts: Record<RepairOutcome['kind'], number> = { saved: 0, passed: 0, unsaved: 0, skipped: 0, failed: 0 }
  for (const d of queue.done.value) counts[d.outcome.kind]++
  return counts
})
function repairOne(r: SourceCheckResult) {
  queue.clear()
  repairId.value = r.id
  showRepair.value = true
}
function repairCategory() {
  const list = repairable.value
  if (!list.length) return
  queue.start(list.map((r) => ({ id: r.id, name: r.name })))
  repairId.value = list[0].id
  showRepair.value = true
}
function onQueue(action: 'next' | 'end', outcome: RepairOutcome) {
  const next = action === 'next' ? queue.record(outcome) : (queue.cancel(), queue.record(outcome))
  if (next) repairId.value = next.id
  else showRepair.value = false
}
function onRepairSaved() {
  results.value = results.value.map((r) => r.id === repairId.value ? { ...r, ok: true, stage: null, error: null, errorType: null } : r)
  emit('done')
}

function removeAllFailed() {
  const list = deletable.value
  const skipped = targets.value.length - list.length
  dialog.warning({
    title: t('删除有问题的书源'),
    content: t('确定删除「{0}」中的 {1} 个书源？跳过 {2} 个无删除权限的书源。', [categoryName.value, list.length, skipped])
      + (category.value === 'all' ? t('全部失效不包含无结果的书源。') : ''),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      await removeSources(list)
      if (skipped) message.info(t('已跳过 {0} 个无删除权限的书源', [skipped]))
    },
  })
}
</script>

<template>
  <n-modal v-model:show="show" preset="card" :title="t('检测书源')" style="width: 640px; max-width: 94vw" :mask-closable="!running">
    <p class="muted small" style="margin-top: 0">
      {{ t('依次检测搜索、详情、目录、正文。检测不会修改书源，有问题的书源可以在下面直接删除或 AI 修复。') }}
    </p>
    <div class="check-bar">
      <n-input v-model:value="keyword" size="small" :disabled="running" :placeholder="t('校验关键字（留空用书源自带的，没有则用“牧神记”）')" />
      <n-button v-if="!running" size="small" type="primary" @click="start">{{ results.length ? t('重新检测') : t('开始检测') }}</n-button>
      <n-button v-else size="small" @click="stop">{{ t('停止') }}</n-button>
    </div>
    <template v-if="running || results.length">
      <n-progress
        type="line"
        :percentage="progress.total ? Math.round((progress.done / progress.total) * 100) : 0"
        :processing="running"
        :show-indicator="false"
        style="margin: 12px 0 6px"
      />
      <div class="summary small">
        <span>
          {{ t('已检测 {0}/{1}', [progress.done, progress.total]) }} ·
          <span class="ok">{{ t('通过 {0}', [passed.length]) }}</span> ·
          <span class="bad">{{ t('失败 {0}', [failed.length]) }}</span>
        </span>
        <n-button v-if="!running && targets.length" size="tiny" type="error" secondary :loading="deleting" @click="removeAllFailed">
          {{ t('删除「{0}」（{1}）', [categoryName, deletable.length]) }}
        </n-button>
      </div>
      <div v-if="running" class="muted small" aria-live="polite">
        <div v-for="[id, item] in activeChecks" :key="id">
          {{ item.name }} · {{ activeStage(item.stage) }} · {{ Math.max(0, Math.floor((now - item.started) / 1000)) }}s
        </div>
      </div>
      <div class="category-filters">
        <n-button v-for="c in categories" :key="c.code" size="tiny" :type="category === c.code ? 'primary' : 'default'" @click="category = c.code">
          {{ c.name }} ({{ countCategory(c.code) }})
        </n-button>
      </div>
      <div v-if="!running && (deletable.length || queue.done.value.length)" class="repair-bar small">
        <div class="repair-bar-main">
          <n-button
            size="tiny"
            type="primary"
            secondary
            :disabled="!repairable.length || !canRepair || showRepair"
            :title="!canRepair ? t('需要管理员授予添加书源权限') : undefined"
            @click="repairCategory"
          >
            {{ t('AI 依次修复「{0}」（{1}）', [categoryName, repairable.length]) }}
          </n-button>
          <span v-if="!canRepair" class="muted">{{ t('需要管理员授予添加书源权限') }}</span>
        </div>
        <div v-if="networkBlocked" class="muted">
          {{ t('跳过 {0} 个无结果、被拦截或网络错误（DNS、TLS、连接中断、超时）的书源：修改规则解决不了，请先排查网络或完成网站验证。', [networkBlocked]) }}
        </div>
        <div v-if="queue.done.value.length && !queue.current.value" class="muted" aria-live="polite">
          {{ t('修复队列已结束：处理 {0}/{1} 个', [queue.done.value.length, queue.items.value.length]) }}
          <template v-for="(count, kind) in outcomeCounts" :key="kind"><template v-if="count"> · {{ outcomeLabels[kind] }} {{ count }}</template></template>
          <template v-if="queue.remaining.value > 0"> · {{ t('未处理 {0}', [queue.remaining.value]) }}</template>
        </div>
        <ul v-if="!queue.current.value && queue.done.value.some((d) => d.outcome.kind === 'failed')" class="queue-failures">
          <template v-for="d in queue.done.value" :key="d.item.id">
            <li v-if="d.outcome.kind === 'failed'">{{ d.item.name }}：{{ d.outcome.error }}</li>
          </template>
        </ul>
      </div>
      <div class="results">
        <div v-for="r in selected" :key="r.id" class="result" :class="{ removed: removed.has(r.id) }">
          <div class="result-main">
            <span class="ellipsis result-name">{{ r.name }}</span>
            <n-tag v-if="r.ok" size="small" type="success" :bordered="false">{{ t('通过') }}</n-tag>
            <n-tag v-else size="small" :type="r.errorType === 'noResult' ? 'warning' : 'error'" :bordered="false">{{ resultCategory(r) }}</n-tag>
            <span class="muted small result-time">{{ (r.time / 1000).toFixed(1) }}s</span>
            <span v-if="removed.has(r.id)" class="muted small">{{ t('已删除') }}</span>
            <n-button v-else-if="!r.ok && r.editable" size="tiny" quaternary type="error" :disabled="deleting" @click="removeOne(r)">
              {{ t('删除') }}
            </n-button>
            <n-button v-if="!r.ok && r.editable && !removed.has(r.id)" size="small" :disabled="running || !canRepair" :title="!canRepair ? t('需要管理员授予添加书源权限') : undefined" @click="repairOne(r)">{{ t('AI 修复') }}</n-button>
            <span v-if="!r.ok && r.editable && !removed.has(r.id) && !canRepair" class="muted small">{{ t('需要管理员授予添加书源权限') }}</span>
          </div>
          <div v-if="r.stage || r.error" class="muted small result-err">{{ resultStage(r.stage) }} · {{ r.error }}</div>
          <div v-if="!r.ok && !r.editable" class="muted small">{{ t('公共书源，只有管理员可以删除') }}</div>
        </div>
      </div>
    </template>
    <n-empty v-else :description="t('已选 {0} 个书源', [ids.length])" style="padding: 20px 0" />
  </n-modal>
  <ai-repair-modal
    v-model:show="showRepair"
    :source-id="repairId"
    :keyword="keyword"
    :queue="queueView"
    @saved="onRepairSaved"
    @queue="onQueue"
    @cancel-queue="queue.cancel()"
  />
</template>

<style scoped>
.check-bar {
  display: flex;
  gap: 8px;
}

.summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
  color: var(--app-text-2);
}

.result.removed .result-name {
  text-decoration: line-through;
  color: var(--app-text-3, #999);
}

.summary .ok {
  color: #18a058;
}

.summary .bad {
  color: #d03050;
}

.results {
  max-height: 50vh;
  overflow-y: auto;
  border-top: 1px solid var(--app-border);
}

.result {
  padding: 8px 2px;
  border-bottom: 1px solid var(--app-border);
}

.result-main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.result-name {
  flex: 1;
  min-width: 0;
}

.result-time {
  flex-shrink: 0;
}

.result-err {
  margin-top: 2px;
  word-break: break-all;
}

.small {
  font-size: 12.5px;
}

.repair-bar {
  display: grid;
  gap: 4px;
  margin-bottom: 8px;
}

.repair-bar-main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.queue-failures {
  margin: 0;
  padding-left: 18px;
  color: var(--app-text-2);
  word-break: break-all;
}

.category-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
</style>
