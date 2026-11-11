import { defineStore } from 'pinia'
import { reactive, watch } from 'vue'
import type { ExploreKind, SearchBook, SourceSummary } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

function emptyState() {
  return {
    /** 是否已加载，空列表也属于有效缓存 */
    initialized: false,
    /** 离开发现页时的滚动位置，随用户缓存隔离 */
    scrollTop: 0,
    /** 可用书源列表 */
    sources: [] as SourceSummary[],
    /** 当前用户选中的书源 */
    sourceId: null as number | null,
    /** 发现页的书源分组筛选，null 表示全部分组 */
    group: null as string | null,
    /** 当前书源的发现分类 */
    kinds: [] as ExploreKind[],
    /** 分类控件的值 */
    infoMap: {} as Record<string, string>,
    /** 当前分类 */
    activeKind: null as ExploreKind | null,
    /** 已加载的书籍 */
    books: [] as SearchBook[],
    /** 下一页页码 */
    page: 1,
    /** 是否已经没有更多书籍 */
    noMore: false,
  }
}

/** 按用户保存本次浏览会话的发现结果，返回页面时不触发请求。 */
export const useExploreStore = defineStore('explore', () => {
  const auth = useAuthStore()
  const state = reactive(emptyState())
  let key = ''
  watch(() => auth.user?.id, (id) => {
    key = `legado_explore_${id ?? 0}`
    Object.assign(state, emptyState())
    try {
      const raw = sessionStorage.getItem(key)
      if (raw) {
        const cached: ReturnType<typeof emptyState> = JSON.parse(raw)
        if (cached.initialized && Array.isArray(cached.sources) && Array.isArray(cached.kinds)
          && Array.isArray(cached.books) && Number.isInteger(cached.page) && cached.page > 0) {
          Object.assign(state, cached)
        }
      }
    } catch (error) {
      console.warn('无法恢复发现缓存', error)
    }
  }, { immediate: true, flush: 'sync' })
  watch(state, () => {
    try {
      sessionStorage.setItem(key, JSON.stringify(state))
    } catch (error) {
      console.warn('无法保存发现缓存', error)
    }
  }, { deep: true })
  return { state }
})
