<script setup lang="ts">
/**
 * 对话框里再打开的子页面（编辑表单等）。
 * 电脑上是叠在上面的卡片对话框；手机上不再叠一层弹窗，而是在原位置显示，顶部带返回按钮，
 * 父组件在 show 为 true 时隐藏自己的列表即可实现“列表 ↔ 表单”的页内切换
 */
import { NButton, NIcon, NModal } from 'naive-ui'
import { ArrowBackOutline } from '@vicons/ionicons5'
import { useIsMobile } from '@/composables/useIsMobile'
import { t } from '@/i18n'

defineProps<{ title: string; width?: string }>()
const show = defineModel<boolean>('show', { default: false })
const isMobile = useIsMobile()
</script>

<template>
  <section v-if="isMobile && show" class="nested-pane" :aria-label="title">
    <header class="pane-head">
      <n-button quaternary circle :aria-label="t('返回')" :title="t('返回')" @click="show = false">
        <template #icon><n-icon :component="ArrowBackOutline" /></template>
      </n-button>
      <h3 class="pane-title">{{ title }}</h3>
    </header>
    <slot />
    <div v-if="$slots.footer" class="pane-footer"><slot name="footer" /></div>
  </section>
  <n-modal
    v-else-if="!isMobile"
    v-model:show="show"
    preset="card"
    :title="title"
    :style="{ width: width ?? '560px', maxWidth: '94vw' }"
    :auto-focus="false"
  >
    <slot />
    <template v-if="$slots.footer" #footer><slot name="footer" /></template>
  </n-modal>
</template>

<style scoped>
.pane-head {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 0 0 12px -8px;
}

.pane-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pane-footer {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--app-border);
}

@media (pointer: coarse) {
  .pane-head :deep(.n-button) {
    min-width: 44px;
    min-height: 44px;
  }
}
</style>
