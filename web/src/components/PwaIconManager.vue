<script setup lang="ts">
/** 应用图标：内置几套，也可以上传自己的图片；只对当前设备生效 */
import { computed, onBeforeUnmount, ref } from 'vue'
import { NButton, NIcon, NSpace, useMessage } from 'naive-ui'
import { CheckmarkCircle } from '@vicons/ionicons5'
import { ApiError } from '@/api/http'
import { t } from '@/i18n'
import {
  BUILTIN_ICONS,
  DEFAULT_STATE,
  ICON_ACCEPT,
  IconFileError,
  builtinIconUrls,
  customIconUrls,
  loadPwaIcon,
  renderIconFile,
  savePwaIcon,
  uploadCustomIcon,
  type BuiltinIcon,
  type IconSelection,
  type PwaIconState,
} from '@/utils/pwaIcon'

const message = useMessage()
const state = ref<PwaIconState>(loadPwaIcon())

const LABELS: Record<BuiltinIcon, string> = {
  default: t('默认'),
  ink: t('墨色'),
  celadon: t('青瓷'),
  paper: t('纸白'),
  midnight: t('夜蓝'),
}

const options = computed(() => {
  const list: { value: IconSelection; label: string; src: string }[] = BUILTIN_ICONS.map((id) => ({
    value: id,
    label: LABELS[id],
    src: builtinIconUrls(id).preview,
  }))
  if (state.value.customId) list.push({ value: 'custom', label: t('自定义'), src: customIconUrls(state.value.customId).preview })
  return list
})

function choose(value: IconSelection) {
  if (value === state.value.selected) return
  state.value = { ...state.value, selected: value }
  savePwaIcon(state.value)
}

function reset() {
  state.value = { ...DEFAULT_STATE, customId: state.value.customId }
  savePwaIcon(state.value)
}

// ---------------- 上传 ----------------

const fileInput = ref<HTMLInputElement | null>(null)
/** 处理好、还没上传的图标：先预览，确认后才上传 */
const pending = ref<{ blob: Blob; url: string } | null>(null)
const busy = ref(false)

function clearPending() {
  if (pending.value) URL.revokeObjectURL(pending.value.url)
  pending.value = null
}
onBeforeUnmount(clearPending)

async function onFile(event: Event) {
  const input = event.target
  if (!(input instanceof HTMLInputElement) || !input.files?.[0]) return
  const file = input.files[0]
  input.value = ''
  busy.value = true
  try {
    const blob = await renderIconFile(file)
    clearPending()
    pending.value = { blob, url: URL.createObjectURL(blob) }
  } catch (error) {
    if (!(error instanceof IconFileError)) throw error
    message.error(error.message)
  } finally {
    busy.value = false
  }
}

async function confirmUpload() {
  if (!pending.value) return
  busy.value = true
  try {
    const id = await uploadCustomIcon(pending.value.blob)
    state.value = { selected: 'custom', customId: id }
    savePwaIcon(state.value)
    clearPending()
    message.success(t('已更换应用图标'))
  } catch (error) {
    if (!(error instanceof ApiError)) throw error
    message.error(error.status === 404 || error.status === 405 ? t('服务器不支持自定义图标，请升级后再试') : error.message)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="icon-grid" role="radiogroup" :aria-label="t('应用图标')">
    <button
      v-for="o in options"
      :key="o.value"
      type="button"
      role="radio"
      class="icon-option"
      :class="{ active: state.selected === o.value }"
      :aria-checked="state.selected === o.value"
      @click="choose(o.value)"
    >
      <span class="icon-frame">
        <img :src="o.src" alt="" width="56" height="56" />
        <n-icon v-if="state.selected === o.value" :component="CheckmarkCircle" class="icon-check" />
      </span>
      <span class="icon-label">{{ o.label }}</span>
    </button>
  </div>

  <div v-if="pending" class="pending">
    <img :src="pending.url" :alt="t('新图标预览')" width="72" height="72" class="pending-img" />
    <div class="pending-body">
      <p class="hint">{{ t('图片已裁成正方形。确认后上传，并作为这台设备的应用图标。') }}</p>
      <n-space size="small">
        <n-button size="small" type="primary" :loading="busy" @click="confirmUpload">{{ t('使用此图标') }}</n-button>
        <n-button size="small" :disabled="busy" @click="clearPending">{{ t('取消') }}</n-button>
      </n-space>
    </div>
  </div>

  <n-space size="small" class="actions">
    <n-button size="small" :loading="busy && !pending" :disabled="busy" @click="fileInput?.click()">{{ t('上传图片') }}</n-button>
    <n-button size="small" :disabled="state.selected === 'default'" @click="reset">{{ t('恢复默认') }}</n-button>
  </n-space>
  <input ref="fileInput" type="file" :accept="ICON_ACCEPT" hidden @change="onFile" />
  <p class="hint">
    {{ t('只对这台设备生效。PNG、JPEG、WebP 或 SVG，至少 192×192 像素，不超过 5MB。') }}
    {{ t('浏览器标签页的图标会立即更换；已经添加到主屏幕的应用图标不会自动更新，iPhone 和 iPad 需要删除后重新添加到主屏幕。') }}
  </p>
</template>

<style scoped>
.icon-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.icon-option {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  min-width: 72px;
  padding: 8px 4px 6px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.icon-option:hover {
  background: color-mix(in srgb, var(--app-accent) 8%, transparent);
}

.icon-option.active {
  background: color-mix(in srgb, var(--app-accent) 14%, transparent);
  color: var(--app-accent);
}

.icon-option:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
}

.icon-frame {
  position: relative;
  display: block;
  width: 56px;
  height: 56px;
}

.icon-frame img {
  display: block;
  width: 56px;
  height: 56px;
  border-radius: 12px;
  object-fit: cover;
  box-shadow: 0 0 0 1px var(--app-border);
}

.icon-check {
  position: absolute;
  right: -6px;
  bottom: -6px;
  font-size: 20px;
  color: var(--app-accent);
  background: var(--app-surface);
  border-radius: 50%;
}

.pending {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 12px;
  padding: 12px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--app-accent) 6%, transparent);
}

.pending-img {
  flex: none;
  border-radius: 14px;
  box-shadow: 0 0 0 1px var(--app-border);
}

.pending-body {
  min-width: 0;
}

.pending-body .hint {
  margin-top: 0;
}

.actions {
  margin-top: 12px;
}

.hint {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--app-text-2);
}
</style>
