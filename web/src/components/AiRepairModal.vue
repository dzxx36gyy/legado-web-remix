<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { NAlert, NButton, NEmpty, NInput, NModal, NSelect, NSpace, useDialog, useMessage } from 'naive-ui'
import { aiApi, sourceApi, type AiModel, type AiRepairDiff, type AiRepairStage } from '@/api'
import { parseAiRepairEvent } from '@/api/aiEvents'
import type { RepairOutcome, RepairQueueView } from '@/composables/useAiRepairQueue'
import { t } from '@/i18n'

const props = defineProps<{ sourceId?: number; keyword?: string; queue?: RepairQueueView }>()
const siteUrl = ref('')
const testKeyword = ref('')
const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ saved: []; queue: [action: 'next' | 'end', outcome: RepairOutcome]; cancelQueue: [] }>()
const dialog = useDialog()
const models = ref<AiModel[]>([])
const modelId = ref<number | null>(null)
const logs = ref<string[]>([])
const evidence = ref<string[]>([])
const running = ref(false)
const saving = ref(false)
const error = ref('')
const result = ref('')
const diff = ref<AiRepairDiff[]>([])
const saved = ref(false)
const confirmationToken = ref('')
const modelCalled = ref(false)
const message = useMessage()
let controller: AbortController | null = null
let sessionId = 0
type StageStatus = 'pending' | 'running' | 'passed' | 'failed'
interface StageState { status: StageStatus; activity: string; startedAt: number | null; endedAt: number | null; elapsedMs: number }
const stageOrder: AiRepairStage[] = ['search', 'bookInfo', 'toc', 'content']
const statusLabels: Record<StageStatus, string> = {
  pending: t('等待'),
  running: t('正在检查'),
  passed: t('已通过'),
  failed: t('未通过'),
}
function blankStages(): Record<AiRepairStage, StageState> {
  return {
    search: { status: 'pending', activity: '', startedAt: null, endedAt: null, elapsedMs: 0 },
    bookInfo: { status: 'pending', activity: '', startedAt: null, endedAt: null, elapsedMs: 0 },
    toc: { status: 'pending', activity: '', startedAt: null, endedAt: null, elapsedMs: 0 },
    content: { status: 'pending', activity: '', startedAt: null, endedAt: null, elapsedMs: 0 },
  }
}
const stageStates = ref(blankStages())
const now = ref(Date.now())
const failed = ref(false)
const startedAt = ref<number | null>(null)
const activity = ref('')
/** 当前动作开始的时间：长时间没有新事件时，让用户看到正在等哪一步、等了多久 */
const activityAt = ref<number | null>(null)
const waitingModel = ref(false)
const elapsed = computed(() => startedAt.value === null ? 0 : Math.max(0, Math.floor((now.value - startedAt.value) / 1000)))
const activityElapsed = computed(() => activityAt.value === null ? 0 : Math.max(0, Math.floor((now.value - activityAt.value) / 1000)))
function setActivity(text: string, model = false) {
  if (text !== activity.value) activityAt.value = Date.now()
  activity.value = text
  waitingModel.value = model
}
let timer: ReturnType<typeof setInterval> | null = null
const options = computed(() => models.value.map((m) => ({ label: m.name, value: m.id })))
const stages: Record<string, string> = { search: t('搜索'), bookInfo: t('详情'), toc: t('目录'), content: t('正文') }
const logMessages: Record<string, string> = {
  '检查当前书源': t('检查当前书源'), '请求修复规则': t('请求修复规则'), '重新校验规则': t('重新校验规则'),
  '当前阶段仍未通过': t('当前阶段仍未通过'), '当前阶段已通过': t('当前阶段已通过'), '补丁无效，准备重试': t('补丁无效，准备重试'),
}
function stopTimer() {
  if (timer !== null) { clearInterval(timer); timer = null }
}
function ensureTimer() {
  if (timer !== null) return
  now.value = Date.now()
  timer = setInterval(() => { now.value = Date.now() }, 1000)
}
function resetStages() {
  stageStates.value = blankStages()
  startedAt.value = null
  activity.value = ''
  activityAt.value = null
  waitingModel.value = false
  now.value = Date.now()
  stopTimer()
}
function isStage(value: string | null | undefined): value is AiRepairStage {
  return value === 'search' || value === 'bookInfo' || value === 'toc' || value === 'content'
}
function passPriorStages(stage: AiRepairStage) {
  const at = Date.now()
  const index = stageOrder.indexOf(stage)
  for (let i = 0; i < index; i++) {
    const key = stageOrder[i]
    const state = stageStates.value[key]
    if (state.status === 'passed') continue
    stageStates.value[key] = { ...state, status: 'passed', activity: '', startedAt: state.startedAt ?? at, endedAt: state.endedAt ?? at }
  }
}
function startStage(stage: AiRepairStage, activity: string) {
  passPriorStages(stage)
  const at = Date.now()
  for (const key of stageOrder) {
    const other = stageStates.value[key]
    if (key !== stage && other.startedAt !== null && other.endedAt === null) other.endedAt = at
  }
  const state = stageStates.value[stage]
  stageStates.value[stage] = {
    status: 'running',
    activity,
    elapsedMs: state.elapsedMs + (state.startedAt !== null && state.endedAt !== null ? Math.max(0, state.endedAt - state.startedAt) : 0),
    startedAt: state.endedAt === null && state.startedAt !== null ? state.startedAt : at,
    endedAt: null,
  }
  now.value = at
  ensureTimer()
}
function failStage(stage: AiRepairStage) {
  passPriorStages(stage)
  const state = stageStates.value[stage]
  const at = Date.now()
  stageStates.value[stage] = { ...state, status: 'failed', activity: '', startedAt: state.startedAt ?? at, endedAt: at }
  now.value = at
}
function passAllStages() {
  const at = Date.now()
  for (const stage of stageOrder) {
    const state = stageStates.value[stage]
    stageStates.value[stage] = { ...state, status: 'passed', activity: '', startedAt: state.startedAt ?? at, endedAt: state.endedAt ?? at }
  }
  now.value = at
  // 流还没结束时继续计时，结束时由 start() 的 finally 停止
  if (!running.value) stopTimer()
}
function failRunningStages() {
  const at = Date.now()
  for (const stage of stageOrder) {
    const state = stageStates.value[stage]
    if (state.status !== 'running') continue
    stageStates.value[stage] = { ...state, status: 'failed', activity: '', startedAt: state.startedAt ?? at, endedAt: at }
  }
  now.value = at
  if (!running.value) stopTimer()
}
function elapsedSeconds(stage: AiRepairStage): number {
  const state = stageStates.value[stage]
  if (state.startedAt === null) return 0
  return Math.max(0, Math.floor((state.elapsedMs + (state.endedAt ?? now.value) - state.startedAt) / 1000))
}
function stageActivity(stage: AiRepairStage): string {
  const state = stageStates.value[stage]
  return state.activity || statusLabels[state.status]
}
function stop() {
  controller?.abort()
  stopTimer()
}
/** 有还没保存的候选规则（含等待确认 JavaScript 的候选） */
const hasUnsaved = computed(() => !saved.value && (!!confirmationToken.value || (!!result.value && diff.value.length > 0)))
function outcome(): RepairOutcome {
  if (saved.value) return { kind: 'saved' }
  if (running.value) return { kind: 'skipped' }
  if (result.value && !confirmationToken.value && !diff.value.length) return { kind: 'passed' }
  if (result.value) return { kind: 'unsaved' }
  if (failed.value) return { kind: 'failed', error: error.value || t('AI 修复失败，请检查模型设置或重试') }
  return { kind: 'skipped' }
}
function confirmDiscard(): Promise<boolean> {
  if (!hasUnsaved.value) return Promise.resolve(true)
  return new Promise((resolve) => {
    dialog.warning({
      title: t('候选规则未保存'),
      content: t('离开后这个书源的候选规则会被丢弃，书源保持不变。'),
      positiveText: t('丢弃并离开'),
      negativeText: t('留下审查'),
      onPositiveClick: () => resolve(true),
      onNegativeClick: () => resolve(false),
      onClose: () => resolve(false),
      onMaskClick: () => resolve(false),
    })
  })
}
/** 队列：记录当前结果并进入下一个或结束；跳过会先停止当前修复 */
async function leave(action: 'next' | 'end', skip = false) {
  if (!skip && !(await confirmDiscard())) return
  const recorded: RepairOutcome = skip ? { kind: 'skipped' } : outcome()
  stop()
  emit('queue', action, recorded)
}
/** 队列中关闭弹窗（右上角关闭）前，同样确认是否丢弃候选规则 */
function beforeClose() {
  return props.queue ? confirmDiscard() : true
}
// 先于下面的重置逻辑执行：用户直接关闭弹窗时，按关闭前的状态记录结果
watch(show, (open, was) => {
  if (!open && was && props.queue) emit('queue', 'end', outcome())
})
watch([show, () => props.sourceId], async ([open]) => {
  const session = ++sessionId
  controller?.abort()
  controller = null
  running.value = false
  stopTimer()
  if (!open) return
  resetStages()
  failed.value = false
  result.value = ''; diff.value = []; logs.value = []; evidence.value = []; error.value = ''; saved.value = false; confirmationToken.value = ''; testKeyword.value = props.keyword ?? ''; modelCalled.value = false
  try {
    const list = await aiApi.list()
    if (session !== sessionId) return
    models.value = list
    if (!models.value.some((m) => m.id === modelId.value)) modelId.value = models.value[0]?.id ?? null
  } catch (e) { if (session !== sessionId) return; if (e instanceof Error) error.value = e.message; else throw e }
  if (props.queue?.autoStart && props.sourceId !== undefined) void start()
})
onBeforeUnmount(() => { sessionId++; controller?.abort(); stopTimer() })
async function start(confirmed: boolean | MouseEvent = false) {
  const chosen = modelId.value
  if (chosen === null && props.sourceId === undefined) return
  const abort = new AbortController()
  const session = ++sessionId
  controller?.abort()
  const token = confirmed === true ? confirmationToken.value : ''
  confirmationToken.value = ''
  controller = abort
  running.value = true; resetStages(); failed.value = false; result.value = ''; diff.value = []; logs.value = []; evidence.value = []; error.value = ''; saved.value = false; modelCalled.value = false
  startedAt.value = Date.now()
  setActivity(token ? t('正在执行已确认的 JavaScript 并重新校验') : t('正在连接服务器'))
  ensureTimer()
  try {
    const onEvent = (raw: unknown) => {
      if (session !== sessionId) return
      const event = parseAiRepairEvent(raw)
      if (!event) return
      switch (event.type) {
        case 'progress':
          if (event.phase === 'model') modelCalled.value = true
          if (event.phase === 'check') setActivity(t('正在检测：{0}', [stages[event.stage]]))
          else setActivity(t('等待模型回复：{0} 第 {1} 轮', [stages[event.stage], event.round]), true)
          startStage(event.stage, event.phase === 'check' ? t('正在检查') : t('正在修复（第 {0} 轮）', [event.round]))
          break
        case 'evidence': {
          evidence.value.push(JSON.stringify(event.evidence, null, 2))
          const stage = event.evidence.stage
          if (stage === null) passAllStages()
          else if (isStage(stage)) failStage(stage)
          break
        }
        case 'log': if (event.message === '请求修复规则') modelCalled.value = true; logs.value.push([event.stage ? stages[event.stage] : '', event.round ? t('第 {0} 轮', [event.round]) : '', logMessages[event.message] ?? event.message].filter(Boolean).join(' · ')); break
        case 'result': result.value = event.source; diff.value = event.diff; passAllStages(); break
        case 'awaiting-confirmation': result.value = event.source; diff.value = event.diff; confirmationToken.value = event.confirmationToken; break
        case 'detect-failed': {
          const stageName = stages[event.stage] ?? event.stage
          error.value = event.code === 'needs-verification'
            ? t('检测未通过：{0}，网站需要验证，请先完成验证再修复。', [stageName])
            : event.errorType && ['dns', 'tls', 'connection', 'timeout'].includes(event.errorType)
              ? t('检测未通过：{0}，网络问题需要先解决，未调用模型。', [stageName])
              : t('检测未通过：{0}，未配置模型，已跳过修复。', [stageName])
          if (event.error.trim()) error.value += ` ${event.error}`
          failed.value = true
          if (isStage(event.stage)) failStage(event.stage)
          else failRunningStages()
          break
        }
        case 'error': error.value = event.code === 'challenge' ? t('网站需要验证，已停止 AI 修复，请完成验证后重试') : event.message.trim() ? t(event.message) : t('AI 修复失败，请检查模型设置或重试'); failed.value = true; failRunningStages(); break
        case 'end': break
      }
    }
    if (props.sourceId !== undefined) await aiApi.repair({ sourceId: props.sourceId, modelId: chosen ?? 0, keyword: testKeyword.value, ...(token ? { confirmationToken: token } : {}) }, onEvent, abort.signal)
    else if (chosen !== null) await aiApi.create({ url: siteUrl.value, keyword: testKeyword.value, modelId: chosen, ...(token ? { confirmationToken: token } : {}) }, onEvent, abort.signal)
    if (session === sessionId && !result.value && !failed.value) error.value = t('修复未完成')
  } catch (e) {
    if (session !== sessionId) return
    failed.value = true
    if (e instanceof Error) error.value = e.name === 'AbortError' ? t('已停止修复') : e.message
    else throw e
   } finally { if (session === sessionId) { running.value = false; controller = null; activity.value = ''; activityAt.value = null; waitingModel.value = false; now.value = Date.now(); stopTimer() } }
}
async function save() {
  if (confirmationToken.value || running.value || !result.value) return
  saving.value = true
   try { if (props.sourceId !== undefined) await sourceApi.save(props.sourceId, result.value); else await sourceApi.create(result.value); saved.value = true; emit('saved'); message.success(t('已保存')) }
  catch (e) { if (e instanceof Error) error.value = e.message; else throw e }
  finally { saving.value = false }
}
</script>

