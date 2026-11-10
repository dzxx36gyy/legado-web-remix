<script setup lang="ts">
import { onMounted, ref } from 'vue'
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
const form = ref(blank())
const providers = [
  { value: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com', model: 'deepseek-flash' },
  { value: 'openrouter', label: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1', model: '' },
  { value: 'siliconflow', label: '硅基流动', baseUrl: 'https://api.siliconflow.cn/v1', model: 'deepseek-ai/DeepSeek-V4-Flash' },
  { value: 'kilo', label: 'Kilo Gateway', baseUrl: 'https://ai-gateway.kilo.ai/api/v1', model: '' },
]
const provider = ref('custom')
function selectProvider(value: string) {
  provider.value = value
  const preset = providers.find((item) => item.value === value)
  if (!preset) return
  form.value.baseUrl = preset.baseUrl
  form.value.model = preset.model
  if (!form.value.name) form.value.name = preset.label
}

async function load() {
  loading.value = true
  try { models.value = await aiApi.list() }
  catch (e) { if (e instanceof Error) message.error(e.message); else throw e }
  finally { loading.value = false }
}
onMounted(load)

function edit(model?: AiModel) {
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
      <n-form-item :label="t('API 提供商')"><n-select :value="provider" :options="[...providers.map((item) => ({ value: item.value, label: item.value === 'siliconflow' ? t('硅基流动') : item.label })), { value: 'custom', label: t('自定义（OpenAI 兼容）') }]" :aria-label="t('API 提供商')" @update:value="selectProvider" /><template #feedback>{{ t('预设填写官方地址；模型标识可从提供商控制台复制，地址也可自行修改。') }}</template></n-form-item>
      <n-form-item :label="t('名称')"><n-input v-model:value="form.name" :input-props="{ 'aria-label': t('名称') }" /></n-form-item>
      <n-form-item :label="t('API 基础地址')"><n-input v-model:value="form.baseUrl" :input-props="{ 'aria-label': t('API 基础地址') }" /><template #feedback>{{ t('填写兼容 OpenAI 的基础地址，例如 https://api.openai.com/v1') }}</template></n-form-item>
      <n-form-item :label="t('模型标识')"><n-input v-model:value="form.model" :input-props="{ 'aria-label': t('模型标识') }" /></n-form-item>
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
@media (pointer: coarse) { :deep(.n-button) { min-height: 44px; } }
</style>
