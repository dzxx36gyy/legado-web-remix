<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NColorPicker, NForm, NFormItem, NInput, NModal, NRadioButton, NRadioGroup, NSpace, NSwitch, useDialog, useMessage } from 'naive-ui'
import { useUiStore } from '@/stores/ui'
import { appThemeKey, appThemeVars, exportAppTheme, parseAppThemes, presetAppThemes, type AppTheme } from '@/utils/appTheme'
import { t } from '@/i18n'

/** 界面主题：日间 / 夜间各选一个，可新建、编辑、导入阅读 App 分享的主题 JSON */
const ui = useUiStore()
const message = useMessage()
const dialog = useDialog()

const dayThemes = computed(() => ui.allAppThemes.filter((x) => !x.isNightTheme))
const nightThemes = computed(() => ui.allAppThemes.filter((x) => x.isNightTheme))
const isCustom = (x: AppTheme) => !x.id
const isActive = (x: AppTheme) => (x.isNightTheme ? ui.nightThemeName : ui.dayThemeName) === appThemeKey(x)

function swatch(x: AppTheme) {
  const v = appThemeVars(x)
  return { background: v.bg, color: v.text, '--sw-bar': v.bar, '--sw-accent': v.accent, '--sw-nav': v.nav, '--sw-on-accent': v.onAccent }
}

// ---------------- 编辑 ----------------

const editing = ref(false)
const editingName = ref<string | null>(null)
const form = ref<AppTheme>({ ...presetAppThemes[0] })

function openNew() {
  const base = ui.activeAppTheme
  editingName.value = null
  form.value = { ...base, themeName: t('我的主题') }
  editing.value = true
}

function openEdit(x: AppTheme) {
  editingName.value = x.themeName
  form.value = { ...x }
  editing.value = true
}

function onPick(x: AppTheme) {
  if (isActive(x) && isCustom(x)) return openEdit(x)
  ui.applyAppTheme(x)
}

function saveForm() {
  if (!form.value.themeName.trim()) return message.warning(t('请填写名称'))
  const saved = ui.saveCustomAppTheme({ ...form.value, themeName: form.value.themeName.trim() }, editingName.value ?? undefined)
  ui.applyAppTheme(saved)
  editing.value = false
}

