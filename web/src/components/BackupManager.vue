<script setup lang="ts">
/**
 * 备份与恢复：格式与阅读 App 的备份 zip 相同，可以互相恢复；
 * WebDAV 使用与 App 相同的目录和文件名，同一个目录里的备份两边都能看到
 */
import { h, onMounted, reactive, ref } from 'vue'
import {
  NAlert, NButton, NCheckbox, NCollapse, NCollapseItem, NDataTable, NForm, NFormItem, NInput, NModal, NSpace,
  NSwitch, NSelect, useDialog, useMessage, type DataTableColumns,
} from 'naive-ui'
import {
  backupApi, webdavApi, type BackupImportSummary, type BackupParts, type WebDavConfig, type WebDavFile,
} from '@/api'
import { useAuthStore } from '@/stores/auth'
import { formatDate, formatTime } from '@/utils/media'
import { t } from '@/i18n'
import HelpTip from '@/components/HelpTip.vue'

const message = useMessage()
const dialog = useDialog()
const auth = useAuthStore()

const parts = reactive<BackupParts>({ books: true, sources: true, rules: true, subs: true, integrations: false, backupPassword: '' })
const exporting = ref(false)
const importing = ref(false)
const summary = ref<BackupImportSummary | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const labels: { key: 'books' | 'sources' | 'rules' | 'subs'; label: string }[] = [
  { key: 'books', label: t('书架和阅读进度') },
  { key: 'sources', label: t('书源') },
  { key: 'rules', label: t('规则（替换、字典、TXT 目录）') },
  { key: 'subs', label: t('书源订阅') },
]

const nothingSelected = () => ![parts.books, parts.sources, parts.rules, parts.subs, parts.integrations].some(Boolean)
const selectedText = () => [...labels.filter((l) => parts[l.key]).map((l) => l.label), ...(parts.integrations ? [t('朗读与 AI 及全部订阅（加密）')] : [])].join('、')

// ---------------- 本地文件 ----------------

async function doExport() {
  if (nothingSelected()) return message.warning(t('请至少选择一项'))
  exporting.value = true
  try {
    const { name, blob } = await backupApi.export({ ...parts })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = name
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 10_000)
  } catch (e: any) {
    message.error(e.message)
  } finally {
    exporting.value = false
  }
}

function pickFile() {
  if (nothingSelected()) return message.warning(t('请至少选择一项'))
  fileInput.value?.click()
}

function onFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  confirmRestore(file.name, () => backupApi.import(file, { ...parts }))
}

function confirmRestore(name: string, run: () => Promise<BackupImportSummary>) {
  dialog.info({
    title: t('从备份恢复'),
    content: t('从「{0}」恢复：{1}。已有的书会按最后阅读时间合并进度，不会删除现有数据。', [name, selectedText()]) + (parts.sources ? ' ' + t('还原只影响私有书源，不会修改公共列表。') : ''),
    positiveText: t('恢复'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      importing.value = true
      summary.value = null
      try {
        summary.value = await run()
        showCloud.value = false
        if (summary.value.committed) message.success(t('恢复完成'))
        else message.error(t('还原未完成，未保留本次数据库修改'))
      } catch (e: any) {
        message.error(e.message)
      } finally {
        importing.value = false
      }
    },
  })
}

// ---------------- WebDAV ----------------

