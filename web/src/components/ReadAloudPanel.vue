<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { NButton, NIcon, NSelect, NSlider, NDropdown, NSwitch } from 'naive-ui'
import { PlayCircle, PauseCircle, PlaySkipBack, PlaySkipForward, Close, OptionsOutline, TimerOutline } from '@vicons/ionicons5'
import type { TtsEngine, TtsVoiceOption } from '@/api/types'
import { t } from '@/i18n'

const props = defineProps<{
  state: 'playing' | 'paused' | 'loading'
  engine: string
  rate: number
  continuous: boolean
  voice: string
  edgeVoice: string
  engines: TtsEngine[]
  voices: SpeechSynthesisVoice[]
  edgeVoices: TtsVoiceOption[]
  browserSupported: boolean
  /** 当前读到的文字（显示在面板上） */
  text: string
  error: string
  bg: string
  fg: string
}>()

const emit = defineEmits<{
  toggle: []
  stop: []
  prev: []
  next: []
  'update:engine': [string]
  'update:rate': [number]
  'update:continuous': [boolean]
  'update:voice': [string]
  'update:edgeVoice': [string]
  /** 打开朗读引擎管理（新建、编辑、导入在线引擎） */
  manageEngines: []
}>()

const showOptions = ref(false)

const engineOptions = computed(() => [
  ...(props.browserSupported ? [{ label: t('浏览器语音'), value: 'browser' }] : []),
  { label: t('Edge 朗读'), value: 'edge' },
  ...props.engines.map((e) => ({ label: e.owned ? e.name : t('{0}（公共）', [e.name]), value: `http:${e.id}` })),
])

const voiceOptions = computed(() => [
  { label: t('自动（中文优先）'), value: '' },
  ...props.voices.map((v) => ({ label: `${v.name}${v.localService ? '' : t('（在线）')}`,     value: v.name })),
])

/** 不支持浏览器语音时实际用的是 Edge 朗读 */
const engineValue = computed(() => (props.engine === 'browser' && !props.browserSupported ? 'edge' : props.engine))

const edgeVoiceOptions = computed(() => [
  { label: t('默认（晓晓）'), value: '' },
  ...props.edgeVoices.map((v) => ({ label: `${v.name} · ${v.locale}`, value: v.id })),
])

// ---------- 定时停止 ----------
const sleepLeft = ref(0)
let sleepTimer: ReturnType<typeof setInterval> | null = null
const sleepOptions = [
  { label: t('不开启'), key: 0 },
  { label: t('15 分钟'), key: 15 },
  { label: t('30 分钟'), key: 30 },
  { label: t('60 分钟'), key: 60 },
  { label: t('90 分钟'), key: 90 },
]
function onSleep(min: number) {
  if (sleepTimer) clearInterval(sleepTimer)
  sleepTimer = null
  sleepLeft.value = min * 60
  if (!min) return
  sleepTimer = setInterval(() => {
    if (props.state !== 'playing') return
    if (--sleepLeft.value <= 0) {
      if (sleepTimer) clearInterval(sleepTimer)
      sleepTimer = null
      emit('stop')
    }
  }, 1000)
}
const sleepText = computed(() => {
  const s = sleepLeft.value
  return s > 0 ? `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}` : ''
})
onBeforeUnmount(() => sleepTimer && clearInterval(sleepTimer))
</script>

