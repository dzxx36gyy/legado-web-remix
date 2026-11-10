<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NEmpty, NModal, NSwitch, useMessage } from 'naive-ui'
import type { Book } from '@/api/types'
import BookCover from '@/components/BookCover.vue'
import SourceSearchStatus from '@/components/SourceSearchStatus.vue'
import { useSourceSearchStore } from '@/stores/sourceSearch'
import { t } from '@/i18n'

/**
 * 换封面（与 App 相同）：用书名在所有启用的书源中搜索，列出同名书籍的封面供选择。
 * 选中后通过 select 返回封面地址，空字符串表示恢复为书源封面。搜索结果与换源共用
 */
const props = defineProps<{ book: Book | null }>()
const show = defineModel<boolean>('show', { default: false })
const emit = defineEmits<{ select: [url: string] }>()
const message = useMessage()
const store = useSourceSearchStore()

/** 与换源共用搜索结果；组件只保存 key，通过 computed 读取 store 当前条目 */
const searchKey = ref('')
const search = computed(() => (searchKey.value ? store.get(searchKey.value) ?? null : null))
const matchAuthor = ref(true)

/** 同名（可选同作者）书籍的封面，同一个地址只显示一次 */
const covers = computed(() => {
  const b = props.book
  const s = search.value
  if (!b || !s) return []
  const seen = new Set<string>()
  return s.results.filter((r) => {
    if (!r.coverUrl || r.name !== b.name) return false
    if (matchAuthor.value && b.author && r.author !== b.author) return false
    if (seen.has(r.coverUrl)) return false
    seen.add(r.coverUrl)
    return true
  })
})

watch(show, (v) => {
  if (v && props.book) searchKey.value = store.ensureKey(props.book.name)
})

watch(
  () => search.value?.error,
  (e) => {
    if (e && show.value) message.error(e)
  },
)

function restart() {
  if (props.book) searchKey.value = store.run(props.book.name).key
}

function pick(url: string) {
  emit('select', url)
  show.value = false
}
</script>

<template>
  <n-modal v-model:show="show" preset="card" :title="t('换封面')" style="width: 720px; max-width: 94vw">
    <source-search-status :search="search" :matched="covers.length" @stop="search && store.stop(search)" @restart="restart">
      <span class="small">{{ t('作者相同') }}</span>
      <n-switch v-model:value="matchAuthor" size="small" />
    </source-search-status>
    <div class="cover-grid">
      <button v-if="book?.customCoverUrl" class="cover-item" @click="pick('')">
        <book-cover :url="book.coverUrl" :name="book.name" :author="book.author" :origin="book.origin" />
        <span class="cover-src">{{ t('恢复书源封面') }}</span>
      </button>
      <button v-for="r in covers" :key="r.coverUrl!" class="cover-item" :class="{ current: r.coverUrl === (book?.customCoverUrl || book?.coverUrl) }" @click="pick(r.coverUrl!)">
        <book-cover :url="r.coverUrl" :name="r.name" :author="r.author" :source-id="r.sourceId" />
        <span class="cover-src ellipsis">{{ r.originName }}</span>
      </button>
    </div>
    <n-empty v-if="search?.status === 'completed' && !covers.length" :description="t('没有找到其他封面')" style="padding: 24px 0" />
    <template #footer>
      <div style="display: flex; justify-content: flex-end; gap: 8px">
        <n-button size="small" @click="show = false">{{ t('关闭') }}</n-button>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.cover-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 14px 12px;
  max-height: 60vh;
  overflow-y: auto;
}

.cover-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  padding: 4px;
  border: 2px solid transparent;
  border-radius: 8px;
  background: none;
  color: inherit;
  cursor: pointer;
  text-align: center;
}

.cover-item:hover {
  background: color-mix(in srgb, var(--app-accent) 8%, transparent);
}

.cover-item.current {
  border-color: var(--app-accent);
}

.cover-src {
  font-size: 12px;
  color: var(--app-text-2);
}

.small {
  font-size: 12.5px;
}
</style>
