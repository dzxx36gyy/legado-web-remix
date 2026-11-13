<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { NAlert, NButton, NForm, NFormItem, NInput, NSelect, NSpace, useMessage } from 'naive-ui'
import NestedPane from './NestedPane.vue'
import { ttsApi } from '@/api'
import type { TtsProviderEngine, TtsProviderInfo, TtsVoiceOption } from '@/api/types'
import { t } from '@/i18n'

const props = defineProps<{
  providers: TtsProviderInfo[]
  /** 正在编辑的引擎；新建时为空 */
  engineId?: number
  initial: TtsProviderEngine
  play: (audio: Blob) => Promise<void>
}>()
const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ saved: [] }>()
const message = useMessage()

const PAGE_SIZE = 50
const REGION_EXAMPLES: Record<string, string> = { tencent: 'ap-guangzhou', azure: 'eastus' }

const pform = ref<TtsProviderEngine>({ ...props.initial })
const testText = ref('')
const saving = ref(false)
const testing = ref(false)

const voices = ref<TtsVoiceOption[] | null>(null)
const voicePage = ref(1)
const voiceTotal = ref<number | null>(null)
const voiceSearch = ref('')
const voicesLoading = ref(false)
const voicesError = ref('')
let voiceRequest = 0
let searchTimer: ReturnType<typeof setTimeout> | undefined

const cur = computed(() => props.providers.find((p) => p.id === pform.value.provider))
const hasKey = computed(() => !!pform.value.apiKey.trim() || !!pform.value.hasApiKey)
/** 没有内置音色列表的服务商（Azure、Fish Audio）要用 API 密钥从服务商获取 */
const remoteVoices = computed(() => (cur.value?.voices.length ?? 0) <= 1)
const hasNextPage = computed(() => {
  const list = voices.value
  if (!list) return false
  return voiceTotal.value === null ? list.length === PAGE_SIZE : voicePage.value * PAGE_SIZE < voiceTotal.value
})
const pageCount = computed(() => (voiceTotal.value === null ? null : Math.max(1, Math.ceil(voiceTotal.value / PAGE_SIZE))))

const voiceLabel = (v: TtsVoiceOption) => {
  const name = v.id === '' ? t('默认音色') : v.name
  const extra = [v.locale, v.gender].filter(Boolean).join(' · ')
  return `${name}（${v.id || '-'}${extra ? ` · ${extra}` : ''}）`
}

/** 当前音色不在列表里时也要显示，避免切页或搜索后丢失已选的音色 */
const voiceOptions = computed(() => {
  const list = voices.value ?? cur.value?.voices ?? []
  const options = list.map((v) => ({ label: voiceLabel(v), value: v.id }))
  const voice = pform.value.voice
  if (!options.some((o) => o.value === voice)) {
    const known = cur.value?.voices.find((v) => v.id === voice)
    options.unshift({ label: known ? voiceLabel(known) : voice || t('默认音色'), value: voice })
  }
  return options
})

function resetVoices() {
  voiceRequest++
  clearTimeout(searchTimer)
  voices.value = null
  voicePage.value = 1
  voiceTotal.value = null
  voiceSearch.value = ''
  voicesLoading.value = false
  voicesError.value = ''
}

async function loadVoices(page = voicePage.value) {
  const seq = ++voiceRequest
  voicesLoading.value = true
  voicesError.value = ''
  try {
    const r = await ttsApi.providerVoices({
      id: props.engineId, engine: pform.value, search: voiceSearch.value.trim(), page, pageSize: PAGE_SIZE,
    })
    if (seq !== voiceRequest) return
    voices.value = r.voices
    voicePage.value = r.page
    voiceTotal.value = r.total ?? null
  } catch (e: any) {
    if (seq !== voiceRequest) return
    voicesError.value = t('获取音色失败：{0}', [e.message])
  } finally {
    if (seq === voiceRequest) voicesLoading.value = false
  }
}

function onVoiceSearch(query: string) {
  if (query.trim() === voiceSearch.value.trim()) return
  voiceSearch.value = query
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => loadVoices(1), 350)
}
onBeforeUnmount(() => clearTimeout(searchTimer))

watch(show, (open) => {
  if (!open) return
  pform.value = { ...props.initial }
  const p = cur.value
  if (p && !pform.value.model) pform.value.model = p.defaultModel
  testText.value = t('这是一段朗读测试。')
  resetVoices()
  if (remoteVoices.value && hasKey.value) loadVoices(1)
}, { immediate: true })

function onProviderChange(id: string) {
  const old = cur.value
  const p = props.providers.find((x) => x.id === id)
  if (!p) return
  if (!pform.value.name || pform.value.name === old?.label) pform.value.name = p.label
  pform.value = { ...pform.value, provider: id, voice: p.defaultVoice, model: p.defaultModel, region: '', secretId: '' }
  resetVoices()
}

