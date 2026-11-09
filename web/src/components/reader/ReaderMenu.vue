<script setup lang="ts">
import { ref } from 'vue'
import { NButton, NIcon, NSlider } from 'naive-ui'
import {
  ArrowBackOutline, ListOutline, SettingsOutline, RefreshOutline, SwapHorizontalOutline, InformationCircleOutline,
  ContractOutline, ExpandOutline, HeadsetOutline,
} from '@vicons/ionicons5'
import type { Book, Chapter } from '@/api/types'
import { t } from '@/i18n'

/** 阅读页点击屏幕中间呼出的顶部、底部菜单（颜色用阅读主题的 --r-panel / --r-text） */
const props = defineProps<{
  visible: boolean
  book: Book | null
  chapters: Chapter[]
  curIndex: number
  /** 章内进度（0-10000） */
  curPos: number
  curTitle: string
  progressPercent: string
  reloading: boolean
  isComic: boolean
  isFullscreen: boolean
  isNight: boolean
  /** 主题色块（夜间 / 日间按钮） */
  themeBg: string
}>()

const emit = defineEmits<{
  back: []
  reload: []
  changeSource: []
  info: []
  prevChapter: []
  nextChapter: []
  goChapter: [index: number]
  /** 跳到本章内的位置（0-10000） */
  seek: [pos: number]
  toc: []
  settings: []
  readAloud: []
  fullscreen: []
  night: []
}>()

/** 拖动章节进度条时只显示目标章节，松开后再跳转 */
const dragValue = ref<number | null>(null)
let dragging = false
function onSlide(v: number) {
  if (dragging) dragValue.value = v
  else if (v !== props.curIndex) emit('goChapter', v) // 键盘调整
}
function onDragStart() {
  dragging = true
  dragValue.value = props.curIndex
}
function onDragEnd() {
  dragging = false
  const v = dragValue.value
  dragValue.value = null
  if (v !== null && v !== props.curIndex) emit('goChapter', v)
}
</script>

<template>
  <transition name="slide-down">
    <header v-if="visible" class="topbar">
      <n-button quaternary circle @click="$emit('back')">
        <template #icon><n-icon :component="ArrowBackOutline" /></template>
      </n-button>
      <div class="top-title">
        <div class="ellipsis">{{ book?.name }}</div>
        <div class="ellipsis sub">{{ curTitle }} · {{ book?.originName }}</div>
      </div>
      <n-button quaternary circle :title="reloading ? t('正在刷新…') : t('刷新本章')" :disabled="reloading" @click="$emit('reload')">
        <template #icon><n-icon :component="RefreshOutline" :class="{ spinning: reloading }" /></template>
      </n-button>
      <n-button quaternary circle :title="t('换源')" @click="$emit('changeSource')">
        <template #icon><n-icon :component="SwapHorizontalOutline" /></template>
      </n-button>
      <n-button quaternary circle :title="t('书籍详情')" @click="$emit('info')">
        <template #icon><n-icon :component="InformationCircleOutline" /></template>
      </n-button>
    </header>
  </transition>

  <transition name="slide-up">
    <footer v-if="visible" class="bottombar">
      <!-- 章内进度：拖动时直接跳到本章对应位置（不用重新加载正文） -->
      <div class="progress-row pos-row">
        <span class="row-label">{{ t('本章') }}</span>
        <n-slider
          class="pos-slider"
          :value="Math.round(curPos)"
          :min="0"
          :max="10000"
          :step="1"
          :format-tooltip="(v: number) => `${Math.round(v / 100)}%`"
          style="flex: 1"
          @update:value="(v: number) => $emit('seek', v)"
        />
        <span class="row-label">{{ Math.round(curPos / 100) }}%</span>
      </div>
      <div class="progress-row">
        <n-button text :disabled="curIndex <= 0" @click="$emit('prevChapter')">{{ t('上一章') }}</n-button>
        <n-slider
          :value="dragValue ?? curIndex"
          :min="0"
          :max="Math.max(0, chapters.length - 1)"
          :step="1"
          :format-tooltip="(v: number) => chapters[v]?.title ?? ''"
          style="flex: 1"
          @update:value="onSlide"
          @dragstart="onDragStart"
          @dragend="onDragEnd"
        />
        <n-button text :disabled="curIndex >= chapters.length - 1" @click="$emit('nextChapter')">{{ t('下一章') }}</n-button>
      </div>
      <div class="tool-row">
        <button @click="$emit('toc')"><n-icon :component="ListOutline" size="22" /><span>{{ t('目录') }}</span></button>
        <button @click="$emit('settings')"><n-icon :component="SettingsOutline" size="22" /><span>{{ t('设置') }}</span></button>
        <button v-if="!isComic" @click="$emit('readAloud')"><n-icon :component="HeadsetOutline" size="22" /><span>{{ t('朗读') }}</span></button>
        <button @click="$emit('fullscreen')">
          <n-icon :component="isFullscreen ? ContractOutline : ExpandOutline" size="22" /><span>{{ isFullscreen ? t('退出全屏') : t('全屏') }}</span>
        </button>
        <button @click="$emit('night')">
          <span class="theme-dot" :style="{ background: themeBg }" /><span>{{ isNight ? t('日间') : t('夜间') }}</span>
        </button>
      </div>
      <div v-if="dragValue !== null" class="progress-text ellipsis">{{ dragValue + 1 }}/{{ chapters.length }} · {{ chapters[dragValue]?.title }}</div>
      <div v-else class="progress-text">{{ progressPercent }}% · {{ curIndex + 1 }}/{{ chapters.length }}</div>
    </footer>
  </transition>
