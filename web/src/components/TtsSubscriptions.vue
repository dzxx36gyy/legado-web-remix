<script setup lang="ts">
import { NCollapse, NCollapseItem } from 'naive-ui'
import { t } from '@/i18n'
import TtsSubscriptionList from './TtsSubscriptionList.vue'

const emit = defineEmits<{ changed: [] }>()
const props = withDefaults(defineProps<{ kind?: 'tts' | 'replace' | 'dict' | 'txtToc' }>(), { kind: 'tts' })
</script>

<template>
  <n-collapse style="margin-bottom: 16px">
    <n-collapse-item :title="props.kind === 'tts' ? t('朗读引擎订阅') : t('规则订阅')" name="tts-subscriptions">
      <tts-subscription-list :kind="props.kind" @changed="emit('changed')" />
    </n-collapse-item>
  </n-collapse>
</template>
