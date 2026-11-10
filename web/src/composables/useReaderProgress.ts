import { computed, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import { useDialog, useMessage } from 'naive-ui'
import { webdavApi } from '@/api'
import { getClientId, getToken } from '@/api/http'
import type { Book, Chapter } from '@/api/types'
import { putLocalProgress } from '@/offline/db'
import { useOfflineStore } from '@/stores/offline'
import { t } from '@/i18n'

/** Persistence and remote following share one local-action clock. */
export function useReaderProgress(d: {
  bookId: Ref<number>
  book: Ref<Book | null>
  chapters: Ref<Chapter[]>
  busy: () => boolean
  goChapter: (index: number, pos: number, save?: boolean) => Promise<void>
}) {
  const offline = useOfflineStore()
  const message = useMessage()
  const dialog = useDialog()
  const curIndex = ref(0)
  const curPos = ref(0)
  const curTitle = computed(() => d.chapters.value[curIndex.value]?.title ?? '')
  const progressPercent = computed(() => d.chapters.value.length
    ? (((curIndex.value + curPos.value / 10000) / d.chapters.value.length) * 100).toFixed(1) : '0')
  // 首次打开章节完成前 curIndex/curPos 还是默认值，此时保存会把真实进度覆盖成第 1 章开头
  // 位置没变就不再上传：页面关闭时 beforeunload / visibilitychange / pagehide 会接连触发；
  // 另一台设备离线读得更靠后、联网同步后，这里只是切到后台也不能用旧位置把它盖掉
  const state = { ready: false, lastSaved: '', followedChapter: -1 }
  let saveTimer: ReturnType<typeof setTimeout> | null = null
  let lastLocalInput = 0
  let localActionVersion = 0
  let remoteUntil = 0

  function scheduleSave() {
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(saveProgress, 1500)
  }

  function saveProgress(keepalive = false) {
    if (!d.book.value || !state.ready || Date.now() < remoteUntil) return
    const index = curIndex.value
    const pos = Math.round(curPos.value)
    const title = curTitle.value
    const key = `${index}:${pos}`
    if (key === state.lastSaved) return
    state.lastSaved = key
    if (keepalive) {
      if (saveTimer) clearTimeout(saveTimer)
      saveTimer = null
      // 页面关闭时：本地记录 + keepalive 请求（离线时请求失败，下次联网再同步）
      const time = Date.now()
      putLocalProgress({ bookId: d.bookId.value, index, pos, title, time, dirty: true }).catch(() => {})
      void fetch(`/api/shelf/${d.bookId.value}/progress`, {
        method: 'PUT', keepalive: true,
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}`, 'X-Client-Id': getClientId() },
        body: JSON.stringify({ index, pos, title }),
      }).catch(() => {})
    } else {
      offline.saveProgress(d.bookId.value, index, pos, title)
    }
  }

  /** WebDAV 进度同步：云端较新直接采用；云端较旧但章节不同则询问（与 App 一致） */
  async function pullCloudProgress(b: Book, chapterCount: number) {
    const version = localActionVersion
    const remote = await webdavApi.progress(b.id).then((r) => r.progress).catch(() => null)
    if (!remote || !d.book.value || d.book.value.id !== b.id) return
    // 云端响应慢：等待期间用户已经翻页、跳章或开始朗读，就不再把位置拉回去。
    if (localActionVersion !== version || d.busy()) return
    const index = Math.min(chapterCount - 1, Math.max(0, remote.index))
    if (index === curIndex.value && Math.abs(remote.pos - curPos.value) < 50) return
    const apply = async () => {
      await d.goChapter(index, remote.pos)
      saveProgress()
    }
    if (remote.time > b.durChapterTime) {
      void apply()
      message.success(t('已同步最新阅读进度'))
    } else if (index !== curIndex.value) {
      dialog.info({
        title: t('云端进度'),
        content: t('云端进度为“{0}”，比当前进度旧，是否跳转？', [remote.title || t('第 {0} 章', [index + 1])]),
        positiveText: t('跳转'), negativeText: t('取消'), onPositiveClick: apply,
      })
    }
  }

  const localInputEvents = ['pointerdown', 'wheel', 'keydown', 'touchstart'] as const
  function markLocalInput() {
    localActionVersion++
    lastLocalInput = Date.now()
    remoteUntil = 0
  }
  for (const event of localInputEvents) window.addEventListener(event, markLocalInput, { passive: true, capture: true })
  function onRemoteProgress(event: Event) {
    if (!(event instanceof CustomEvent)) return
    const value: unknown = event.detail
    if (!value || typeof value !== 'object' || !('bookId' in value) || !('index' in value) || !('pos' in value)) return
    if (value.bookId !== d.bookId.value || typeof value.index !== 'number' || typeof value.pos !== 'number') return
    if (!Number.isInteger(value.index) || value.index < 0 || value.index >= d.chapters.value.length || !Number.isFinite(value.pos) || value.pos < 0 || value.pos > 10000) return
    if (!state.ready || Date.now() - lastLocalInput < 3000 || d.busy()) return
    if (value.index === curIndex.value && Math.abs(value.pos - curPos.value) < 30) return
    remoteUntil = Date.now() + 3000
    state.followedChapter = value.index
    if (saveTimer) clearTimeout(saveTimer)
    void d.goChapter(value.index, value.pos, false)
  }
  function onBeforeUnload() { saveProgress(true) }
  function onVisibilityChange() {
    if (document.visibilityState === 'hidden') saveProgress(true)
  }
  window.addEventListener('legado:progress', onRemoteProgress)
  onMounted(() => {
    window.addEventListener('beforeunload', onBeforeUnload)
    window.addEventListener('pagehide', onBeforeUnload)
    document.addEventListener('visibilitychange', onVisibilityChange)
  })
  onBeforeUnmount(() => {
    saveProgress()
    if (saveTimer) clearTimeout(saveTimer)
    window.removeEventListener('legado:progress', onRemoteProgress)
    for (const event of localInputEvents) window.removeEventListener(event, markLocalInput, { capture: true })
    window.removeEventListener('beforeunload', onBeforeUnload)
    window.removeEventListener('pagehide', onBeforeUnload)
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })
  return { curIndex, curPos, curTitle, progressPercent, state, scheduleSave, saveProgress, pullCloudProgress }
}
