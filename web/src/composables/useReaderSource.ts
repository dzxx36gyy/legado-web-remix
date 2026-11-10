import { onBeforeUnmount, type Ref } from 'vue'
import { useMessage } from 'naive-ui'
import { searchApi, shelfApi, toOpenRequest } from '@/api'
import type { Book, Chapter, SearchBook } from '@/api/types'
import { useOfflineStore } from '@/stores/offline'
import { t } from '@/i18n'

/** Manual and automatic recovery must invalidate the same source generation. */
export function useReaderSource(d: {
  book: Ref<Book | null>
  chapters: Ref<Chapter[]>
  resetContent: () => void
  goChapter: (index: number, pos: number) => Promise<void>
}) {
  const message = useMessage()
  const offline = useOfflineStore()
  let autoChanged = false
  const sourceRecovery = new AbortController()
  onBeforeUnmount(() => sourceRecovery.abort())
  /** 每次换源（手动或自动）加一：较早发起的换源、目录请求晚返回时不能覆盖较新的结果 */
  let sourceSeq = 0
  async function onChanged(nb: Book) {
    const seq = ++sourceSeq
    d.book.value = nb
    d.resetContent()
    try {
      const c = await shelfApi.chapters(nb.id)
      if (seq !== sourceSeq || sourceRecovery.signal.aborted) return
      d.chapters.value = c
      // 换源后先清掉旧的离线正文，再加载章节
      await offline.refreshBook(nb, c)
      if (seq !== sourceSeq || sourceRecovery.signal.aborted) return
      await d.goChapter(Math.min(nb.durChapterIndex, c.length - 1), 0)
    } catch (error) {
      if (seq === sourceSeq && !sourceRecovery.signal.aborted) message.error(error instanceof Error ? error.message : t('请求失败'))
    }
  }

  async function autoChangeSource() {
    const current = d.book.value
    if (!current || autoChanged || current.sourceId != null || current.isLocal) return
    autoChanged = true
    const seq = sourceSeq
    const found: SearchBook[] = []
    const controller = new AbortController()
    const abort = () => controller.abort()
    sourceRecovery.signal.addEventListener('abort', abort, { once: true })
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      await searchApi.search({ key: current.name }, (event) => {
        if (event.type !== 'books') return
        found.push(...event.books.filter((item) => item.name === current.name && (!current.author || item.author === current.author)))
        if (found.length && !timer) timer = setTimeout(abort, 2000)
      }, controller.signal)
    } catch (error) {
      if (!(error instanceof Error && error.name === 'AbortError')) message.warning(error instanceof Error ? error.message : t('请求失败'))
    } finally {
      if (timer) clearTimeout(timer)
      sourceRecovery.signal.removeEventListener('abort', abort)
    }
    for (const hit of found.sort((a, b) => a.respondTime - b.respondTime).slice(0, 3)) {
      if (sourceRecovery.signal.aborted || seq !== sourceSeq) return
      try {
        const updated = await shelfApi.changeSource(current.id, toOpenRequest(hit))
        // 等待期间用户手动换了源：不再用自动换源的结果覆盖
        if (!sourceRecovery.signal.aborted && seq === sourceSeq) await onChanged(updated)
        return
      } catch (error) {
        message.warning(error instanceof Error ? error.message : t('请求失败'))
      }
    }
  }
  return { onChanged, autoChangeSource }
}
