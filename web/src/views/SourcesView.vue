<script setup lang="ts">
import { useIsMobile } from '@/composables/useIsMobile'
import { useSourceGroups } from '@/composables/useSourceGroups'
import { useSourceImport } from '@/composables/useSourceImport'
import { mobileColumns, type RowAction } from '@/utils/mobileTable'
import { computed, h, onMounted, reactive, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NButton, NDataTable, NDropdown, NIcon, NInput, NModal, NSelect, NSpace, NSwitch, NTabs, NTabPane, NTag,
  NCheckbox, NUpload, NUploadDragger, NAlert, NEmpty, NTooltip, NRadioGroup, NRadioButton,
  useDialog, useMessage, type DataTableColumns,
} from 'naive-ui'
import {
  AddOutline, CloudDownloadOutline, CloudUploadOutline, SearchOutline, KeyOutline, CreateOutline, TrashOutline, SparklesOutline,
  ChevronDownOutline, EllipsisHorizontal,
} from '@vicons/ionicons5'
import SourceConfigModal from '@/components/SourceConfigModal.vue'
import SourceCheckModal from '@/components/SourceCheckModal.vue'
import AiRepairModal from '@/components/AiRepairModal.vue'
import SourceImportPreview from '@/components/SourceImportPreview.vue'
import SourceSubscriptionPanel from '@/components/SourceSubscriptionPanel.vue'
import { sourceApi } from '@/api'
import type { SourceSummary } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { getToken } from '@/api/http'
import { locale } from '@/i18n'
import { downloadText } from '@/utils/media'
import { t } from '@/i18n'

defineOptions({ name: 'SourcesView' })

const router = useRouter()
const message = useMessage()
const dialog = useDialog()
const auth = useAuthStore()

const route = useRoute()
const tab = ref(route.query.tab === 'subs' ? 'subs' : 'sources')
/** tab 与 URL 双向同步：切换 tab 用 replace 写回 ?tab=subs，历史里不额外增加记录 */
watch(tab, (value) => {
  const current = route.query.tab === 'subs' ? 'subs' : 'sources'
  if (current === value) return
  const query = { ...route.query }
  if (value === 'subs') query.tab = 'subs'
  else delete query.tab
  router.replace({ query }).catch(() => {})
})
watch(() => route.query.tab, (value) => {
  const next = value === 'subs' ? 'subs' : 'sources'
  if (tab.value !== next) tab.value = next
})
const showAiCreate = ref(false)
const showRepair = ref(false)
const repairId = ref<number | null>(null)
const sources = ref<SourceSummary[]>([])
const loading = ref(false)
const keyword = ref('')
const group = ref<string | null>(null)
const status = ref<string | null>(null)
const checked = ref<number[]>([])
const scope = ref<'private' | 'public'>('private')
watch(scope, () => { checked.value = [] })
const subCount = ref(0)
async function publish(mode: 'incremental' | 'overwrite') {
  if (!checked.value.length) return message.warning(t('请先勾选书源'))
  try {
    const ids = [...checked.value]
    const preview = await sourceApi.syncPublic({ ids, mode })
    // 覆盖同步会从公共列表移除未选中的书源：先把要移除的列出来，确认按钮显示为危险操作
    const destructive = mode === 'overwrite' && preview.removed > 0
    const removedItems = preview.items.filter((item) => item.action === 'remove')
    const otherItems = destructive ? preview.items.filter((item) => item.action !== 'remove') : preview.items
    const itemList = (items: typeof preview.items, maxHeight: string) =>
      h('ul', { style: `max-height: ${maxHeight}; overflow: auto; padding-left: 20px; margin: 6px 0 0` }, items.map((item) =>
        h('li', { style: 'overflow-wrap: anywhere' }, `${item.action === 'add' ? t('新增') : item.action === 'update' ? t('更新') : item.action === 'remove' ? t('移除') : t('不变')} · ${item.name} · ${item.url}`)))
    ;(destructive ? dialog.error : dialog.warning)({
      title: mode === 'incremental' ? t('增量同步') : t('覆盖同步'),
      content: () => h('div', [
        destructive
          ? h(NAlert, { type: 'error', title: t('将从公共列表移除 {0} 个书源', [preview.removed]), style: 'margin-bottom: 12px' }, () => itemList(removedItems, '24vh'))
          : null,
        h('p', t('新增 {0}，更新 {1}，移除 {2}，不变 {3}。仅确认同步才会修改公共列表。', [preview.added, preview.updated, preview.removed, preview.unchanged])),
        otherItems.length ? itemList(otherItems, destructive ? '20vh' : '40vh') : null,
      ]),
      positiveText: destructive ? t('覆盖并移除 {0} 个', [preview.removed]) : t('确认同步'),
      positiveButtonProps: destructive ? { type: 'error' } : undefined,
      negativeText: t('取消'),
      onPositiveClick: async () => {
        try {
          await sourceApi.syncPublic({ ids, mode, revision: preview.revision }, false)
          await load()
          message.success(t('操作成功'))
        } catch (error) { message.error(error instanceof Error ? error.message : String(error)) }
      },
    })
  } catch (error) { message.error(error instanceof Error ? error.message : String(error)) }
}

