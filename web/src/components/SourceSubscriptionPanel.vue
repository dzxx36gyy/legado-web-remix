<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  NAlert, NButton, NCheckbox, NEmpty, NIcon, NInput, NModal, NSpace, NTag,
  useDialog, useMessage,
} from 'naive-ui'
import { AddOutline, RefreshOutline } from '@vicons/ionicons5'
import { subscriptionApi } from '@/api'
import type { Subscription } from '@/api/types'
import { showImportResult } from '@/composables/useSourceImport'
import { formatTime } from '@/utils/media'
import { t } from '@/i18n'

/**
 * 书源订阅面板：列表、添加/编辑/刷新/删除，以及订阅弹窗。
 * 订阅数量通过 update:count 交给父组件显示在 tab 上；书源列表的刷新
 * 由父组件提供的 reloadSources 完成，面板只负责捕获刷新失败。
 */
const props = defineProps<{
  canAdd: boolean
  isAdmin: boolean
  reloadSources: () => Promise<void>
}>()

const emit = defineEmits<{ (e: 'update:count', count: number): void }>()

const message = useMessage()
const dialog = useDialog()

const subs = ref<Subscription[]>([])
const subsLoading = ref(false)
const subsError = ref('')
const showSub = ref(false)
const editingSub = ref<number | null>(null)
const subForm = ref({ name: '', url: '', isPublic: false, autoUpdate: true })
const refreshingSub = ref<number | null>(null)

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}

/** 刷新订阅列表；失败时抛给调用方，由调用方决定提示方式 */
async function fetchSubs() {
  subs.value = await subscriptionApi.list()
  subsError.value = ''
  emit('update:count', subs.value.length)
}

/** 带列表加载状态的刷新；失败时抛给调用方 */
async function reloadSubs() {
  subsLoading.value = true
  try {
    await fetchSubs()
  } finally {
    subsLoading.value = false
  }
}

/** 页面初次加载：失败显示在列表位置，不抛出 */
async function loadSubs() {
  subsError.value = ''
  try {
    await reloadSubs()
  } catch (error: unknown) {
    subsError.value = errorMessage(error)
  }
}

/**
 * 操作成功后的刷新。订阅列表和书源列表并行刷新，
 * Promise.allSettled 保证两个 reload 的 rejection 都被捕获，
 * 一个失败不会阻止另一个完成，也不会留下未处理的 rejection。
 */
async function reloadAfterMutation(includeSources: boolean): Promise<string | null> {
  try {
    const tasks: Promise<unknown>[] = [reloadSubs()]
    if (includeSources) tasks.push(props.reloadSources())
    const results = await Promise.allSettled(tasks)
    const failed = results.find((r): r is PromiseRejectedResult => r.status === 'rejected')
    return failed ? errorMessage(failed.reason) : null
  } catch (error: unknown) {
    return errorMessage(error)
  }
}

/** 操作失败用 error；操作已成功但刷新失败用 warning，避免把成功误报成失败 */
function warnReloadFailure(error: string | null) {
  if (error) message.warning(t('刷新失败：{0}', [error]))
}

async function addSub() {
  if (!subForm.value.url.trim()) return message.warning(t('请输入订阅链接'))
  const editing = editingSub.value !== null
  try {
    if (editingSub.value !== null) await subscriptionApi.update(editingSub.value, subForm.value)
    else await subscriptionApi.create(subForm.value)
  } catch (error: unknown) {
    message.error(errorMessage(error))
    return
  }
  showSub.value = false
  subForm.value = { name: '', url: '', isPublic: false, autoUpdate: true }
  message.success(editing ? t('已保存') : t('订阅已添加并完成首次同步'))
  warnReloadFailure(await reloadAfterMutation(true))
}

function editSub(s?: Subscription) {
  editingSub.value = s?.id ?? null
  subForm.value = s ? { name: s.name, url: s.url, isPublic: s.isPublic, autoUpdate: s.autoUpdate }
    : { name: '', url: '', isPublic: false, autoUpdate: true }
  showSub.value = true
}

