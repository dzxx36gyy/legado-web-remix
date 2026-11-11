<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { NButton, NEmpty, NForm, NFormItem, NInput, NInputNumber, NModal, NSelect, NSpace, useDialog, useMessage } from 'naive-ui'
import { aiApi, type AiModel, type AiModelInput } from '@/api'
import { t } from '@/i18n'

const models = ref<AiModel[]>([])
const loading = ref(false)
const show = ref(false)
const editing = ref<number | null>(null)
const saving = ref(false)
const testing = ref(new Set<number>())
const message = useMessage()
const dialog = useDialog()
const blank = (): AiModelInput => ({ name: '', baseUrl: '', model: '', apiKey: '', temperature: null, maxTokens: null })
const fetchedModels = ref<string[]>([])
const fetchingModels = ref(false)
const fetchError = ref('')
let fetchRequest = 0
const form = ref(blank())
const providers = [
  { value: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com', model: 'deepseek-flash' },
  { value: 'openrouter', label: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1', model: '' },
  { value: 'siliconflow', label: '硅基流动', baseUrl: 'https://api.siliconflow.cn/v1', model: 'deepseek-ai/DeepSeek-V4-Flash' },
  { value: 'kilo', label: 'Kilo Gateway', baseUrl: 'https://ai-gateway.kilo.ai/api/v1', model: '' },
]
const modelOptions = computed(() => {
  const values = [providers.find((item) => item.value === provider.value)?.model, ...fetchedModels.value]
  if (form.value.model) values.push(form.value.model)
  return [...new Set(values.filter(Boolean))].map((value) => ({ label: value, value }))
})
const provider = ref('custom')
function selectProvider(value: string) {
  provider.value = value
  clearFetchedModels()
  const preset = providers.find((item) => item.value === value)
  if (!preset) return
  form.value.baseUrl = preset.baseUrl
  form.value.model = preset.model
  if (!form.value.name) form.value.name = preset.label
}
function invalidateFetch() {
  fetchRequest++
  fetchingModels.value = false
}
function clearFetchedModels() {
  invalidateFetch()
  fetchedModels.value = []
  fetchError.value = ''
}
async function fetchModels() {
  const baseUrl = form.value.baseUrl.trim()
  if (!baseUrl) { fetchError.value = t('请先填写 API 基础地址'); return }
  const apiKey = form.value.apiKey.trim()
  const id = editing.value
  const request = ++fetchRequest
  fetchingModels.value = true
  fetchError.value = ''
  try {
    const list = await aiApi.fetchModels({ baseUrl, ...(apiKey ? { apiKey } : {}), ...(id !== null ? { id } : {}) })
    if (request !== fetchRequest || !show.value || form.value.baseUrl.trim() !== baseUrl || form.value.apiKey.trim() !== apiKey || editing.value !== id) return
    fetchedModels.value = list
    if (!list.length) { fetchError.value = t('未获取到模型，请手动填写'); message.warning(fetchError.value) }
  } catch (e) {
    if (request !== fetchRequest) return
    fetchError.value = e instanceof Error ? e.message : t('获取模型列表失败')
    message.error(fetchError.value)
  } finally {
    if (request === fetchRequest) fetchingModels.value = false
  }
}
watch(() => form.value.baseUrl, clearFetchedModels)
watch([() => form.value.apiKey, editing], invalidateFetch)
watch(show, (open) => { if (open) invalidateFetch(); else clearFetchedModels() })

async function load() {
  loading.value = true
  try { models.value = await aiApi.list() }
  catch (e) { if (e instanceof Error) message.error(e.message); else throw e }
  finally { loading.value = false }
}
onMounted(load)

function edit(model?: AiModel) {
  clearFetchedModels()
  editing.value = model?.id ?? null
  form.value = model ? { name: model.name, baseUrl: model.baseUrl, model: model.model, apiKey: '', temperature: model.temperature, maxTokens: model.maxTokens } : blank()
  provider.value = model ? providers.find((item) => item.baseUrl === model.baseUrl.replace(/\/$/, ''))?.value ?? 'custom' : 'deepseek'
  if (!model) selectProvider('deepseek')
  show.value = true
}
async function save() {
  saving.value = true
  try {
    await aiApi.save(editing.value, form.value)
    form.value.apiKey = ''
    show.value = false
    await load()
  } catch (e) { if (e instanceof Error) message.error(e.message); else throw e }
  finally { saving.value = false }
}
async function test(id: number) {
  if (testing.value.has(id)) return
  testing.value.add(id)
  try { await aiApi.test(id); message.success(t('连接成功')) }
  catch (e) { if (e instanceof Error) message.error(e.message); else throw e }
  finally { testing.value.delete(id) }
}
function remove(model: AiModel) {
  dialog.warning({
    title: t('删除 AI 模型'), content: t('确定删除「{0}」？', [model.name]),
    positiveText: t('删除'), negativeText: t('取消'),
    onPositiveClick: async () => {
      try { await aiApi.remove(model.id); await load() }
      catch (e) { if (e instanceof Error) message.error(e.message); else throw e }
    },
  })
}
</script>

<template>
  <p class="muted">{{ t('模型和密钥仅自己可用。请求由服务器发送，密钥保存后不再返回。') }}</p>
  <n-space><n-button type="primary" @click="edit()">{{ t('添加模型') }}</n-button><n-button :loading="loading" @click="load">{{ t('刷新') }}</n-button></n-space>
  <n-empty v-if="!loading && !models.length" :description="t('尚未添加 AI 模型')" style="padding: 20px 0" />
  <div v-for="model in models" :key="model.id" class="model-row">
    <div class="model-info"><strong>{{ model.name }}</strong><div class="muted">{{ model.model }} · {{ model.baseUrl }}</div></div>
    <n-space :size="8" wrap>
      <n-button :loading="testing.has(model.id)" @click="test(model.id)">{{ t('测试连接') }}</n-button>
      <n-button @click="edit(model)">{{ t('编辑') }}</n-button>
      <n-button type="error" secondary @click="remove(model)">{{ t('删除') }}</n-button>
    </n-space>
  </div>
  <n-modal v-model:show="show" preset="card" :title="editing === null ? t('添加模型') : t('编辑模型')" style="width: 520px; max-width: 94vw" :mask-closable="!saving" @after-leave="form.apiKey = ''">
    <n-form label-placement="top" @submit.prevent="save">
      <n-form-item :label="t('服务商')"><n-select :value="provider" :options="[...providers.map((item) => ({ value: item.value, label: item.value === 'siliconflow' ? t('硅基流动') : item.label })), { value: 'custom', label: t('自定义（OpenAI 兼容）') }]" :aria-label="t('服务商')" @update:value="selectProvider" /><template #feedback>{{ t('预设填写官方地址；模型标识可从服务商控制台复制，地址也可自行修改。') }}</template></n-form-item>
      <n-form-item :label="t('名称')"><n-input v-model:value="form.name" :input-props="{ 'aria-label': t('名称') }" /></n-form-item>
      <n-form-item :label="t('API 基础地址')"><n-input v-model:value="form.baseUrl" :input-props="{ 'aria-label': t('API 基础地址') }" /><template #feedback>{{ t('填写兼容 OpenAI 的基础地址，例如 https://api.openai.com/v1') }}</template></n-form-item>
      <n-form-item :label="t('模型标识')">
        <n-select v-model:value="form.model" :options="modelOptions" filterable tag :placeholder="t('选择或输入模型标识')" :aria-label="t('模型标识')" />
        <template #feedback>
          <div class="model-fetch">
            <n-button size="small" :loading="fetchingModels" :disabled="fetchingModels" @click="fetchModels">{{ t('获取模型列表') }}</n-button>
            <span v-if="fetchError" class="fetch-error">{{ fetchError }}</span>
            <span v-else class="muted">{{ t('可从服务商获取模型列表，也可以手动输入。') }}</span>
          </div>
        </template>
      </n-form-item>
      <n-form-item :label="t('API 密钥')"><n-input v-model:value="form.apiKey" type="password" show-password-on="click" :input-props="{ 'aria-label': t('API 密钥'), autocomplete: 'new-password' }" /><template v-if="editing !== null" #feedback>{{ t('已保存密钥；留空保留原值') }}</template></n-form-item>
      <n-form-item :label="t('温度（可选）')"><n-input-number v-model:value="form.temperature" :min="0" :max="2" :step="0.1" /></n-form-item>
      <n-form-item :label="t('最大令牌数（可选）')"><n-input-number v-model:value="form.maxTokens" :min="1" :max="131072" :precision="0" /></n-form-item>
      <n-space justify="end"><n-button :disabled="saving" @click="show = false">{{ t('取消') }}</n-button><n-button type="primary" :loading="saving" attr-type="submit">{{ t('保存') }}</n-button></n-space>
    </n-form>
  </n-modal>
</template>

<style scoped>
.model-row { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; padding: 16px 0; border-bottom: 1px solid var(--app-border); }
.model-info { flex: 1 1 220px; min-width: 0; overflow-wrap: anywhere; }
.model-fetch { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.fetch-error { color: #d03050; }
@media (pointer: coarse) { :deep(.n-button) { min-height: 44px; } }
</style>