const canAdd = computed(() => auth.me?.canAddSource ?? false)

/** 只负责取数据；失败时抛给调用方，订阅面板可据此区分“操作成功但刷新失败” */
async function reloadSources() {
  loading.value = true
  try {
    sources.value = await sourceApi.list(true)
  } finally {
    loading.value = false
  }
}

async function load() {
  try {
    await reloadSources()
  } catch (e: any) {
    message.error(e.message)
  }
}
onMounted(() => {
  load()
})

const {
  groups, showGroups, groupStats, renaming, renameInput, doRenameGroup, deleteGroup,
} = useSourceGroups({ sources, group, reload: load, message, dialog })

const {
  showImport, importTab, importUrl, importText, importFile, importing, previewItems, previewSelected, doImport, onFile,
} = useSourceImport({ reload: load, message, dialog })

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return sources.value.filter((s) => {
    if (auth.isAdmin && (scope.value === 'public' ? !s.isPublic : !s.owned)) return false
    if (k && !s.bookSourceName.toLowerCase().includes(k) && !s.bookSourceUrl.toLowerCase().includes(k)
      && !(s.bookSourceGroup || '').toLowerCase().includes(k)) return false
    if (group.value && !(s.bookSourceGroup || '').split(/[,;，；]/).map((g) => g.trim()).includes(group.value)) return false
    if (status.value === 'enabled' && !s.enabled) return false
    if (status.value === 'disabled' && s.enabled) return false
    if (status.value === 'login' && !s.hasLogin) return false
    if (status.value === 'explore' && !s.hasExplore) return false
    if (status.value === 'mine' && !s.owned) return false
    if (status.value === 'public' && !s.isPublic) return false
    return true
  })
})

/**
 * 分页：自己保存页码和每页条数。naive-ui 的表格在 pagination 里写了 pageSize 时由外部控制，
 * 不处理切换事件的话，选择“每页 N 条”不会生效
 */
const PAGE_SIZE_KEY = 'legado_sources_page_size'
const pageSizes = [20, 50, 100]
const pagination = reactive({
  page: 1,
  pageSize: pageSizes.includes(Number(localStorage.getItem(PAGE_SIZE_KEY))) ? Number(localStorage.getItem(PAGE_SIZE_KEY)) : 50,
  showSizePicker: true,
  pageSizes,
  onChange: (page: number) => {
    pagination.page = page
  },
  onUpdatePageSize: (size: number) => {
    pagination.pageSize = size
    pagination.page = 1
    localStorage.setItem(PAGE_SIZE_KEY, String(size))
  },
})
// 筛选条件或书源范围变化后回到第一页（否则可能停在不存在的页上）
watch([keyword, group, status, scope], () => {
  pagination.page = 1
})

async function toggle(s: SourceSummary, v: boolean) {
  try {
    await sourceApi.batch([s.id], v ? 'enable' : 'disable')
    s.enabled = v
  } catch (e: any) {
    message.error(e.message)
  }
}

const configId = ref<number | null>(null)
const showConfig = ref(false)
function openConfig(s: SourceSummary) {
  configId.value = s.id
  showConfig.value = true
}

function openRepair(s: SourceSummary) {
  repairId.value = s.id
  showRepair.value = true
}

function remove(ids: number[]) {
  dialog.warning({
    title: t('删除书源'),
    content: t('确定删除选中的 {0} 个书源？', [ids.length]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      try {
        await sourceApi.batch(ids, 'delete')
        checked.value = []
        await load()
      } catch (e: any) {
        message.error(e.message)
      }
    },
  })
}

