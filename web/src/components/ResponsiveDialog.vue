<script setup lang="ts">
/**
 * 电脑上是居中的卡片对话框，手机上是底部抽屉（不再一层层叠弹窗）。
 * 减少动态效果时抽屉不做滑入（见 styles/base.css）
 */
import { NDrawer, NDrawerContent, NModal } from 'naive-ui'
import { useIsMobile } from '@/composables/useIsMobile'

defineProps<{ title: string; width?: string }>()
const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ afterLeave: [] }>()
const isMobile = useIsMobile()
</script>

<template>
  <n-drawer
    v-if="isMobile"
    v-model:show="show"
    placement="bottom"
    height="88dvh"
    :auto-focus="false"
    class="responsive-sheet"
    @after-leave="emit('afterLeave')"
  >
    <n-drawer-content :title="title" closable :native-scrollbar="true">
      <slot />
    </n-drawer-content>
  </n-drawer>
  <n-modal
    v-else
    v-model:show="show"
    preset="card"
    :title="title"
    :style="{ width: width ?? '720px', maxWidth: '94vw', maxHeight: '88dvh' }"
    content-style="overflow-y: auto; min-height: 0"
    :auto-focus="false"
    @after-leave="emit('afterLeave')"
  >
    <slot />
  </n-modal>
</template>

<style>
/* 抽屉挂在 body 下，需要非 scoped 样式：顶部圆角，底部留出安全区 */
.n-drawer.responsive-sheet {
  border-radius: 16px 16px 0 0;
  overflow: hidden;
}

.n-drawer.responsive-sheet .n-drawer-body-content-wrapper {
  padding-bottom: calc(16px + env(safe-area-inset-bottom));
}
</style>
