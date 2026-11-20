<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NButton, NInput, NSpace, NSpin, NSwitch, useMessage } from 'naive-ui'
import { http } from '@/api/http'
import { t } from '@/i18n'

const sites = ref('')
const syncToGlobal = ref<boolean | null>(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const message = useMessage()
async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await http.get<{ readonly sites: string; readonly syncToGlobal?: boolean | null }>('/api/browser/sites')
    sites.value = data.sites
    syncToGlobal.value = data.syncToGlobal ?? null
  }
  catch (cause: unknown) { error.value = cause instanceof Error ? cause.message : t('读取设置失败') }
  finally { loading.value = false }
}
async function save() {
  saving.value = true
  try {
    await http.put('/api/browser/sites', {
      sites: sites.value,
      ...(syncToGlobal.value === null ? {} : { syncToGlobal: syncToGlobal.value }),
    })
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
    <p v-if="syncToGlobal === null" class="muted">{{ t('这里的站点只对你生效；管理员的全局列表对所有用户生效。删除个人列表中的域名后，该站点仍可能受全局列表控制。') }}</p>
    <n-space v-else align="center">
      <span id="cloak-sync-label">{{ t('自动同步到全局 Cloak 列表') }}</span>
      <n-switch v-model:value="syncToGlobal" :disabled="saving" aria-labelledby="cloak-sync-label" />
    </n-space>
    <p v-if="syncToGlobal !== null" class="muted">{{ t('保存后，个人列表的增删会同步给所有用户。关闭仅撤回你的共享，不影响手动添加或其他管理员共享的域名。') }}</p>
    <n-input v-model:value="sites" type="textarea" :input-props="{ 'aria-label': t('我的 Cloak 站点') }" :autosize="{ minRows: 3, maxRows: 8 }" :disabled="saving" placeholder="example.com" />
    <p class="muted">{{ t('每行一个域名，或用逗号分隔。example.com 仅匹配自身，*.example.com 仅匹配子域名。') }}</p>
    <n-button :loading="saving" @click="save">{{ t('保存') }}</n-button>
  </template>
</template>