function removeEditing() {
  const name = editingName.value
  if (!name) return
  dialog.warning({
    title: t('删除主题'),
    content: t('确定删除「{0}」？', [name]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: () => {
      ui.removeCustomAppTheme(name)
      editing.value = false
    },
  })
}

async function copyExport() {
  try {
    await navigator.clipboard.writeText(exportAppTheme(form.value))
    message.success(t('已复制，可在阅读 App 的主题设置中导入'))
  } catch {
    message.error(t('复制失败，浏览器不允许访问剪贴板'))
  }
}

// ---------------- 导入 ----------------

const importing = ref(false)
const importText = ref('')

function doImport() {
  let list: AppTheme[]
  try {
    list = parseAppThemes(importText.value)
  } catch {
    return message.error(t('不是有效的 JSON'))
  }
  if (!list.length) return message.error(t('没有找到主题（需要 primaryColor、accentColor、backgroundColor）'))
  let last: AppTheme | null = null
  for (const x of list) last = ui.saveCustomAppTheme(x)
  if (last) ui.applyAppTheme(last)
  message.success(t('已导入 {0} 个主题', [list.length]))
  importText.value = ''
  importing.value = false
}

const colorFields = [
  { key: 'primaryColor', label: '主色调' },
  { key: 'accentColor', label: '强调色' },
  { key: 'backgroundColor', label: '背景色' },
  { key: 'bottomBackground', label: '底栏颜色' },
] as const
</script>

<template>
  <div class="pref-row">
    <span class="pref-label">{{ t('模式') }}</span>
    <n-radio-group v-model:value="ui.themeMode" size="small">
      <n-radio-button value="auto">{{ t('跟随系统') }}</n-radio-button>
      <n-radio-button value="light">{{ t('日间') }}</n-radio-button>
      <n-radio-button value="dark">{{ t('夜间') }}</n-radio-button>
    </n-radio-group>
  </div>
  <div v-for="group in [{ label: t('日间主题'), list: dayThemes }, { label: t('夜间主题'), list: nightThemes }]" :key="group.label" class="pref-row top">
    <span class="pref-label">{{ group.label }}</span>
    <div class="swatches">
      <button
        v-for="x in group.list"
        :key="appThemeKey(x)"
        class="swatch"
        :class="{ active: isActive(x) }"
        :style="swatch(x)"
        :title="isActive(x) && isCustom(x) ? t('再点一次编辑') : x.themeName"
        :data-theme-name="x.themeName"
        @click="onPick(x)"
      >
        <span class="sw-bar" /><span class="sw-name">{{ x.themeName }}</span><span class="sw-nav" />
      </button>
    </div>
  </div>
  <div class="pref-row">
    <span class="pref-label" />
    <n-space size="small">
      <n-button size="small" @click="openNew">{{ t('＋ 自定义') }}</n-button>
      <n-button size="small" @click="importing = true">{{ t('导入主题') }}</n-button>
    </n-space>
  </div>

  <n-modal
    v-model:show="editing"
    preset="card"
    :title="editingName ? t('编辑主题') : t('自定义主题')"
    style="width: 460px; max-width: 94vw"
    :auto-focus="false"
  >
    <div class="preview" :style="swatch(form)">
      <div class="pv-side" />
      <div class="pv-main">
        <div class="pv-card">
          <div>{{ t('书架') }}</div>
          <div class="pv-muted">{{ t('这是界面颜色的预览') }}</div>
          <span class="pv-btn">{{ t('按钮') }}</span>
        </div>
      </div>
      <div class="pv-nav" />
    </div>
    <n-form label-placement="left" label-width="84" size="small" style="margin-top: 12px">
      <n-form-item :label="t('名称')">
        <n-input v-model:value="form.themeName" maxlength="20" />
      </n-form-item>
      <n-form-item :label="t('夜间主题')">
        <n-switch v-model:value="form.isNightTheme" />
      </n-form-item>
      <n-form-item v-for="f in colorFields" :key="f.key" :label="t(f.label)">
        <n-color-picker v-model:value="form[f.key]" :modes="['hex']" :show-alpha="false" />
      </n-form-item>
    </n-form>
    <template #footer>
      <n-space justify="space-between">
        <n-space>
          <n-button v-if="editingName" type="error" ghost @click="removeEditing">{{ t('删除') }}</n-button>
          <n-button @click="copyExport">{{ t('复制 JSON') }}</n-button>
        </n-space>
        <n-space>
          <n-button @click="editing = false">{{ t('取消') }}</n-button>
          <n-button type="primary" @click="saveForm">{{ t('保存') }}</n-button>
        </n-space>
      </n-space>
    </template>
  </n-modal>

  <n-modal v-model:show="importing" preset="card" :title="t('导入主题')" style="width: 520px; max-width: 94vw">
    <n-input
      v-model:value="importText"
      type="textarea"
      :autosize="{ minRows: 6, maxRows: 14 }"
      placeholder='{"themeName": "..."}'
    />
    <p class="hint">{{ t('粘贴阅读 App「主题设置 → 分享主题」得到的 JSON，可以是单个主题或主题数组') }}</p>
    <template #footer>
      <n-space justify="end">
        <n-button @click="importing = false">{{ t('取消') }}</n-button>
        <n-button type="primary" :disabled="!importText.trim()" @click="doImport">{{ t('导入') }}</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<style scoped>
.pref-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.pref-row.top {
  align-items: flex-start;
}

.pref-row.top .pref-label {
  padding-top: 8px;
}

.pref-label {
  min-width: 4em;
}

.swatches {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.swatch {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 84px;
  height: 58px;
  padding: 0;
  overflow: hidden;
  border: 2px solid transparent;
  border-radius: 8px;
  box-shadow: 0 0 0 1px rgba(128, 128, 128, 0.35);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.swatch.active {
  border-color: var(--app-accent);
}

.sw-bar {
  height: 12px;
  background: var(--sw-bar);
}

.sw-name {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sw-nav {
  height: 8px;
  background: var(--sw-nav);
  border-top: 2px solid var(--sw-accent);
}

.preview {
  display: grid;
  grid-template-columns: 56px 1fr;
  grid-template-rows: 1fr 16px;
  height: 120px;
  overflow: hidden;
  border-radius: 8px;
  box-shadow: 0 0 0 1px rgba(128, 128, 128, 0.3);
}

.pv-side {
  grid-row: 1 / 3;
  background: var(--sw-bar);
}

.pv-main {
  padding: 12px;
}

.pv-card {
  padding: 8px 12px;
  border-radius: 6px;
  background: color-mix(in srgb, currentColor 6%, transparent);
  font-size: 13px;
}

.pv-muted {
  opacity: 0.6;
  font-size: 12px;
}

.pv-btn {
  display: inline-block;
  margin-top: 6px;
  padding: 2px 10px;
  border-radius: 4px;
  background: var(--sw-accent);
  color: var(--sw-on-accent);
  font-size: 12px;
}

.pv-nav {
  background: var(--sw-nav);
}

.hint {
  margin: 0 0 8px;
  font-size: 13px;
  opacity: 0.75;
}
</style>
