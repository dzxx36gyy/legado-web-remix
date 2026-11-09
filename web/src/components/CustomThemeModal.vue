<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NCheckbox, NColorPicker, NForm, NFormItem, NInput, NModal, NSpace, useDialog, useMessage } from 'naive-ui'
import { prefsApi } from '@/api'
import { customToTheme, type CustomTheme, type ReaderConfig } from '@/stores/reader'
import { parseLegadoReadConfig } from '@/utils/legadoReadConfig'
import { parseHex } from '@/utils/color'
import { t } from '@/i18n'

/** 新建 / 编辑自定义阅读主题。 */
const props = defineProps<{ show: boolean; theme: CustomTheme | null }>()
const emit = defineEmits<{ 'update:show': [boolean]; saved: [CustomTheme]; deleted: [string]; typography: [Partial<ReaderConfig>] }>()
const message = useMessage()
const dialog = useDialog()

const name = ref('')
const bg = ref('#f3ead7')
const text = ref('#3b3226')
const autoPanel = ref(true)
const panel = ref('#fbf6ea')
const autoDesk = ref(true)
const desk = ref('#e4d9c1')
const image = ref('')
const imageLoading = ref(false)

watch(
  () => props.show,
  async (v) => {
    if (!v) return
    const theme = props.theme
    image.value = ''
    if (theme?.image) {
      try { image.value = await prefsApi.get<string>(`readerBg_${theme.id}`) || '' }
      catch (error) { if (error instanceof Error) message.error(error.message); else throw error }
    }
    name.value = theme?.name ?? t('我的主题')
    bg.value = theme?.bg ?? '#f3ead7'
    text.value = theme?.text ?? '#3b3226'
    autoPanel.value = !theme?.panel
    autoDesk.value = !theme?.desk
    const derived = customToTheme({ id: '', name: '', bg: bg.value, text: text.value })
    panel.value = theme?.panel || derived.panel
    desk.value = theme?.desk || derived.desk
  },
)

// ---- 导入阅读 App 导出的阅读样式（config.json） ----
const showImport = ref(false)
const importText = ref('')
const importTypo = ref(true)

function doImport() {
  let style
  try {
    style = parseLegadoReadConfig(importText.value)
  } catch {
    return message.error(t('不是阅读 App 导出的阅读样式 JSON'))
  }
  const night = style.night
  if (style.day.bg) bg.value = style.day.bg
  else if (night?.bg) bg.value = night.bg
  if (style.day.text) text.value = style.day.text
  if (style.name) name.value = style.name
  autoPanel.value = true
  autoDesk.value = true
  if (importTypo.value) emit('typography', style.typography)
  if (!style.day.bg) message.warning(t('该样式使用背景图片，只导入了文字颜色'))
  else message.success(importTypo.value ? t('已导入颜色和排版，保存后生效') : t('已导入颜色，保存后生效'))
  importText.value = ''
  showImport.value = false
}

// 背景色变了而面板色、底色是自动时，预览跟着变
const preview = computed(() => customToTheme({
  id: '', name: '', bg: bg.value, text: text.value,
  panel: autoPanel.value ? undefined : panel.value,
  desk: autoDesk.value ? undefined : desk.value,
}))

async function pickImage(event: Event) {
  const input = event.target
  if (!(input instanceof HTMLInputElement) || !input.files?.[0]) return
  imageLoading.value = true
  try {
    const bitmap = await createImageBitmap(input.files[0])
    const scale = Math.min(1, 1920 / Math.max(bitmap.width, bitmap.height))
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(bitmap.width * scale)
    canvas.height = Math.round(bitmap.height * scale)
    const context = canvas.getContext('2d')
    if (!context) return message.error(t('图片处理失败'))
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    bitmap.close()
    const encoded = canvas.toDataURL('image/jpeg', 0.8)
    if (encoded.length > 2796200) return message.error(t('图片不能超过 2MB'))
    image.value = encoded
  } catch (error) {
    if (!(error instanceof Error)) throw error
    message.error(t('图片处理失败'))
  } finally {
    imageLoading.value = false
  }
}

async function save() {
  if (!parseHex(bg.value) || !parseHex(text.value)) return message.warning(t('请填写有效的颜色（如 #f3ead7）'))
  const id = props.theme?.id ?? Date.now().toString(36)
  try { await prefsApi.set(`readerBg_${id}`, image.value) }
  catch (error) { if (error instanceof Error) return message.error(error.message); throw error }
  emit('saved', {
    id,
    image: image.value ? Date.now().toString(36) : undefined,
    name: name.value.trim() || t('我的主题'),
    bg: bg.value,
    text: text.value,
    panel: autoPanel.value ? undefined : panel.value,
    desk: autoDesk.value ? undefined : desk.value,
  })
  emit('update:show', false)
}