</template>

<style scoped>
.topbar,
.bottombar {
  position: fixed;
  left: 0;
  right: 0;
  background: var(--r-panel);
  color: var(--r-text);
  z-index: 20;
  box-shadow: 0 0 12px rgba(0, 0, 0, 0.12);
}

/* 按钮跟随阅读主题而不是界面主题：界面深色 + 阅读浅色时默认的浅色文字会看不清 */
.topbar :deep(.n-button),
.bottombar :deep(.n-button) {
  --n-text-color: var(--r-text) !important;
  --n-text-color-hover: var(--r-text) !important;
  --n-text-color-pressed: var(--r-text) !important;
  --n-text-color-focus: var(--r-text) !important;
  --n-text-color-disabled: color-mix(in srgb, var(--r-text) 40%, transparent) !important;
}

.topbar {
  top: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 10px;
  padding-top: calc(8px + env(safe-area-inset-top));
}

.top-title {
  flex: 1;
  min-width: 0;
  font-weight: 600;
}

.top-title .sub {
  font-size: 12px;
  font-weight: 400;
  opacity: 0.7;
}

/* 刷新本章时图标转圈 */
.spinning {
  animation: reader-spin 0.8s linear infinite;
}

@keyframes reader-spin {
  to {
    transform: rotate(360deg);
  }
}

.bottombar {
  bottom: 0;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
}

.progress-row {
  display: flex;
  align-items: center;
  gap: 14px;
  max-width: 720px;
  margin: 0 auto;
}

.pos-row {
  margin-bottom: 6px;
}

.row-label {
  flex-shrink: 0;
  min-width: 3em;
  font-size: 13px;
  opacity: 0.75;
  text-align: center;
}

.tool-row {
  display: flex;
  justify-content: space-around;
  max-width: 520px;
  margin: 10px auto 4px;
}

.tool-row button {
  border: none;
  background: none;
  color: inherit;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  cursor: pointer;
  padding: 4px 10px;
}

.theme-dot {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 1px solid currentColor;
}

.progress-text {
  text-align: center;
  font-size: 12px;
  opacity: 0.6;
}

.slide-down-enter-active,
.slide-down-leave-active,
.slide-up-enter-active,
.slide-up-leave-active {
  transition: transform 0.2s ease, opacity 0.2s;
}

.slide-down-enter-from,
.slide-down-leave-to {
  transform: translateY(-100%);
  opacity: 0;
}

.slide-up-enter-from,
.slide-up-leave-to {
  transform: translateY(100%);
  opacity: 0;
}

/*
 * iOS（添加到主屏幕后打开）的状态栏取顶部固定元素的颜色，顶栏消失后不会再变回来，会一直是菜单的颜色。
 * 手机上收起菜单时，顶栏先换成正文背景色再滑出，状态栏最后取到的是正文背景色
 */
@media (max-width: 799px) {
  .topbar.slide-down-leave-active {
    background: var(--r-bg);
  }
}
</style>
