<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NButton, NInput, NSpin, useMessage } from 'naive-ui'
import { http } from '@/api/http'
import { t } from '@/i18n'

const sites = ref('')
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const message = useMessage()
async function load() {
  loading.value = true
  error.value = ''
  try { sites.value = (await http.get<{ sites: string }>('/api/browser/sites')).sites }
  catch (cause: unknown) { error.value = cause instanceof Error ? cause.message : t('读取设置失败') }
  finally { loading.value = false }
}
async function save() {
  saving.value = true
  try {
    await http.put('/api/browser/sites', { sites: sites.value })
    message.success(t('已保存'))
  } catch (cause: unknown) { message.error(cause instanceof Error ? cause.message : t('保存失败')) }
  finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <n-spin v-if="loading" size="small" />
  <template v-else-if="error"><p role="alert">{{ error }}</p><n-button @click="load">{{ t('重试') }}</n-button></template>
  <template v-else>
    <p class="muted">{{ t('这里的站点只对你生效；管理员的全局列表对所有用户生效。删除个人列表中的域名后，该站点仍可能受全局列表控制。') }}</p>
    <n-input v-model:value="sites" type="textarea" :aria-label="t('我的 Cloak 站点')" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="example.com" />
    <p class="muted">{{ t('每行一个域名，或用逗号分隔。example.com 仅匹配自身，*.example.com 仅匹配子域名。') }}</p>
    <n-button :loading="saving" @click="save">{{ t('保存') }}</n-button>
  </template>
</template>