<template>
  <n-modal v-model:show="show" preset="card" :title="props.sourceId === undefined ? t('AI 创建书源') : t('AI 检测')" style="width: 720px; max-width: 94vw" :mask-closable="!running && !(queue && hasUnsaved)" :close-on-esc="!(queue && hasUnsaved)" :on-close="beforeClose">
    <div class="repair-body">
      <div v-if="queue" class="repair-queue" aria-live="polite">
        <strong>{{ t('修复队列 {0}/{1}：{2}', [queue.position, queue.total, queue.name]) }}</strong>
        <span class="muted small">{{ queue.cancelled ? t('队列已取消，处理完这个书源后结束') : queue.next ? t('下一个：{0}', [queue.next]) : t('这是最后一个') }}</span>
        <span class="muted small">{{ t('候选规则不会自动保存，JavaScript 也不会自动执行。') }}</span>
      </div>
      <p class="muted">{{ props.sourceId === undefined ? t('从真实网页生成规则，四个阶段校验通过后确认保存。') : t('依次校验搜索、详情、目录、正文；只有失败才调用模型修复，全部通过时不会发出模型请求。') }}</p>
      <label>{{ t('选择模型') }}<n-select v-model:value="modelId" :options="options" :disabled="running" :placeholder="t('选择模型')" /></label>
      <template v-if="props.sourceId === undefined">
        <label>{{ t('网站网址') }}<n-input v-model:value="siteUrl" :input-props="{ 'aria-label': t('网站网址') }" :disabled="running" /></label>
        <label>{{ t('测试书名') }}<n-input v-model:value="testKeyword" :input-props="{ 'aria-label': t('测试书名') }" :disabled="running" /></label>
      </template>
      <label v-else>{{ t('测试书名') }}<n-input v-model:value="testKeyword" :placeholder="t('留空则使用书源的校验关键字')" :input-props="{ 'aria-label': t('测试书名') }" :disabled="running" /></label>
      <n-empty v-if="!models.length && props.sourceId === undefined" :description="t('请先在“我的”中添加 AI 模型')" />
      <p v-else-if="!models.length" class="muted small">{{ t('还没有配置 AI 模型：现在只检测、不修复。') }}</p>
      <n-space style="margin-top: 16px">
        <n-button v-if="!running" type="primary" :disabled="modelId === null && props.sourceId === undefined" @click="start">{{ props.sourceId === undefined ? t('开始创建') : t('开始检测') }}</n-button>
        <n-button v-else @click="stop">{{ t('停止') }}</n-button>
      </n-space>
      <div class="repair-stages">
        <div v-for="stage in stageOrder" :key="stage" class="repair-stage" :class="`is-${stageStates[stage].status}`">
          <span class="stage-name">{{ stages[stage] }}</span>
          <span class="stage-text">{{ stageActivity(stage) }}</span>
          <span v-if="stageStates[stage].startedAt !== null" class="stage-elapsed">{{ t('{0} 秒', [elapsedSeconds(stage)]) }}</span>
        </div>
      </div>
      <p v-if="startedAt !== null" aria-live="polite">
        <template v-if="running && activity">{{ t('{0}（{1} 秒）', [activity, activityElapsed]) }} · </template>{{ t('已用时 {0} 秒', [elapsed]) }}
      </p>
      <p v-if="running && waitingModel && activityElapsed >= 20" class="muted small">{{ t('模型生成规则通常需要几十秒到几分钟，仍在等待回复。可以随时停止。') }}</p>
      <ol v-if="logs.length" class="repair-log" aria-live="polite"><li v-for="(log, i) in logs" :key="i">{{ log }}</li></ol>
      <details v-for="(item, index) in evidence" :key="index"><summary>{{ t('第 {0} 轮', [index + 1]) }} · {{ t('详情') }}</summary><pre>{{ item }}</pre></details>
      <n-alert v-if="error" type="error" style="margin-top: 16px">{{ error }}</n-alert>
      <template v-if="confirmationToken">
        <n-alert type="warning" style="margin-top: 16px">{{ t('\u004aavaScript \u5c1a\u672a\u6267\u884c\uff0c\u5019\u9009\u89c4\u5219\u672a\u7ecf\u6821\u9a8c\u3002\u8bf7\u5ba1\u67e5\u4ee3\u7801\u540e\u786e\u8ba4\u6267\u884c\u3002') }}</n-alert>
        <section v-for="item in diff" :key="item.field" class="field-diff">
          <h3>{{ item.field }}</h3><pre>{{ item.new }}</pre>
        </section>
        <n-button :disabled="running" @click="start(true)">{{ t('\u786e\u8ba4\u6267\u884c JavaScript \u5e76\u91cd\u65b0\u6821\u9a8c') }}</n-button>
      </template>
      <n-alert v-if="result && !confirmationToken && !diff.length" type="success" style="margin-top: 16px">{{ modelCalled ? t('所有阶段校验通过。') : t('书源正常：搜索、详情、目录、正文均通过，未调用模型。') }}</n-alert>
      <template v-if="result && !confirmationToken && diff.length">
        <n-alert type="success" style="margin-top: 16px">{{ t('所有阶段校验通过，请检查差异后保存') }}</n-alert>
        <n-alert v-if="diff.some((d) => d.javascript)" type="warning" style="margin-top: 16px">{{ t('警告：AI 生成的规则包含 JavaScript。请审查代码后再保存。') }}</n-alert>
        <section v-for="item in diff" :key="item.field" class="field-diff">
          <h3>{{ item.field }}</h3>
          <div class="muted">{{ t('修改前') }}</div><pre>{{ item.old ?? t('空') }}</pre>
          <div class="muted">{{ t('修改后') }}</div><pre>{{ item.new ?? t('空') }}</pre>
        </section>
        <n-button type="primary" :loading="saving" :disabled="saved || running" style="margin-top: 16px" @click="save">{{ saved ? t('已保存') : t('保存') }}</n-button>
      </template>
      <n-space v-if="queue" class="queue-actions">
        <n-button v-if="running" :disabled="saving" @click="leave(queue.next && !queue.cancelled ? 'next' : 'end', true)">{{ queue.next && !queue.cancelled ? t('跳过，修复下一个') : t('跳过并结束') }}</n-button>
        <n-button v-else-if="queue.next && !queue.cancelled" :type="saved || !hasUnsaved ? 'primary' : 'default'" :disabled="saving" @click="leave('next')">{{ t('下一个') }}</n-button>
        <n-button v-else :disabled="saving" @click="leave('end')">{{ t('结束队列') }}</n-button>
        <n-button v-if="queue.next && !queue.cancelled" quaternary :disabled="saving" @click="emit('cancelQueue')">{{ t('取消剩余 {0} 个', [queue.total - queue.position]) }}</n-button>
      </n-space>
    </div>
  </n-modal>
</template>

<style scoped>
.repair-body { max-height: 75dvh; overflow-y: auto; overflow-wrap: anywhere; }
.repair-stages { display: grid; gap: 8px; margin-top: 16px; }
.repair-stage { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border: 1px solid var(--app-border); border-radius: 8px; }
.repair-stage.is-running { border-color: var(--app-accent); }
.repair-stage.is-passed .stage-text { color: var(--app-accent); }
.repair-stage.is-failed .stage-text { color: #d03050; }
.stage-name { min-width: 48px; font-weight: 600; }
.stage-text { flex: 1; }
.stage-elapsed { color: var(--app-accent); font-variant-numeric: tabular-nums; }
.repair-log { padding-left: 24px; line-height: 1.8; }
.repair-queue { display: grid; gap: 4px; padding-bottom: 12px; margin-bottom: 12px; border-bottom: 1px solid var(--app-border); }
.queue-actions { margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--app-border); }
.small { font-size: 12.5px; }
.field-diff { padding: 16px 0; border-bottom: 1px solid var(--app-border); }
.field-diff h3 { margin: 0 0 12px; font-size: 16px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 6px 0 16px; }
@media (pointer: coarse) { :deep(.n-button), :deep(.n-base-selection) { min-height: 44px; } }
</style>
