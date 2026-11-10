<script setup lang="ts">
import { ref } from 'vue'
import { NButton, NIcon, NModal, NProgress } from 'naive-ui'
import { RefreshOutline, StopCircleOutline } from '@vicons/ionicons5'
import type { SourceSearch } from '@/stores/sourceSearch'
import { t } from '@/i18n'

/** 换源、换封面弹窗的搜索进度栏（与搜书页一致）：进度、匹配数、失败的书源、停止 / 重新搜索 */
defineProps<{ search: SourceSearch | null; matched: number }>()
const emit = defineEmits<{ stop: []; restart: [] }>()
const showErrors = ref(false)
</script>

<template>
  <div class="status">
    <n-progress
      type="line"
      :percentage="search?.total ? Math.round((search.done / search.total) * 100) : 0"
      :show-indicator="false"
      :height="4"
      :processing="search?.status === 'searching'"
      style="flex: 1; min-width: 60px"
    />
    <span class="muted small">{{ t('{0}/{1} 个书源 · 匹配 {2} / 共 {3} 条', [search?.done ?? 0, search?.total ?? 0, matched, search?.results.length ?? 0]) }}</span>
    <span v-if="search?.status === 'aborted'" class="muted small">{{ t('已停止') }}</span>
    <span v-else-if="search?.status === 'failed'" class="muted small">{{ t('搜索失败') }}</span>
    <n-button v-if="search?.errors.length" text size="tiny" type="error" @click="showErrors = true">{{ t('{0} 个失败', [search.errors.length]) }}</n-button>
    <slot />
    <n-button v-if="search?.status === 'searching'" size="tiny" type="warning" @click="emit('stop')">
      <template #icon><n-icon :component="StopCircleOutline" /></template>
      {{ t('停止') }}
    </n-button>
    <n-button v-else size="tiny" @click="emit('restart')">
      <template #icon><n-icon :component="RefreshOutline" /></template>
      {{ t('重新搜索') }}
    </n-button>
  </div>
  <n-modal v-model:show="showErrors" preset="card" :title="t('{0} 个书源搜索失败', [search?.errors.length ?? 0])" style="width: 520px; max-width: calc(100vw - 24px)">
    <div class="err-list">
      <div v-for="(e, i) in search?.errors" :key="i" class="err-line">
        <b>{{ e.source }}</b>：<span class="muted">{{ e.error }}</span>
      </div>
    </div>
  </n-modal>
</template>

<style scoped>
.status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.small {
  font-size: 12px;
  white-space: nowrap;
}

.err-list {
  max-height: 60vh;
  overflow: auto;
  font-size: 13px;
}

.err-line {
  padding: 4px 0;
  overflow-wrap: anywhere;
}
</style>
