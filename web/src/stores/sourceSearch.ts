import { defineStore } from 'pinia'
import { reactive } from 'vue'
import { searchApi } from '@/api'
import type { SearchBook } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

export interface SourceSearch {
  key: string
  results: SearchBook[]
  done: number
  total: number
  errors: { source: string; error: string }[]
  searching: boolean
  /** 出错信息（非中止） */
  error?: string
  controller?: AbortController
}

/** 最多保留的书名数 */
const MAX_ENTRIES = 10

/**
 * 换源、换封面共用的全书源搜索结果（只存在内存中，刷新页面后清空）。
 * 关闭弹窗不会中止搜索，再次打开时直接显示已有结果
 */
export const useSourceSearchStore = defineStore('sourceSearch', () => {
  const entries = reactive(new Map<string, SourceSearch>())

  function cacheKey(name: string) {
    return `${useAuthStore().user?.id ?? 0}|${name}`
  }

  /** 取某本书的搜索结果；没有时开始搜索 */
  function ensure(name: string): SourceSearch {
    const k = cacheKey(name)
    const exist = entries.get(k)
    if (exist) {
      // 最近使用的放到最后
      entries.delete(k)
      entries.set(k, exist)
      return exist
    }
    return run(name, false)
  }

  /** 清空已有结果重新搜索 */
  function run(name: string, refresh = true): SourceSearch {
    const k = cacheKey(name)
    entries.get(k)?.controller?.abort()
    entries.delete(k)
    const s = reactive<SourceSearch>({ key: name, results: [], done: 0, total: 0, errors: [], searching: true })
    entries.set(k, s)
    while (entries.size > MAX_ENTRIES) {
      const [oldKey, old] = entries.entries().next().value!
      old.controller?.abort()
      entries.delete(oldKey)
    }
    const controller = new AbortController()
    s.controller = controller
    searchApi
      .search(
        { key: name, refresh },
        (e) => {
          if (e.type === 'books') s.results.push(...e.books)
          else if (e.type === 'progress') {
            s.done = e.done
            s.total = e.total
            if (e.error) s.errors.push({ source: e.source, error: e.error })
          }
        },
        controller.signal,
      )
      .catch((e: any) => {
        if (e.name !== 'AbortError') s.error = e.message
      })
      .finally(() => {
        s.searching = false
        s.controller = undefined
      })
    return s
  }

  function stop(s: SourceSearch) {
    s.controller?.abort()
  }

  return { ensure, run, stop }
})
