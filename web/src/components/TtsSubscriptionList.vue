<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NAlert, NButton, NEmpty, NForm, NFormItem, NInput, NModal, NSpace, NSwitch, NTag, useDialog, useMessage } from 'naive-ui'
import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { t } from '@/i18n'

interface Subscription { id: number; name: string; url: string; autoUpdate: boolean; lastResult: string | null; lastUpdateAt: number; owned: boolean }
const emit = defineEmits<{ changed: [] }>()
const props = withDefaults(defineProps<{ kind?: 'tts' | 'replace' | 'dict' | 'txtToc' }>(), { kind: 'tts' })
const rows = ref<Subscription[]>([])
const auth = useAuthStore()
const loading = ref(false)
const loadError = ref('')
const show = ref(false)
const busy = ref(false)
const refreshing = ref(new Set<number>())
const editing = ref<number | null>(null)
const form = ref({ name: '', url: '', autoUpdate: true })
const message = useMessage()
const dialog = useDialog()
const base = props.kind === 'tts' ? '/api/tts/subscriptions' : `/api/rule-subscriptions/${props.kind}`
async function load(): Promise<boolean> {
  loading.value = true
  loadError.value = ''
  try { rows.value = await http.get<Subscription[]>(base); return true }
  catch (e: unknown) { loadError.value = e instanceof Error ? e.message : String(e); return false }
  finally { loading.value = false }
}
onMounted(load)
function edit(row?: Subscription) {
  editing.value = row?.id ?? null
  form.value = { name: row?.name ?? '', url: row?.url ?? '', autoUpdate: row?.autoUpdate ?? true }
  show.value = true
}
async function save() {
  busy.value = true
  try {
    if (editing.value === null) await http.post(base, form.value)
    else await http.put(`${base}/${editing.value}`, form.value)
    show.value = false
    await load()
    emit('changed')
  } catch (e: unknown) { message.error(e instanceof Error ? e.message : String(e)) }
  finally { busy.value = false }
}
async function refresh(row: Subscription) {
  if (refreshing.value.has(row.id)) return
  refreshing.value.add(row.id)
  let succeeded = false
  try {
    try { await http.post(`${base}/${row.id}/refresh`); succeeded = true; emit('changed') }
    catch (e: unknown) { message.error(e instanceof Error ? e.message : String(e)) }
    const reloaded = await load()
    if (succeeded && !reloaded) message.warning(t('刷新成功，但重新加载订阅失败'))
  } finally { refreshing.value.delete(row.id) }
}
function remove(row: Subscription) {
   dialog.warning({ title: t('删除订阅'), content: t('删除订阅不会删除已导入的数据。'), positiveText: t('删除'), negativeText: t('取消'), onPositiveClick: async () => { await http.del(`${base}/${row.id}`); await load() } })
}
</script>

<template>
  <h3 v-if="props.kind === 'tts'">{{ t('朗读引擎订阅') }}</h3>
  <p class="muted small">{{ props.kind === 'tts' ? t('订阅阅读 App 格式的 HttpTTS JSON；按名称更新，不删除已有引擎，自动更新约每天一次。') : t('按当前规则类型订阅 JSON，自动更新约每天一次，删除订阅保留已导入规则。') }}</p>
  <n-button size="small" :disabled="props.kind === 'tts' && !auth.me?.canAddSource" @click="edit()">{{ t('添加订阅') }}</n-button>
  <p v-if="props.kind === 'tts' && !auth.me?.canAddSource" class="muted small">{{ t('需要管理员授予添加书源权限') }}</p>
  <p v-if="loading" class="muted small" role="status">{{ t('正在加载订阅…') }}</p>
  <n-alert v-else-if="loadError" type="error" :bordered="false">
    {{ t('加载订阅失败：{0}', [loadError]) }}
    <n-button size="small" @click="load">{{ t('刷新') }}</n-button>
  </n-alert>
  <n-empty v-else-if="!rows.length" :description="t('暂无订阅，请添加订阅链接。')" />
  <div v-for="row in rows" :key="row.id" style="margin-top: 12px">
    <div>{{ row.name }} <n-tag v-if="row.autoUpdate" size="tiny" :bordered="false">{{ t('自动更新') }}</n-tag></div>
    <div class="muted small" style="overflow-wrap: anywhere">{{ row.url }}</div>
    <div v-if="row.lastResult" class="muted small">{{ row.lastResult }}</div>
    <n-space v-if="row.owned" style="margin-top: 6px">
      <n-button size="small" :loading="refreshing.has(row.id)" @click="refresh(row)">{{ t('刷新') }}</n-button>
      <n-button size="small" @click="edit(row)">{{ t('编辑') }}</n-button>
      <n-button size="small" @click="remove(row)">{{ t('删除') }}</n-button>
    </n-space>
  </div>
  <n-modal v-model:show="show" preset="card" :title="editing === null ? t('添加订阅') : t('编辑订阅')" style="width: 520px; max-width: 94vw" :auto-focus="false">
    <n-form label-placement="top" @submit.prevent="save">
      <n-form-item :label="t('名称')"><n-input v-model:value="form.name" :input-props="{ 'aria-label': t('名称') }" /></n-form-item>
      <n-form-item :label="t('订阅链接')" required><n-input v-model:value="form.url" placeholder="https://example.com/subscription.json" :input-props="{ 'aria-label': t('订阅链接') }" /></n-form-item>
      <n-form-item :label="t('自动更新')"><n-switch v-model:value="form.autoUpdate" :aria-label="t('自动更新')" /></n-form-item>
      <n-button type="primary" :loading="busy" :disabled="!form.url.trim()" attr-type="submit">{{ t('保存') }}</n-button>
    </n-form>
  </n-modal>
</template>

<style scoped>
@media (pointer: coarse) {
  :deep(.n-button) { min-height: 44px; min-width: 44px; }
}
</style>