async function save() {
  saving.value = true
  try {
    await ttsApi.saveProvider(props.engineId, pform.value)
    show.value = false
    message.success(t('已保存'))
    emit('saved')
  } catch (e: any) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function test() {
  testing.value = true
  try {
    await props.play(await ttsApi.testProvider(props.engineId, pform.value, testText.value || t('这是一段朗读测试。')))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <nested-pane v-model:show="show" :title="engineId ? t('编辑朗读引擎') : t('添加云服务商')">
    <p class="muted small" style="margin-top: 0">
      {{ t('由本服务器直接调用服务商的语音合成接口，API 密钥加密保存，只有你自己可以使用。') }}
    </p>
    <n-form label-placement="top" class="tts-form">
      <n-form-item :label="t('服务商')" required>
        <n-select
          :value="pform.provider"
          :options="providers.map((p) => ({ label: p.label, value: p.id }))"
          @update:value="onProviderChange"
        />
      </n-form-item>
      <n-form-item :label="t('名称')" required>
        <n-input v-model:value="pform.name" />
      </n-form-item>
      <n-form-item v-if="cur?.needsSecretId" label="SecretId" required>
        <n-input v-model:value="pform.secretId" class="mono" />
      </n-form-item>
      <n-form-item :label="t('API 密钥')" :required="!pform.hasApiKey">
        <n-input v-model:value="pform.apiKey" type="password" show-password-on="click" class="mono" />
        <template #feedback>
          <span v-if="cur?.needsSecretId">{{ t('请填写服务商的 SecretKey。') }} </span>
          <span v-if="pform.hasApiKey">{{ t('已保存，留空表示不修改') }}</span>
        </template>
      </n-form-item>
      <n-form-item v-if="cur?.needsRegion" :label="t('地域')" :required="pform.provider === 'azure'">
        <n-input v-model:value="pform.region" class="mono" :placeholder="REGION_EXAMPLES[pform.provider] ?? ''" />
        <template #feedback>
          {{ t('例如 {0}', [REGION_EXAMPLES[pform.provider] ?? '']) }}
        </template>
      </n-form-item>
      <n-form-item v-if="(cur?.models.length ?? 0) > 1" :label="t('模型')" required>
        <n-select v-model:value="pform.model" :options="(cur?.models ?? []).map((m) => ({ label: m, value: m }))" />
      </n-form-item>
      <n-form-item :label="t('音色')" required>
        <n-select
          v-model:value="pform.voice"
          filterable
          tag
          remote
          :loading="voicesLoading"
          :options="voiceOptions"
          :placeholder="t('搜索或输入音色 ID')"
          @search="onVoiceSearch"
        />
        <template #feedback>
          <span v-if="voices">
            {{ pageCount ? t('第 {0} / {1} 页', [voicePage, pageCount]) : t('第 {0} 页', [voicePage]) }}<template v-if="voiceTotal !== null"> · {{ t('共 {0} 个音色', [voiceTotal]) }}</template>
          </span>
          <span v-else-if="remoteVoices && !hasKey">{{ t('填写 API 密钥后可从服务商获取音色列表') }}</span>
        </template>
      </n-form-item>
      <div class="voice-tools">
        <n-button size="small" :loading="voicesLoading" @click="loadVoices(voices ? voicePage : 1)">{{ t('刷新音色') }}</n-button>
        <n-button size="small" :disabled="!voices || voicePage <= 1 || voicesLoading" @click="loadVoices(voicePage - 1)">{{ t('上一页') }}</n-button>
        <n-button size="small" :disabled="!hasNextPage || voicesLoading" @click="loadVoices(voicePage + 1)">{{ t('下一页') }}</n-button>
      </div>
      <n-alert v-if="voicesError" type="error" :bordered="false" class="voice-error">{{ voicesError }}</n-alert>
      <div class="test-row">
        <n-input v-model:value="testText" size="small" :placeholder="t('试听的文字')" />
        <n-button size="small" :loading="testing" @click="test">{{ t('试听') }}</n-button>
      </div>
    </n-form>
    <template #footer>
      <n-space justify="end">
        <n-button size="small" @click="show = false">{{ t('取消') }}</n-button>
        <n-button size="small" type="primary" :loading="saving" @click="save">{{ t('保存') }}</n-button>
      </n-space>
    </template>
  </nested-pane>
</template>

<style scoped>
.voice-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
}

.voice-error {
  margin-top: 12px;
}

.test-row {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

.small {
  font-size: 12.5px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

@media (max-width: 767px) {
  :deep(.n-button) { min-height: 44px; min-width: 44px; }
}
</style>