interface SourceAction extends RowAction {
  /** 桌面工具提示；手机菜单用 label（AI 检测的置灰说明两边文案不同） */
  tooltip?: string
  icon: Component
}

/**
 * 每行的可执行操作只生成一份描述，桌面列和手机菜单共用。
 * 权限判断只写在这里一处，避免两个端各写一套后逐渐不一致。
 */
function sourceActions(s: SourceSummary): SourceAction[] {
  const actions: SourceAction[] = [
    {
      key: 'edit',
      label: s.canEdit ? t('编辑 / 调试') : t('查看 / 调试'),
      tooltip: s.canEdit ? t('编辑 / 调试') : t('查看 / 调试'),
      icon: CreateOutline,
      onSelect: () => router.push({ name: 'source-edit', params: { id: s.id } }),
    },
    {
      key: 'config',
      label: t('登录 / 变量'),
      tooltip: t('登录 / 变量'),
      icon: KeyOutline,
      onSelect: () => openConfig(s),
    },
  ]
  if ((s.canEdit || !canAdd.value) && (!auth.isAdmin || scope.value === 'private')) {
    actions.push({
      key: 'repair',
      label: canAdd.value ? t('AI 检测') : t('AI 检测（需要管理员授予添加书源权限）'),
      tooltip: canAdd.value ? t('AI 检测') : t('需要管理员授予添加书源权限'),
      icon: SparklesOutline,
      disabled: !s.canEdit || !canAdd.value,
      onSelect: () => openRepair(s),
    })
  }
  if (s.canEdit) {
    actions.push({ key: 'delete', label: t('删除'), icon: TrashOutline, danger: true, onSelect: () => remove([s.id]) })
  }
  return actions
}

const typeNames = [t('文本'), t('音频'), t('图片'), t('文件')]

/** 书源来自订阅时显示订阅名（可能多个），否则显示添加者 */
function ownerTags(s: SourceSummary) {
  if (s.subscriptionNames?.length) {
    return s.subscriptionNames.map((name) => h(NTag, { size: 'tiny', type: 'success', bordered: false }, () => name))
  }
  return [h(NTag, { size: 'tiny', bordered: false }, () => s.ownerUsername)]
}

const isMobile = useIsMobile()
/** 手机列表没有排序，当前页就是筛选结果按页码切出的一段 */
const pageRows = computed(() => {
  const start = (pagination.page - 1) * pagination.pageSize
  return filtered.value.slice(start, start + pagination.pageSize)
})
const pageAllChecked = computed(() => pageRows.value.length > 0 && pageRows.value.every((s) => checked.value.includes(s.id)))
function checkRows(rows: SourceSummary[], v: boolean) {
  const ids = rows.map((s) => s.id)
  checked.value = v ? [...new Set([...checked.value, ...ids])] : checked.value.filter((id) => !ids.includes(id))
}
const selectionOptions = computed(() => [
  { label: t('全选本页'), key: 'page', onSelect: (rows: SourceSummary[]) => checkRows(rows, true) },
  { label: t('全选全部（{0}）', [filtered.value.length]), key: 'all', onSelect: () => { checked.value = filtered.value.map((s) => s.id) } },
  { label: t('取消全部选择'), key: 'none', onSelect: () => { checked.value = [] } },
])
function onMobileSelect(key: string) {
  selectionOptions.value.find((o) => o.key === key)?.onSelect(pageRows.value)
}

/** 手机上与 App 一样只显示多选、名称、启用和菜单 */
const mobileCols = computed(() =>
  mobileColumns<SourceSummary>({
    name: (s) => [
      h('span', s.bookSourceName),
      ...ownerTags(s),
      s.isPublic ? h(NTag, { size: 'tiny', type: 'info', bordered: false }, () => t('公共')) : null,
      s.hasLogin ? h(NTag, { size: 'tiny', type: 'warning', bordered: false }, () => t('登录')) : null,
    ],
    sub: (s) => [s.bookSourceGroup, s.respondTime < 180000 ? `${(s.respondTime / 1000).toFixed(1)}s` : null, s.bookSourceUrl].filter(Boolean).join(' · '),
    enabled: (s) => s.enabled,
    onToggle: toggle,
    actions: (s) => sourceActions(s),
  }),
)

