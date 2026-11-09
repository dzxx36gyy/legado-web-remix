<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NButton, NForm, NFormItem, NInput, NModal, NSpace, NSwitch, useDialog, useMessage } from 'naive-ui'
import { http } from '@/api/http'
import { t } from '@/i18n'

interface Subscription { id: number; name: string; url: string; autoUpdate: boolean; lastResult: string | null; lastUpdateAt: number; owned: boolean }
const emit = defineEmits<{ changed: [] }>()
const props = withDefaults(defineProps<{ kind?: 'tts' | 'replace' | 'dict' | 'txtToc' }>(), { kind: 'tts' })
const rows = ref<Subscription[]>([])
const show = ref(false)
const busy = ref(false)
const refreshing = ref<number | null>(null)
const editing = ref<number | null>(null)
const form = ref({ name: '', url: '', autoUpdate: true })
const message = useMessage()
const dialog = useDialog()
const base = props.kind === 'tts' ? '/api/tts/subscriptions' : `/api/rule-subscriptions/${props.kind}`
async function load() { rows.value = await http.get<Subscription[]>(base) }
onMounted(() => load().catch((e: unknown) => message.error(e instanceof Error ? e.message : String(e))))
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
  refreshing.value = row.id
  try { await http.post(`${base}/${row.id}/refresh`); emit('changed') }
  catch (e: unknown) { message.error(e instanceof Error ? e.message : String(e)) }
  finally { refreshing.value = null; await load() }
}
function remove(row: Subscription) {
   dialog.warning({ title: t('删除订阅'), content: t('删除订阅不会删除已导入的数据。'), positiveText: t('删除'), negativeText: t('取消'), onPositiveClick: async () => { await http.del(`${base}/${row.id}`); await load() } })
}
</script>

<template>
  <p class="muted small">{{ props.kind === 'tts' ? t('订阅阅读 App 格式的 HttpTTS JSON；按名称更新，不删除已有引擎，自动更新约每天一次。') : t('按当前规则类型订阅 JSON，自动更新约每天一次，删除订阅保留已导入规则。') }}</p>
  <n-button size="small" @click="edit()">{{ t('添加订阅') }}</n-button>
  <div v-for="row in rows" :key="row.id" style="margin-top: 12px">
    <div>{{ row.name }}</div>
    <div class="muted small" style="overflow-wrap: anywhere">{{ row.url }}</div>
    <div v-if="row.lastResult" class="muted small">{{ row.lastResult }}</div>
    <n-space v-if="row.owned" style="margin-top: 6px">
      <n-button size="small" :loading="refreshing === row.id" @click="refresh(row)">{{ t('刷新') }}</n-button>
      <n-button size="small" @click="edit(row)">{{ t('编辑') }}</n-button>
      <n-button size="small" @click="remove(row)">{{ t('删除') }}</n-button>
    </n-space>
  </div>
  <n-modal v-model:show="show" preset="card" :title="editing === null ? t('添加订阅') : t('编辑订阅')" style="width: 520px; max-width: 94vw" :auto-focus="false">
    <n-form label-placement="top" @submit.prevent="save">
      <n-form-item :label="t('名称')"><n-input v-model:value="form.name" :input-props="{ 'aria-label': t('名称') }" /></n-form-item>
      <n-form-item :label="t('订阅链接')" required><n-input v-model:value="form.url" :input-props="{ 'aria-label': t('订阅链接') }" /></n-form-item>
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
