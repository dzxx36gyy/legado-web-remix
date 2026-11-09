<script setup lang="ts">
import { ref } from 'vue'
import { NButton, NInput, NModal, NSpace, useMessage } from 'naive-ui'
import { subscriptionApi } from '@/api'
import { t } from '@/i18n'

const props = defineProps<{ type: 'replace' | 'dict' | 'txt-toc' }>()
const emit = defineEmits<{ imported: [] }>()
const message = useMessage()
const show = ref(false)
const url = ref('')
const loading = ref(false)
async function submit() {
  loading.value = true
  try {
    const result = await subscriptionApi.importRuleUrl(props.type, url.value.trim())
    message.success(t('已导入 {0} 条', [result.count]))
    show.value = false
    emit('imported')
  } catch (error) {
    if (!(error instanceof Error)) throw error
    message.error(error.message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <n-button size="small" @click="show = true">{{ t('网络导入') }}</n-button>
  <n-modal v-model:show="show" preset="card" :title="t('网络导入')" style="max-width: 560px">
    <n-input v-model:value="url" :aria-label="t('网络链接')" placeholder="https://example.com/rules.json" @keyup.enter="submit" />
    <template #footer>
      <n-space justify="end"><n-button type="primary" :loading="loading" :disabled="!url.trim()" @click="submit">{{ t('导入') }}</n-button></n-space>
    </template>
  </n-modal>
</template>