<template>
  <div class="tts-panel" :style="{ background: bg, color: fg }" @click.stop>
    <div class="row">
      <button class="icon-btn" :title="t('上一段')" @click="emit('prev')"><n-icon :component="PlaySkipBack" size="20" /></button>
      <button class="icon-btn main" :title="state !== 'paused' ? t('暂停') : t('继续')" @click="emit('toggle')">
        <n-icon :component="state !== 'paused' ? PauseCircle : PlayCircle" size="40" />
      </button>
      <button class="icon-btn" :title="t('下一段')" @click="emit('next')"><n-icon :component="PlaySkipForward" size="20" /></button>
      <div class="now">
        <div class="now-text">{{ error || (state === 'loading' ? t('正在准备…') : text) }}</div>
      </div>
      <n-dropdown :options="sleepOptions" trigger="click" @select="onSleep">
        <button class="icon-btn" :title="sleepText ? t('定时停止：剩余 {0}', [sleepText]) : t('定时停止')">
          <n-icon :component="TimerOutline" size="20" />
          <span v-if="sleepText" class="sleep">{{ sleepText }}</span>
        </button>
      </n-dropdown>
      <button class="icon-btn" :class="{ on: showOptions }" :title="t('朗读设置')" @click="showOptions = !showOptions">
        <n-icon :component="OptionsOutline" size="20" />
      </button>
      <button class="icon-btn" :title="t('停止朗读')" @click="emit('stop')"><n-icon :component="Close" size="20" /></button>
    </div>
    <div v-if="showOptions" class="options">
      <!-- 不用 label：label 里点任何地方都会触发其中的第一个按钮（管理引擎） -->
      <div class="option-row">
        <span>{{ t('引擎') }}</span>
        <div class="engine-row">
          <n-select
            :value="engineValue"
            :options="engineOptions"
            size="small"
            @update:value="(v: string) => emit('update:engine', v)"
          />
          <n-button size="small" @click="emit('manageEngines')">{{ t('管理引擎') }}</n-button>
        </div>
      </div>
      <label v-if="engineValue === 'browser'">
        <span>{{ t('语音') }}</span>
        <n-select
          :value="voice"
          :options="voiceOptions"
          size="small"
          filterable
          @update:value="(v: string) => emit('update:voice', v)"
        />
      </label>
      <label v-else-if="engineValue === 'edge'">
        <span>{{ t('语音') }}</span>
        <n-select
          class="edge-voice"
          :value="edgeVoice"
          :options="edgeVoiceOptions"
          size="small"
          filterable
          @update:value="(v: string) => emit('update:edgeVoice', v)"
        />
      </label>
      <label v-if="engineValue !== 'browser'">
        <span>{{ t('整章连续播放') }}</span>
        <n-switch :value="continuous" :aria-label="t('整章连续播放')" @update:value="(v: boolean) => emit('update:continuous', v)" />
      </label>
      <p v-if="engineValue !== 'browser'" class="muted small">{{ t('默认逐段播放；开启后先合成本章剩余内容，准备时间较长，跨章仍可能受后台限制。') }}</p>
      <label>
        <span>{{ t('语速 {0}x', [rate.toFixed(1)]) }}</span>
        <n-slider
          :value="rate"
          :min="0.5"
          :max="3"
          :step="0.1"
          :tooltip="false"
          @update:value="(v: number) => emit('update:rate', v)"
        />
      </label>
    </div>
  </div>
</template>

<style scoped>
.engine-row {
  display: flex;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.engine-row .n-select {
  flex: 1;
  min-width: 0;
}

.tts-panel {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  bottom: calc(16px + env(safe-area-inset-bottom));
  width: min(640px, calc(100vw - 24px));
  border-radius: 14px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.25);
  padding: 6px 10px;
  z-index: 30;
}

.row {
  display: flex;
  align-items: center;
  gap: 2px;
}

.icon-btn {
  border: none;
  background: none;
  color: inherit;
  cursor: pointer;
  padding: 6px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  gap: 2px;
  -webkit-tap-highlight-color: transparent;
}

.icon-btn:hover,
.icon-btn.on {
  background: rgba(127, 127, 127, 0.16);
}

.icon-btn.main {
  padding: 0 2px;
}

@media (pointer: coarse) {
  .icon-btn {
    min-width: 44px;
    min-height: 44px;
    justify-content: center;
  }
}

.sleep {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.now {
  flex: 1;
  min-width: 0;
  padding: 0 6px;
}

.now-text {
  font-size: 13px;
  opacity: 0.8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.options {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 4px 6px;
  border-top: 1px solid rgba(127, 127, 127, 0.2);
  margin-top: 6px;
}

.options label,
.option-row {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}

.options label > span,
.option-row > span {
  width: 76px;
  flex-shrink: 0;
  opacity: 0.8;
}

.hint {
  font-size: 12px;
  opacity: 0.7;
}
</style>