const dav = ref<WebDavConfig | null>(null)
const davForm = reactive({ url: '', account: '', password: '', dir: 'legado', deviceName: '', autoBackup: false, syncProgress: false })
const davSaving = ref(false)
const davBackingUp = ref(false)
const showCloud = ref(false)
const cloudFiles = ref<WebDavFile[]>([])
const cloudLoading = ref(false)
const davAction = ref<'backup' | 'restore' | null>(null)
const restoreName = ref('')
const restorePreview = ref<Awaited<ReturnType<typeof webdavApi.preview>> | null>(null)
const selectedBooks = ref<string[]>([])
const selectedSources = ref<string[]>([])
function restoreSelected() {
  const name = restoreName.value
  const selection = { bookUrls: [...selectedBooks.value], sourceUrls: [...selectedSources.value] }
  const selectedParts = { ...parts }
  confirmRestore(name, async () => {
    const result = await webdavApi.restore(name, selectedParts, selection)
    if (result.committed) restorePreview.value = null
    return result
  })
}
async function previewRestore(name: string) {
  cloudLoading.value = true
  try {
    restorePreview.value = await webdavApi.preview(name)
    restoreName.value = name
    selectedBooks.value = restorePreview.value.books.map((b) => b.url)
    selectedSources.value = restorePreview.value.sources.map((s) => s.url)
    showCloud.value = false
  } catch (error) { message.error(error instanceof Error ? error.message : String(error)) }
  finally { cloudLoading.value = false }
}

function fillDav(c: WebDavConfig) {
  dav.value = c
  Object.assign(davForm, { url: c.url, account: c.account, password: '', dir: c.dir, deviceName: c.deviceName, autoBackup: c.autoBackup, syncProgress: c.syncProgress })
}

onMounted(async () => {
  try {
    fillDav(await webdavApi.config())
  } catch {
    /* 离线等 */
  }
})

const davReady = () => !!dav.value?.account && !!dav.value?.hasPassword

async function saveDav() {
  davSaving.value = true
  try {
    fillDav(await webdavApi.save({ ...davForm, password: davForm.password || undefined }))
    message.success(t('连接成功，已保存'))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    davSaving.value = false
  }
}

async function toggleAuto(v: boolean) {
  davForm.autoBackup = v
  if (davReady()) await saveDav()
}

async function toggleSyncProgress(v: boolean) {
  davForm.syncProgress = v
  if (davReady()) await saveDav()
}

async function backupToDav() {
  if (nothingSelected()) return message.warning(t('请至少选择一项'))
  davBackingUp.value = true
  try {
    const r = await webdavApi.backup({ ...parts })
    message.success(t('已备份到 WebDAV：{0}', [r.name]))
    fillDav(await webdavApi.config())
  } catch (e: any) {
    message.error(e.message)
  } finally {
    davBackingUp.value = false
  }
}

async function openCloud() {
  if (nothingSelected()) return message.warning(t('请至少选择一项'))
  showCloud.value = true
  cloudLoading.value = true
  try {
    cloudFiles.value = await webdavApi.list()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    cloudLoading.value = false
  }
}

