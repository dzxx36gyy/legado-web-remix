import { ref, watch } from 'vue'
import type { DialogApi, MessageApi, UploadFileInfo } from 'naive-ui'
import { sourceApi, type ImportPreviewItem } from '@/api'
import type { ImportResult } from '@/api/types'
import { t } from '@/i18n'

/** 导入结果提示：有失败时用对话框列出错误，否则用轻提示 */
export function showImportResult(r: ImportResult, message: MessageApi, dialog: DialogApi) {
  const msg = t('新增 {0}，更新 {1}{2}', [r.added, r.updated, r.failed ? t('，失败 {0}', [r.failed]) : ''])
  if (r.failed) {
    dialog.warning({ title: t('导入完成'), content: msg + '\n' + r.errors.join('\n'), positiveText: t('好的') })
  } else {
    message.success(t('导入完成：{0}', [msg]))
  }
}

/**
 * 书源导入：链接预览勾选、粘贴/文件导入。
 * reload 由调用方提供，负责刷新书源列表。
 */
export function useSourceImport(options: {
  reload: () => Promise<void>
  message: MessageApi
  dialog: DialogApi
}) {
  const { reload, message, dialog } = options

  const showImport = ref(false)
  const importTab = ref('url')
  const importUrl = ref('')
  const importText = ref('')
  const importFile = ref<{ name: string; text: string } | null>(null)
  const importPublic = ref(false)
  const importing = ref(false)
  /** 链接导入：先下载并列出书源，勾选后再导入 */
  const previewItems = ref<ImportPreviewItem[] | null>(null)
  const previewSelected = ref(new Set<number>())

  watch(showImport, (v) => {
    if (!v) { previewItems.value = null; importFile.value = null }
  })

  async function doImport() {
    importing.value = true
    try {
      let r: ImportResult
      if (importTab.value === 'url' && !previewItems.value) {
        if (!importUrl.value.trim()) return message.warning(t('请输入链接'))
        const items = await sourceApi.importPreview(importUrl.value.trim())
        if (!items.length) return message.warning(t('链接里没有书源'))
        previewItems.value = items
        previewSelected.value = new Set(items.map((_, i) => i))
        return
      }
      if (importTab.value === 'url') {
        const items = previewItems.value ?? []
        const picked = items.filter((_, i) => previewSelected.value.has(i))
        if (!picked.length) return message.warning(t('请先勾选书源'))
        r = await sourceApi.importText(`[${picked.map((i) => i.json).join(',')}]`, importPublic.value)
      } else {
        const text = importFile.value?.text ?? importText.value
        if (!text.trim()) return message.warning(t('请粘贴书源 JSON'))
        r = await sourceApi.importText(text, importPublic.value)
      }
      showImportResult(r, message, dialog)
      showImport.value = false
      importText.value = ''
      await reload()
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : String(e))
    } finally {
      importing.value = false
    }
  }

  async function onFile(opts: { file: UploadFileInfo }) {
    const f = opts.file.file
    if (!f) return
    importFile.value = { name: f.name, text: await f.text() }
    importTab.value = 'text'
  }

  return {
    showImport, importTab, importUrl, importText, importFile, importPublic, importing,
    previewItems, previewSelected, doImport, onFile,
  }
}
