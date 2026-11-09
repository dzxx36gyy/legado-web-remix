<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { NAlert, NButton, NEmpty, NInput, NModal, NSelect, NSpace, useMessage } from 'naive-ui'
import { aiApi, sourceApi, type AiModel, type AiRepairDiff } from '@/api'
import { t } from '@/i18n'

const props = defineProps<{ sourceId?: number; keyword?: string }>()
const siteUrl = ref('')
const testKeyword = ref('')
const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ saved: [] }>()
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
const options = computed(() => models.value.map((m) => ({ label: m.name, value: m.id })))
const stages: Record<string, string> = { search: t('搜索'), bookInfo: t('详情'), toc: t('目录'), content: t('正文') }
const logMessages: Record<string, string> = {
  '检查当前书源': t('检查当前书源'), '请求修复规则': t('请求修复规则'), '重新校验规则': t('重新校验规则'),
  '当前阶段仍未通过': t('当前阶段仍未通过'), '当前阶段已通过': t('当前阶段已通过'), '补丁无效，准备重试': t('补丁无效，准备重试'),
}
watch(show, async (open) => {
  if (!open) { controller?.abort(); return }
  result.value = ''; diff.value = []; logs.value = []; evidence.value = []; error.value = ''; saved.value = false; confirmationToken.value = ''; testKeyword.value = props.keyword ?? ''; modelCalled.value = false
  try {
    models.value = await aiApi.list()
    if (!models.value.some((m) => m.id === modelId.value)) modelId.value = models.value[0]?.id ?? null
  } catch (e) { if (e instanceof Error) error.value = e.message; else throw e }
})
onBeforeUnmount(() => controller?.abort())
async function start(confirmed: boolean | MouseEvent = false) {
  const chosen = modelId.value
  if (chosen === null && props.sourceId === undefined) return
  const abort = new AbortController()
  const token = confirmed === true ? confirmationToken.value : ''
  confirmationToken.value = ''
  controller = abort
  running.value = true; result.value = ''; diff.value = []; logs.value = []; evidence.value = []; error.value = ''; saved.value = false; modelCalled.value = false
  try {
    const onEvent = (event: import('@/api').AiRepairEvent) => {
      switch (event.type) {
        case 'evidence': evidence.value.push(JSON.stringify(event.evidence, null, 2)); break
        case 'log': if (event.message === '请求修复规则') modelCalled.value = true; logs.value.push([event.stage ? stages[event.stage] : '', event.round ? t('第 {0} 轮', [event.round]) : '', logMessages[event.message] ?? event.message].filter(Boolean).join(' · ')); break
        case 'result': result.value = event.source; diff.value = event.diff; break
        case 'awaiting-confirmation': result.value = event.source; diff.value = event.diff; confirmationToken.value = event.confirmationToken; break
        case 'detect-failed': error.value = t('检测未通过：{0}，未配置模型，已跳过修复。', [stages[event.stage] ?? event.stage]); break
        case 'error': error.value = event.code === 'challenge' ? t('网站需要验证，已停止 AI 修复，请完成验证后重试') : t('AI 修复失败，请检查模型设置或重试'); break
        case 'end': break
      }
    }
    if (props.sourceId !== undefined) await aiApi.repair({ sourceId: props.sourceId, modelId: chosen ?? 0, keyword: testKeyword.value, ...(token ? { confirmationToken: token } : {}) }, onEvent, abort.signal)
    else if (chosen !== null) await aiApi.create({ url: siteUrl.value, keyword: testKeyword.value, modelId: chosen, ...(token ? { confirmationToken: token } : {}) }, onEvent, abort.signal)
    if (!result.value && !error.value) error.value = t('修复未完成')
  } catch (e) {
    if (e instanceof Error) error.value = e.name === 'AbortError' ? t('已停止修复') : e.message
    else throw e
  } finally { running.value = false; controller = null }
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
  <n-modal v-model:show="show" preset="card" :title="props.sourceId === undefined ? t('AI 创建书源') : t('AI 检测')" style="width: 720px; max-width: 94vw" :mask-closable="!running">
    <div class="repair-body">
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
        <n-button v-else @click="controller?.abort()">{{ t('停止') }}</n-button>
      </n-space>
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
    </div>
  </n-modal>
</template>

<style scoped>
.repair-body { max-height: 75dvh; overflow-y: auto; overflow-wrap: anywhere; }
.repair-log { padding-left: 24px; line-height: 1.8; }
.field-diff { padding: 16px 0; border-bottom: 1px solid var(--app-border); }
.field-diff h3 { margin: 0 0 12px; font-size: 16px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 6px 0 16px; }
@media (pointer: coarse) { :deep(.n-button), :deep(.n-base-selection) { min-height: 44px; } }
</style>