const columns = computed<DataTableColumns<SourceSummary>>(() => isMobile.value ? mobileCols.value : [
  { type: 'selection', options: selectionOptions.value },
  {
    title: t('启用'),
    key: 'enabled',
    width: 70,
    render: (s) => h(NSwitch, { size: 'small', value: s.enabled, onUpdateValue: (v: boolean) => toggle(s, v) }),
  },
  {
    title: t('书源'),
    key: 'bookSourceName',
    minWidth: 220,
    render: (s) =>
      h('div', { class: 'src-cell' }, [
        h('div', { class: 'src-name' }, [
          h('span', s.bookSourceName),
          ...ownerTags(s),
          s.isPublic ? h(NTag, { size: 'tiny', type: 'info', bordered: false }, () => t('公共')) : null,
          s.hasLogin ? h(NTag, { size: 'tiny', type: 'warning', bordered: false }, () => t('登录')) : null,
          s.hasExplore ? h(NTag, { size: 'tiny', bordered: false }, () => t('发现')) : null,
          s.bookSourceType ? h(NTag, { size: 'tiny', bordered: false }, () => typeNames[s.bookSourceType] ?? '') : null,
        ]),
        h('div', { class: 'src-url muted ellipsis' }, s.bookSourceUrl),
      ]),
  },
  {
    title: t('分组'),
    key: 'bookSourceGroup',
    width: 140,
    ellipsis: { tooltip: true },
    render: (s) => s.bookSourceGroup || '-',
  },
  {
    title: t('响应'),
    key: 'respondTime',
    width: 80,
    sorter: (a, b) => a.respondTime - b.respondTime,
    render: (s) => (s.respondTime >= 180000 ? '-' : `${(s.respondTime / 1000).toFixed(1)}s`),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 168,
    render: (s) => {
      const actions = sourceActions(s)
      return h(NSpace, { size: 4, wrap: false }, () => actions.map((action) => {
        const button = h(NButton, {
          size: 'tiny',
          quaternary: true,
          type: action.danger ? 'error' : undefined,
          disabled: action.disabled,
          'aria-label': action.key === 'repair' ? t('AI 检测') : undefined,
          onClick: action.onSelect,
        }, { icon: () => h(NIcon, null, () => h(action.icon)) })
        // 删除按钮原来就没有 tooltip，其余操作保持原来的 tooltip 行为
        if (action.key === 'delete') return button
        return h(NTooltip, null, {
          trigger: () => action.key === 'repair'
            ? h('span', { tabindex: canAdd.value ? undefined : 0 }, [button])
            : button,
          default: () => action.tooltip ?? action.label,
        })
      }))
    },
  },
])

// ---------- 批量操作 ----------
const batchOptions = computed(() => auth.isAdmin && scope.value === 'public' ? [
  { label: t('检测书源'), key: 'check' },
  { label: t('启用'), key: 'enable' },
  { label: t('禁用'), key: 'disable' },
  { label: t('导出选中'), key: 'export' },
] : [
  { label: t('检测书源'), key: 'check' },
  { label: t('启用'), key: 'enable' },
  { label: t('禁用'), key: 'disable' },
  { label: t('启用发现'), key: 'enableExplore' },
  { label: t('禁用发现'), key: 'disableExplore' },
  { label: t('添加分组'), key: 'addGroup' },
  { label: t('移除分组'), key: 'removeGroup' },
  { label: t('导出选中'), key: 'export' },
  { label: t('删除'), key: 'delete' },
])

// 检测书源：对话框里显示进度和结果，结束后刷新列表（分组有变化）
const showCheck = ref(false)
const checkIds = ref<number[]>([])

const groupInput = ref('')
const groupAction = ref<'addGroup' | 'removeGroup' | null>(null)

async function onBatch(key: string) {
  if (!checked.value.length) return message.warning(t('请先勾选书源'))
  if (key === 'delete') return remove(checked.value)
  if (key === 'export') return exportSources(checked.value)
  if (key === 'check') {
    checkIds.value = [...checked.value]
    showCheck.value = true
    return
  }
  if (key === 'addGroup' || key === 'removeGroup') {
    groupAction.value = key
    groupInput.value = ''
    return
  }
  try {
    await sourceApi.batch(checked.value, key)
    message.success(t('操作成功'))
    await load()
  } catch (e: any) {
    message.error(e.message)
  }
}