function remove() {
  const theme = props.theme
  if (!theme) return
  dialog.warning({
    title: t('删除主题'),
    content: t('确定删除「{0}」？', [theme.name]),
    positiveText: t('删除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      await prefsApi.set(`readerBg_${theme.id}`, '')
      emit('deleted', theme.id)
      emit('update:show', false)
    },
  })
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="theme ? t('编辑主题') : t('自定义主题')"
    style="width: 460px; max-width: 94vw"
    :auto-focus="false"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="preview" :style="{ backgroundColor: preview.bg, color: preview.text, backgroundImage: image ? `url(${image})` : undefined, backgroundSize: 'cover' }">
      <div class="preview-title">{{ t('第一章 预览') }}</div>
      <div>　　{{ t('这是正文的预览，可以看看文字在这个背景上是否清楚。') }}</div>
    </div>
    <n-form label-placement="left" label-width="84" size="small" style="margin-top: 12px">
      <n-form-item :label="t('名称')">
        <n-input v-model:value="name" maxlength="12" />
      </n-form-item>
      <n-form-item :label="t('背景色')">
        <n-color-picker v-model:value="bg" :modes="['hex']" :show-alpha="false" />
      </n-form-item>
      <n-form-item :label="t('背景图片')">
        <n-space vertical>
          <input type="file" accept="image/*" :aria-label="t('背景图片')" :disabled="imageLoading" @change="pickImage" />
          <n-button v-if="image" size="small" @click="image = ''">{{ t('移除图片') }}</n-button>
        </n-space>
      </n-form-item>
      <n-form-item :label="t('文字色')">
        <n-color-picker v-model:value="text" :modes="['hex']" :show-alpha="false" />
      </n-form-item>
      <n-form-item :label="t('菜单面板')">
        <n-space align="center" :wrap="false" style="width: 100%">
          <n-checkbox v-model:checked="autoPanel">{{ t('自动') }}</n-checkbox>
          <n-color-picker v-if="!autoPanel" v-model:value="panel" :modes="['hex']" :show-alpha="false" style="width: 180px" />
        </n-space>
      </n-form-item>
      <n-form-item :label="t('外部底色')">
        <n-space align="center" :wrap="false" style="width: 100%">
          <n-checkbox v-model:checked="autoDesk">{{ t('自动') }}</n-checkbox>
          <n-color-picker v-if="!autoDesk" v-model:value="desk" :modes="['hex']" :show-alpha="false" style="width: 180px" />
        </n-space>
      </n-form-item>
    </n-form>
    <div v-if="showImport" class="import">
      <n-input
        v-model:value="importText"
        type="textarea"
        :autosize="{ minRows: 4, maxRows: 10 }"
        :placeholder="t('粘贴阅读 App「阅读界面 → 界面 → 样式 → 导出」得到的 config.json 内容')"
      />
      <n-space justify="space-between" align="center" style="margin-top: 8px">
        <n-checkbox v-model:checked="importTypo">{{ t('同时导入排版（字号、行距、段距、缩进、标题、边距）') }}</n-checkbox>
        <n-button size="small" type="primary" :disabled="!importText.trim()" @click="doImport">{{ t('导入') }}</n-button>
      </n-space>
    </div>
    <template #footer>
      <n-space justify="space-between">
        <n-space>
          <n-button v-if="theme" type="error" ghost @click="remove">{{ t('删除') }}</n-button>
          <n-button @click="showImport = !showImport">{{ t('导入 App 样式') }}</n-button>
        </n-space>
        <n-space>
          <n-button @click="emit('update:show', false)">{{ t('取消') }}</n-button>
          <n-button type="primary" :disabled="imageLoading" @click="save">{{ t('保存') }}</n-button>
        </n-space>
      </n-space>
    </template>
  </n-modal>
</template>

<style scoped>
.preview {
  padding: 14px 18px;
  border-radius: 8px;
  box-shadow: 0 0 0 1px rgba(128, 128, 128, 0.25);
  font-size: 15px;
  line-height: 1.8;
}

.import {
  margin-top: 4px;
}

.preview-title {
  margin-bottom: 4px;
  font-size: 17px;
  font-weight: 600;
}
</style>
