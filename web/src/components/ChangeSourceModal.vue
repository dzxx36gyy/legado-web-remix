<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NEmpty, NModal, NSwitch, NTag, useDialog, useMessage } from 'naive-ui'
import { shelfApi, toOpenRequest } from '@/api'
import type { Book, SearchBook } from '@/api/types'
import SourceSearchStatus from '@/components/SourceSearchStatus.vue'
import { useSourceSearchStore } from '@/stores/sourceSearch'
import { t } from '@/i18n'

const props = defineProps<{ show: boolean; book: Book | null }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void; (e: 'changed', b: Book): void }>()
const message = useMessage()
const dialog = useDialog()
const store = useSourceSearchStore()

/** 与换封面共用的搜索结果，关闭弹窗不中止，再次打开直接显示；组件只保存 key */
const searchKey = ref('')
const search = computed(() => (searchKey.value ? store.get(searchKey.value) ?? null : null))
const matchAuthor = ref(true)
const changing = ref<string | null>(null)

const list = computed(() => {
  const b = props.book
  const s = search.value
  if (!b || !s) return []
  return s.results
    .filter((r) => r.name === b.name && (!matchAuthor.value || !b.author || r.author === b.author))
    .sort((a, c) => a.respondTime - c.respondTime)
})

watch(
  () => props.show,
  (v) => {
    if (v && props.book) searchKey.value = store.ensureKey(props.book.name)
  },
)

watch(
  () => search.value?.error,
  (e) => {
    if (e && props.show) message.error(e)
  },
)

function restart() {
  if (props.book) searchKey.value = store.run(props.book.name).key
}

function change(r: SearchBook) {
  if (!props.book || changing.value) return
  dialog.warning({
    title: t('换源'),
    content: t('确定切换到「{0}」吗？这本书已离线下载的章节会被清除。', [r.originName]),
    positiveText: t('切换'),
    negativeText: t('取消'),
    onPositiveClick: () => {
      doChange(r)
    },
  })
}

async function doChange(r: SearchBook) {
  if (!props.book) return
  changing.value = r.bookUrl
  try {
    const nb = await shelfApi.changeSource(props.book.id, toOpenRequest(r))
    message.success(t('已切换到「{0}」', [r.originName]))
    emit('changed', nb)
    emit('update:show', false)
  } catch (e: any) {
    message.error(t('换源失败：{0}', [e.message]))
  } finally {
    changing.value = null
  }
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="t('换源')"
    style="max-width: 560px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <source-search-status :search="search" :matched="list.length" @stop="search && store.stop(search)" @restart="restart">
      <span class="muted small">{{ t('校验作者') }}</span>
      <n-switch v-model:value="matchAuthor" size="small" />
    </source-search-status>
    <n-empty v-if="search?.status === 'completed' && !list.length" :description="t('没有找到其他来源')" />
    <div class="list">
      <div
        v-for="r in list"
        :key="r.bookUrl"
        class="row"
        :class="{ current: r.bookUrl === book?.bookUrl }"
        @click="r.bookUrl !== book?.bookUrl && change(r)"
      >
        <div class="row-main">
          <div class="ellipsis">
            <b>{{ r.originName }}</b>
            <n-tag v-if="r.bookUrl === book?.bookUrl" size="tiny" type="success" :bordered="false" style="margin-left: 6px">{{ t('当前') }}</n-tag>
          </div>
          <div class="muted ellipsis small">{{ r.latestChapterTitle || t('无最新章节') }}</div>
        </div>
        <span class="muted small">{{ r.respondTime }}ms</span>
        <n-button v-if="r.bookUrl !== book?.bookUrl" size="tiny" :loading="changing === r.bookUrl">{{ t('切换') }}</n-button>
      </div>
    </div>
  </n-modal>
</template>

<style scoped>
.small {
  font-size: 12px;
  white-space: nowrap;
}

.list {
  max-height: 60vh;
  overflow: auto;
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 6px;
  border-bottom: 1px solid var(--app-border);
  cursor: pointer;
}

.row:hover {
  background: color-mix(in srgb, var(--app-accent) 6%, transparent);
}

.row.current {
  cursor: default;
}

.row-main {
  flex: 1;
  min-width: 0;
}
</style>