async function doGroupAction() {
  if (!groupAction.value || !groupInput.value.trim()) return
  await sourceApi.batch(checked.value, groupAction.value, groupInput.value.trim())
  groupAction.value = null
  await load()
}

async function exportSources(ids?: number[]) {
  const res = await fetch(sourceApi.exportUrl(ids), { headers: { Authorization: `Bearer ${getToken()}`, 'Accept-Language': locale } })
  if (!res.ok) return message.error(t('导出失败'))
  downloadText(`bookSource-${new Date().toISOString().slice(0, 10)}.json`, await res.text())
}

const statusOptions = [
  { label: t('全部'), value: '' },
  { label: t('已启用'), value: 'enabled' },
  { label: t('已禁用'), value: 'disabled' },
  { label: t('需登录'), value: 'login' },
  { label: t('有发现'), value: 'explore' },
  { label: t('我的'), value: 'mine' },
  { label: t('公共'), value: 'public' },
]

// ---------- 工具栏（手机上次要操作收进“＋”和“⋯”菜单） ----------
/** 编辑、导入、分组管理只作用于私有书源（管理员看公共列表时不显示） */
const canManage = computed(() => !auth.isAdmin || scope.value === 'private')
const syncOptions = computed(() => [
  { label: t('增量同步'), key: 'incremental' },
  { label: t('覆盖同步'), key: 'overwrite' },
])
const addOptions = computed(() => [
  { label: t('新建'), key: 'new' },
  { label: t('导入'), key: 'import' },
  { label: t('AI 创建书源'), key: 'ai' },
])
function onAddMenu(key: string) {
  if (key === 'new') router.push({ name: 'source-edit' })
  else if (key === 'import') showImport.value = true
  else if (key === 'ai') showAiCreate.value = true
}
const moreOptions = computed(() => [
  ...(canManage.value ? [{ label: t('分组管理'), key: 'groups' }] : []),
  { label: t('导出'), key: 'export' },
])
function onMoreMenu(key: string) {
  if (key === 'groups') showGroups.value = true
  else if (key === 'export') exportSources()
}
</script>

