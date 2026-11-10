import { computed, ref, type Ref } from 'vue'
import type { DialogApi, MessageApi } from 'naive-ui'
import { sourceApi } from '@/api'
import type { SourceSummary } from '@/api/types'
import { t } from '@/i18n'

/**
 * 书源分组：筛选选项、分组统计和分组改名/删除。
 * sources 与筛选用的 group 由调用方提供，数据刷新交给 reload。
 */
export function useSourceGroups(options: {
  sources: Ref<SourceSummary[]>
  group: Ref<string | null>
  reload: () => Promise<void>
  message: MessageApi
  dialog: DialogApi
}) {
  const { sources, group, reload, message, dialog } = options

  const groups = computed(() => {
    const set = new Set<string>()
    sources.value.forEach((s) => s.bookSourceGroup?.split(/[,;，；]/).forEach((g) => g.trim() && set.add(g.trim())))
    return [...set].sort()
  })

  // ---------- 分组管理 ----------
  const showGroups = ref(false)
  const groupStats = computed(() => {
    const map = new Map<string, { total: number; enabled: number; editable: number }>()
    sources.value.forEach((s) => {
      new Set((s.bookSourceGroup || '').split(/[,;，；]/).map((g) => g.trim()).filter(Boolean)).forEach((g) => {
        const st = map.get(g) ?? { total: 0, enabled: 0, editable: 0 }
        st.total++
        if (s.enabled) st.enabled++
        if (s.canEdit) st.editable++
        map.set(g, st)
      })
    })
    return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([name, st]) => ({ name, ...st }))
  })
  const renaming = ref<string | null>(null)
  const renameInput = ref('')

  function groupChanged(r: { changed: number; skipped: number }) {
    if (r.skipped) message.warning(t('已修改 {0} 个书源，{1} 个公共书源无权修改', [r.changed, r.skipped]))
    else message.success(t('已修改 {0} 个书源', [r.changed]))
  }

  async function doRenameGroup() {
    const from = renaming.value
    const to = renameInput.value.trim()
    if (!from || !to || to === from) return
    if (/[,;，；]/.test(to)) {
      message.warning(t('分组名不能包含逗号或分号'))
      return
    }
    try {
      groupChanged(await sourceApi.renameGroup(from, to))
      renaming.value = null
      if (group.value === from) group.value = to
      await reload()
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : String(e))
    }
  }

  function deleteGroup(name: string) {
    dialog.warning({
      title: t('删除分组'),
      content: t('从书源中移除分组「{0}」？书源本身不会被删除。', [name]),
      positiveText: t('删除'),
      negativeText: t('取消'),
      onPositiveClick: async () => {
        try {
          groupChanged(await sourceApi.renameGroup(name, null))
          if (group.value === name) group.value = null
          await reload()
        } catch (e: unknown) {
          message.error(e instanceof Error ? e.message : String(e))
        }
      },
    })
  }

  return { groups, showGroups, groupStats, renaming, renameInput, doRenameGroup, deleteGroup }
}