async function refreshSub(s: Subscription) {
  refreshingSub.value = s.id
  try {
    showImportResult(await subscriptionApi.refresh(s.id), message, dialog)
    warnReloadFailure(await reloadAfterMutation(true))
  } catch (error: unknown) {
    message.error(errorMessage(error))
  } finally {
    refreshingSub.value = null
  }
}

function removeSub(s: Subscription) {
  dialog.warning({
    title: t('删除订阅'),
    content: t('确定删除订阅「{0}」吗？', [s.name]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      try {
        await subscriptionApi.remove(s.id)
      } catch (error: unknown) {
        message.error(errorMessage(error))
        return
      }
      warnReloadFailure(await reloadAfterMutation(false))
    },
  })
}

onMounted(loadSubs)
</script>

<template>
  <div class="toolbar">
    <span class="muted small">{{ t('书源订阅会每天自动同步一次，也可以手动更新。') }}</span>
    <div style="flex: 1" />
    <n-button size="small" type="primary" :disabled="!canAdd" @click="editSub()">
      <template #icon><n-icon :component="AddOutline" /></template>{{ t('添加订阅') }}
    </n-button>
  </div>
  <p v-if="!canAdd" class="muted small">{{ t('需要管理员授予添加书源权限') }}</p>
  <p v-if="subsLoading" class="muted small" role="status">{{ t('正在加载订阅…') }}</p>
  <n-alert v-else-if="subsError" type="error" :bordered="false">
    {{ t('加载订阅失败：{0}', [subsError]) }}
    <n-button size="small" @click="loadSubs">{{ t('刷新') }}</n-button>
  </n-alert>
  <n-empty v-else-if="!subs.length" :description="t('暂无订阅，请添加订阅链接。')" style="padding: 40px 0" />
  <div v-for="s in subs" :key="s.id" class="sub card">
    <div class="sub-main">
      <div>
        <b>{{ s.name }}</b>
        <n-tag v-if="s.isPublic" size="tiny" type="info" :bordered="false" style="margin-left: 6px">{{ t('公共') }}</n-tag>
        <n-tag v-if="s.autoUpdate" size="tiny" :bordered="false" style="margin-left: 6px">{{ t('自动更新') }}</n-tag>
      </div>
      <div class="muted small ellipsis">{{ s.url }}</div>
      <div class="muted small">{{ formatTime(s.lastUpdateAt) }} · {{ s.lastResult || t('未同步') }}</div>
    </div>
    <n-space v-if="s.owned || isAdmin">
      <n-button size="small" @click="editSub(s)">{{ t('编辑') }}</n-button>
      <n-button size="small" :loading="refreshingSub === s.id" @click="refreshSub(s)">
        <template #icon><n-icon :component="RefreshOutline" /></template>{{ t('刷新') }}
      </n-button>
      <n-button size="small" type="error" ghost @click="removeSub(s)">{{ t('删除') }}</n-button>
    </n-space>
  </div>

  <n-modal v-model:show="showSub" preset="card" :title="editingSub !== null ? t('编辑订阅') : t('添加订阅')" style="max-width: 480px">
    <n-space vertical>
      <n-input v-model:value="subForm.name" :placeholder="t('名称（可选）')" />
      <n-input v-model:value="subForm.url" placeholder="https://example.com/bookSource.json" :input-props="{ 'aria-label': t('订阅链接') }" />
      <p class="muted small">{{ t('订阅链接需返回书源 JSON；开启自动更新后每天刷新一次。') }}</p>
      <n-checkbox v-model:checked="subForm.autoUpdate">{{ t('自动更新') }}</n-checkbox>
      <n-checkbox v-if="isAdmin" v-model:checked="subForm.isPublic">{{ t('导入为公共书源') }}</n-checkbox>
    </n-space>
    <template #footer>
      <n-space justify="end"><n-button type="primary" @click="addSub">{{ t('保存') }}</n-button></n-space>
    </template>
  </n-modal>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin: 8px 0 12px;
}

.small {
  font-size: 12.5px;
}

.sub {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  margin-bottom: 10px;
}

.sub-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
</style>
