import { defineStore } from 'pinia'
import { reactive } from 'vue'
import { onSourceMutation, searchApi } from '@/api'
import { isAbortError } from '@/api/http'
import type { SearchBook } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

export const SOURCE_SEARCH_STATUSES = ['searching', 'aborted', 'failed', 'completed'] as const
export type SourceSearchStatus = (typeof SOURCE_SEARCH_STATUSES)[number]

export interface SourceSearch {
  /** 书名；组件只保存这个 key，通过 store 的 computed 读取当前条目 */
  readonly key: string
  results: SearchBook[]
  done: number
  total: number
  errors: { source: string; error: string }[]
  status: SourceSearchStatus
  /** 出错信息（中止时不设置） */
  error?: string
  controller?: AbortController
}

/** 最多保留的书名数 */
const MAX_ENTRIES = 10

/**
 * 换源、换封面共用的全书源搜索结果（只存在内存中，刷新页面后清空）。
 * 关闭弹窗不会中止搜索，再次打开时直接显示已有结果。
 * 书源配置变更后由 api 层的 onSourceMutation 统一清空，避免继续使用旧结果。
 */
export const useSourceSearchStore = defineStore('sourceSearch', () => {
  const entries = reactive(new Map<string, SourceSearch>())

  function cacheKey(name: string) {
    return `${useAuthStore().user?.id ?? 0}|${name}`
  }

  function touch(k: string, entry: SourceSearch) {
    // 最近使用的放到最后
    entries.delete(k)
    entries.set(k, entry)
    return entry
  }

  /** 读取当前用户的条目；没有时返回 undefined（不会触发搜索，也不改 LRU 顺序） */
  function get(name: string): SourceSearch | undefined {
    return entries.get(cacheKey(name))
  }

  /** 取某本书的搜索结果；没有时开始搜索 */
  function ensure(name: string): SourceSearch {
    const k = cacheKey(name)
    const exist = entries.get(k)
    if (exist) return touch(k, exist)
    return run(name, false)
  }

  /** 只返回 key，供组件保存 */
  function ensureKey(name: string): string {
    return ensure(name).key
  }

  /** 清空已有结果重新搜索 */
  function run(name: string, refresh = true): SourceSearch {
    const k = cacheKey(name)
    const previous = entries.get(k)
    if (previous) {
      previous.status = 'aborted'
      previous.controller?.abort()
    }
    entries.delete(k)
    const s = reactive<SourceSearch>({
      key: name,
      results: [],
      done: 0,
      total: 0,
      errors: [],
      status: 'searching',
    })
    entries.set(k, s)
    while (entries.size > MAX_ENTRIES) {
      const oldest = entries.entries().next()
      if (oldest.done) break
      const [oldKey, old] = oldest.value
      old.status = 'aborted'
      old.controller?.abort()
      entries.delete(oldKey)
    }
    const controller = new AbortController()
    s.controller = controller
    searchApi
      .search(
        { key: name, refresh },
        (e) => {
          if (s.status !== 'searching') return
          if (e.type === 'books') s.results.push(...e.books)
          else if (e.type === 'progress') {
            s.done = e.done
            s.total = e.total
            if (e.error) s.errors.push({ source: e.source, error: e.error })
          }
        },
        controller.signal,
      )
      .then(() => {
        if (s.status === 'searching') s.status = 'completed'
      })
      .catch((e: unknown) => {
        if (s.status !== 'searching') return
        if (isAbortError(e)) {
          s.status = 'aborted'
          return
        }
        s.error = e instanceof Error ? e.message : String(e)
        s.status = 'failed'
      })
      .finally(() => {
        if (s.controller === controller) s.controller = undefined
      })
    return s
  }

  /** 用户点击停止：保留已收到的结果，状态记为 aborted */
  function stop(s: SourceSearch) {
    if (s.status !== 'searching') return
    s.status = 'aborted'
    s.controller?.abort()
  }

  /** 书源启用 / 编辑 / 登录 / 导入 / 删除后清空所有缓存 */
  function invalidate() {
    for (const s of entries.values()) {
      s.status = 'aborted'
      s.controller?.abort()
    }
    entries.clear()
  }

  onSourceMutation(invalidate)

  return { get, ensure, ensureKey, run, stop, invalidate }
})