function fmtSize(n: number) {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

const cloudColumns: DataTableColumns<WebDavFile> = [
  { title: t('文件'), key: 'name', ellipsis: { tooltip: true } },
  { title: t('时间'), key: 'lastModified', width: 150, render: (f) => (f.lastModified ? formatDate(f.lastModified) : '-') },
  { title: t('大小'), key: 'size', width: 80, render: (f) => fmtSize(f.size) },
  {
    title: '',
    key: 'op',
    width: 70,
    render: (f) =>
      h(NButton, { size: 'small', onClick: () => previewRestore(f.name) }, () => t('选择还原内容')),
  },
]
</script>

<template>
  <div>
    <n-space :size="[16, 4]" style="margin-bottom: 10px">
      <n-checkbox v-for="l in labels" :key="l.key" v-model:checked="parts[l.key]">{{ l.label }}</n-checkbox>
    </n-space>
    <n-checkbox v-model:checked="parts.integrations">{{ t('朗读与 AI 及全部订阅（加密）') }}</n-checkbox>
    <div v-if="parts.integrations" style="margin: 10px 0">
      <label>{{ t('备份密码（至少 8 位）') }}</label>
      <n-input v-model:value="parts.backupPassword" type="password" :input-props="{ 'aria-label': t('备份密码（至少 8 位）') }" autocomplete="new-password" />
      <p class="muted small">{{ t('包含 API 密钥；使用独立密码加密，恢复时需输入同一密码。密码不会保存在服务器。') }}</p>
    </div>
    <n-space>
      <n-button size="small" type="primary" :loading="exporting" @click="doExport">{{ t('导出备份文件') }}</n-button>
      <n-button size="small" :loading="importing && !showCloud" @click="pickFile">{{ t('从文件恢复') }}</n-button>
      <template v-if="davReady()">
        <n-select v-model:value="davAction" :options="[{ label: t('备份到 WebDAV'), value: 'backup' }, { label: t('从 WebDAV 恢复'), value: 'restore' }]" :placeholder="t('选择 WebDAV 操作')" :aria-label="t('选择 WebDAV 操作')" style="width: 210px" />
        <n-button v-if="davAction === 'backup'" size="small" :loading="davBackingUp" @click="backupToDav">{{ t('备份到 WebDAV') }}</n-button>
        <n-button v-if="davAction === 'restore'" size="small" @click="openCloud">{{ t('从 WebDAV 恢复') }}</n-button>
      </template>
    </n-space>
    <input ref="fileInput" type="file" accept=".zip,application/zip" style="display: none" @change="onFile" />

    <n-alert v-if="summary" :type="summary.committed ? 'success' : 'error'" :bordered="false" style="margin-top: 12px" closable @close="summary = null">
      <template v-if="!summary.committed">
        <p>{{ summary.rolledBack ? t('还原失败，本次数据库修改已全部回滚。') : t('还原前校验失败，未写入任何数据。') }}</p>
        <ul><li v-for="failure in summary.failures" :key="failure.file">{{ failure.file }} · {{ failure.reason }}</li></ul>
      </template>
      <div v-else class="small">
        <div v-if="parts.books">
          {{ t('书架：新增 {0} 本，更新进度 {1} 本，保持不变 {2} 本', [summary.booksAdded, summary.booksUpdated, summary.booksSkipped]) }}<span v-if="summary.groupsAdded">{{ t('，新增分组 {0} 个', [summary.groupsAdded]) }}</span>
        </div>
        <div v-if="parts.sources">
          {{ t('书源：新增 {0}，更新 {1}', [summary.sourcesAdded, summary.sourcesUpdated]) }}<span v-if="summary.sourcesSkipped">{{ t('，跳过 {0}', [summary.sourcesSkipped]) }}</span>
        </div>
        <div v-if="parts.books && summary.bookmarksAdded">{{ t('书签：新增 {0}', [summary.bookmarksAdded]) }}</div>
        <div v-if="parts.rules">{{ t('替换规则：新增 {0}', [summary.rulesAdded]) }}</div>
        <div v-if="parts.rules && (summary.dictRulesImported || summary.tocRulesImported)">
          {{ t('字典规则：导入 {0} · TXT 目录规则：导入 {1}', [summary.dictRulesImported, summary.tocRulesImported]) }}
        </div>
        <div v-if="parts.subs">{{ t('书源订阅：新增 {0}', [summary.subsAdded]) }}</div>
        <ul v-if="summary.warnings.length" class="warn">
          <li v-for="(w, i) in summary.warnings" :key="i">{{ w }}</li>
        </ul>
      </div>
    </n-alert>

    <n-collapse style="margin-top: 14px">
      <n-collapse-item name="dav">
        <template #header>
          {{ t('WebDAV 设置') }}
          <span v-if="davReady()" class="muted small" style="margin-left: 8px">
            {{ t('已配置 · 上次备份 {0}', [dav?.lastBackup ? formatTime(dav.lastBackup) : t('从未')]) }}
          </span>
        </template>
        <n-alert v-if="dav?.lastError" type="warning" :bordered="false" style="margin-bottom: 10px">
          {{ t('上次自动备份失败：{0}', [dav.lastError]) }}
        </n-alert>
        <p class="muted small">{{ t('新备份使用唯一文件名，避免同日或多用户覆盖。共用 WebDAV 账号和目录仍可读取彼此的备份，请为不同用户设置独立目录。') }}</p>
        <n-form label-placement="left" label-width="auto" size="small" style="max-width: 520px">
          <n-form-item :label="t('服务器地址')">
            <n-input v-model:value="davForm.url" placeholder="https://dav.jianguoyun.com/dav/" />
          </n-form-item>
          <n-form-item :label="t('账号')">
            <n-input v-model:value="davForm.account" :placeholder="t('WebDAV 账号')" />
          </n-form-item>
          <n-form-item :label="t('密码')">
            <n-input
              v-model:value="davForm.password"
              type="password"
              show-password-on="click"
              :placeholder="dav?.hasPassword ? t('已保存，留空表示不修改') : t('坚果云请使用“应用密码”')"
            />
          </n-form-item>
          <n-form-item :label="t('WebDAV 目录')">
            <n-input v-model:value="davForm.dir" :placeholder="t('legado（与 App 默认一致）')" />
          </n-form-item>
          <n-form-item :label="t('设备名')">
            <n-input v-model:value="davForm.deviceName" :placeholder="t('可选，会加在备份文件名后，如 web')" />
          </n-form-item>
          <n-form-item>
            <template #label>{{ t('自动备份') }}<help-tip>{{ t('每天自动备份一次到 WebDAV') }}</help-tip></template>
            <n-switch :value="davForm.autoBackup" @update:value="toggleAuto" />
          </n-form-item>
          <n-form-item>
            <template #label>{{ t('同步阅读进度') }}<help-tip>{{ t('打开书时读取、翻页后上传云端进度，与阅读 App 的“同步阅读进度”互通（书名和作者相同即为同一本书）') }}</help-tip></template>
            <n-switch :value="davForm.syncProgress" @update:value="toggleSyncProgress" />
          </n-form-item>
          <n-form-item label=" ">
            <n-button type="primary" size="small" :loading="davSaving" @click="saveDav">{{ t('测试并保存') }}</n-button>
          </n-form-item>
        </n-form>
      </n-collapse-item>
    </n-collapse>

    <p v-if="!auth.isAdmin && !auth.me?.canAddSource" class="muted small note">
      {{ t('当前账号没有添加书源的权限，恢复时会跳过书源和订阅。') }}
    </p>

    <n-modal v-model:show="showCloud" preset="card" :title="t('WebDAV 上的备份')" style="max-width: 640px">
      <p class="muted small" style="margin-top: 0">{{ t('包括 App 和网页版上传的备份，将恢复：{0}', [selectedText()]) }}</p>
      <n-data-table :columns="cloudColumns" :data="cloudFiles" :loading="cloudLoading || importing" size="small" :max-height="400" />
    </n-modal>
    <n-modal :show="restorePreview !== null" preset="card" :title="t('选择还原内容')" style="width: 620px; max-width: 94vw" @update:show="(show: boolean) => { if (!show) restorePreview = null }">
      <div v-if="restorePreview" style="max-height: 60vh; overflow: auto">
        <p>{{ restoreName }}</p>
        <template v-if="parts.books">
          <h3>{{ t('书架和阅读进度') }}</h3>
          <n-select v-model:value="selectedBooks" multiple filterable :options="restorePreview.books.map((b) => ({ label: b.name, value: b.url }))" />
        </template>
        <template v-if="parts.sources">
          <h3>{{ t('私有书源') }}</h3>
          <n-select v-model:value="selectedSources" multiple filterable :options="restorePreview.sources.map((s) => ({ label: s.name, value: s.url }))" />
        </template>
        <p>{{ t('未选中的书籍和书源不会还原，其他类别按上方勾选项还原。') }}</p>
        <n-button :loading="importing" @click="restoreSelected">{{ t('恢复') }}</n-button>
      </div>
    </n-modal>
  </div>
</template>

<style scoped>
.small {
  font-size: 13px;
}

.warn {
  margin: 6px 0 0;
  padding-left: 18px;
}

.note {
  margin: 12px 0 0;
  line-height: 1.6;
}
</style>