<template>
  <div class="page">
    <ai-repair-modal v-model:show="showAiCreate" @saved="load" />
    <ai-repair-modal v-model:show="showRepair" :source-id="repairId ?? undefined" @saved="load" />
    <div class="page-header">
      <h2>{{ t('书源管理') }}</h2>
    </div>

    <n-alert v-if="!canAdd" type="info" :bordered="false" style="margin-bottom: 12px">
      {{ t('管理员未开放普通用户添加书源，你可以使用管理员提供的公共书源，并可按需启用/禁用、配置登录信息。') }}
    </n-alert>

    <n-tabs v-model:value="tab" type="line">
      <n-tab-pane name="sources" :tab="t('书源 ({0})', [sources.length])">
        <div v-if="auth.isAdmin" class="scope-bar">
          <n-radio-group v-model:value="scope" :size="isMobile ? 'medium' : 'small'" :aria-label="t('书源范围')">
            <n-radio-button value="private">{{ t('私有书源') }}</n-radio-button>
            <n-radio-button value="public">{{ t('公共列表') }}</n-radio-button>
          </n-radio-group>
          <n-dropdown v-if="scope === 'private'" trigger="click" :options="syncOptions" @select="(key: string) => publish(key === 'overwrite' ? 'overwrite' : 'incremental')">
            <n-button :size="isMobile ? 'medium' : 'small'" :disabled="!checked.length">
              {{ t('同步到公共') }}
              <template #icon><n-icon :component="ChevronDownOutline" /></template>
            </n-button>
          </n-dropdown>
        </div>
        <p v-if="auth.isAdmin" class="muted small">{{ t('编辑、导入和还原只影响私有书源。增量同步新增或更新选中项，覆盖同步还会移除公共列表中未选中的书源。') }}</p>
        <div class="toolbar" :class="{ 'toolbar-mobile': isMobile }">
          <n-input v-model:value="keyword" class="tb-search" :placeholder="t('搜索名称 / 地址 / 分组')" clearable size="small" style="width: 220px">
            <template #prefix><n-icon :component="SearchOutline" /></template>
          </n-input>
          <n-select v-model:value="group" class="tb-filter" :options="groups.map((g) => ({ label: g, value: g }))" :placeholder="t('分组')" clearable size="small" style="width: 130px" />
          <n-select v-model:value="status" class="tb-filter" :options="statusOptions" :placeholder="t('状态')" clearable size="small" style="width: 110px" />
          <n-button v-if="!isMobile && canManage" size="small" class="group-manage-btn" @click="showGroups = true">{{ t('分组管理') }}</n-button>
          <div v-if="!isMobile" style="flex: 1" />
          <div v-if="isMobile" class="select-all">
            <n-checkbox :checked="pageAllChecked" :indeterminate="!pageAllChecked && checked.length > 0" @update:checked="(v: boolean) => checkRows(pageRows, v)">{{ t('本页') }}</n-checkbox>
            <n-dropdown trigger="click" :options="selectionOptions.map(({ label, key }) => ({ label, key }))" @select="onMobileSelect">
              <n-button size="small" quaternary :aria-label="t('选择范围')" :title="t('选择范围')">
                <template #icon><n-icon :component="ChevronDownOutline" /></template>
              </n-button>
            </n-dropdown>
          </div>
          <n-dropdown :options="batchOptions" @select="onBatch">
            <n-button size="small" :disabled="!checked.length">{{ t('批量操作 ({0})', [checked.length]) }}</n-button>
          </n-dropdown>
          <template v-if="isMobile">
            <n-dropdown v-if="canManage" trigger="click" placement="bottom-end" :options="addOptions" :disabled="!canAdd" @select="onAddMenu">
              <n-button size="small" type="primary" :disabled="!canAdd" :aria-label="t('添加书源')" :title="t('添加书源')">
                <template #icon><n-icon :component="AddOutline" /></template>
              </n-button>
            </n-dropdown>
            <n-dropdown trigger="click" placement="bottom-end" :options="moreOptions" @select="onMoreMenu">
              <n-button size="small" :aria-label="t('更多操作')" :title="t('更多操作')">
                <template #icon><n-icon :component="EllipsisHorizontal" /></template>
              </n-button>
            </n-dropdown>
          </template>
          <template v-else>
          <n-button size="small" @click="exportSources()">
            <template #icon><n-icon :component="CloudDownloadOutline" /></template>{{ t('导出') }}
          </n-button>
          <n-button v-if="canManage" :disabled="!canAdd" size="small" @click="showImport = true">
            <template #icon><n-icon :component="CloudUploadOutline" /></template>{{ t('导入') }}
          </n-button>
          <n-button v-if="canManage" :disabled="!canAdd" size="small" @click="showAiCreate = true">{{ t('AI 创建书源') }}</n-button>
          <n-button v-if="canManage" :disabled="!canAdd" size="small" type="primary" @click="router.push({ name: 'source-edit' })">
            <template #icon><n-icon :component="AddOutline" /></template>{{ t('新建') }}
          </n-button>
          </template>
        </div>
        <p v-if="!canAdd && canManage" class="muted small">{{ t('需要管理员授予添加书源权限') }}</p>
        <n-data-table
          v-model:checked-row-keys="checked"
          :columns="columns"
          :data="filtered"
          :row-key="(r: SourceSummary) => r.id"
          :loading="loading"
          :pagination="pagination"
          size="small"
          :class="{ 'm-table': isMobile }"
          :scroll-x="isMobile ? undefined : 720"
          :table-layout="isMobile ? 'fixed' : 'auto'"
        />
      </n-tab-pane>

      <n-tab-pane name="subs" :tab="t('书源订阅 ({0})', [subCount])" display-directive="show">
        <source-subscription-panel
          :can-add="canAdd"
          :is-admin="auth.isAdmin"
          :reload-sources="reloadSources"
          @update:count="subCount = $event"
        />
      </n-tab-pane>
    </n-tabs>

    <!-- 导入 -->
    <n-modal v-model:show="showImport" preset="card" :title="t('导入书源')" style="max-width: 600px">
      <n-tabs v-model:value="importTab" type="segment">
        <n-tab-pane name="url" :tab="t('网络链接')">
          <SourceImportPreview v-if="previewItems" v-model:selected="previewSelected" :items="previewItems" />
          <template v-else>
            <n-input v-model:value="importUrl" placeholder="https://example.com/bookSource.json" @keyup.enter="doImport" />
            <p class="muted small">{{ t('支持阅读 3.0 书源 JSON（单个或数组）。下载后可以勾选要导入的书源。需要定期同步请使用“书源订阅”。') }}</p>
          </template>
        </n-tab-pane>
        <n-tab-pane name="text" :tab="t('粘贴 / 文件')">
          <n-upload :show-file-list="false" accept=".json,.txt" :custom-request="() => {}" @change="onFile">
            <n-upload-dragger style="padding: 12px">{{ t('点击或拖拽 JSON 文件到这里') }}</n-upload-dragger>
          </n-upload>
          <n-space v-if="importFile" align="center" style="margin-top: 10px; overflow-wrap: anywhere">
            <span>{{ importFile.name }}</span><n-button @click="importFile = null">{{ t('移除') }}</n-button>
          </n-space>
          <n-input v-else v-model:value="importText" type="textarea" :autosize="{ minRows: 6, maxRows: 14 }" :placeholder="t('粘贴书源 JSON')" class="mono" style="margin-top: 10px" />
        </n-tab-pane>
      </n-tabs>
      <template #footer>
        <n-space justify="space-between" align="center">
          <span />
          <n-space :size="8">
            <n-button v-if="importTab === 'url' && previewItems" :disabled="importing" @click="previewItems = null">{{ t('返回') }}</n-button>
            <n-button type="primary" :loading="importing" @click="doImport">
              {{ importTab === 'url' ? (previewItems ? t('导入选中 ({0})', [previewSelected.size]) : t('下一步')) : t('导入') }}
            </n-button>
          </n-space>
        </n-space>
      </template>
    </n-modal>

    <!-- 分组 -->
    <n-modal :show="!!groupAction" preset="dialog" :title="groupAction === 'addGroup' ? t('添加分组') : t('移除分组')"
      :positive-text="t('确定')" :negative-text="t('取消')" @positive-click="doGroupAction" @update:show="(v: boolean) => !v && (groupAction = null)">
      <n-input v-model:value="groupInput" :placeholder="t('分组名，多个用逗号分隔')" />
    </n-modal>

    <!-- 分组管理 -->
    <n-modal v-model:show="showGroups" preset="card" :title="t('分组管理')" style="max-width: 520px">
      <n-empty v-if="!groupStats.length" :description="t('暂无分组')" style="padding: 24px 0" />
      <div v-for="g in groupStats" :key="g.name" class="group-row">
        <div class="group-main">
          <n-input v-if="renaming === g.name" v-model:value="renameInput" size="small" autofocus
            @keyup.enter="doRenameGroup" @keyup.esc="renaming = null" />
          <template v-else>
            <b class="ellipsis">{{ g.name }}</b>
            <span class="muted small">{{ t('{0} 个书源，已启用 {1}', [g.total, g.enabled]) }}</span>
          </template>
        </div>
        <n-space :size="6" :wrap="false">
          <template v-if="renaming === g.name">
            <n-button size="small" type="primary" @click="doRenameGroup">{{ t('确定') }}</n-button>
            <n-button size="small" @click="renaming = null">{{ t('取消') }}</n-button>
          </template>
          <template v-else>
            <n-button size="small" @click="group = g.name; showGroups = false">{{ t('筛选') }}</n-button>
            <n-button size="small" :disabled="!g.editable" @click="renaming = g.name; renameInput = g.name">{{ t('重命名') }}</n-button>
            <n-button size="small" type="error" ghost :disabled="!g.editable" @click="deleteGroup(g.name)">{{ t('删除') }}</n-button>
          </template>
        </n-space>
      </div>
    </n-modal>

    <source-config-modal v-model:show="showConfig" :source-id="configId" />
    <source-check-modal v-model:show="showCheck" :ids="checkIds" @done="load" />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin: 8px 0 12px;
}

.scope-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

/* 手机：搜索单独一行，筛选两列，其余按钮一行 */
.toolbar-mobile .tb-search {
  width: 100% !important;
}

.toolbar-mobile .tb-filter {
  flex: 1 1 calc(50% - 4px);
  width: auto !important;
  min-width: 0;
}

.toolbar-mobile .select-all {
  display: flex;
  align-items: center;
  margin-right: auto;
}

:deep(.src-name) {
  display: flex;
  align-items: center;
  gap: 4px;
  font-weight: 500;
}

:deep(.src-url) {
  font-size: 12px;
  max-width: 360px;
}

.small {
  font-size: 12.5px;
}

.group-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--n-border-color, rgba(128, 128, 128, 0.15));
}

.group-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
</style>
