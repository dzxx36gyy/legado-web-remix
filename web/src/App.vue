<script setup lang="ts">
import { computed } from 'vue'
import {
  NConfigProvider, NDialogProvider, NMessageProvider, NNotificationProvider, NLoadingBarProvider,
  darkTheme, zhCN, dateZhCN, zhTW, dateZhTW, enUS, dateEnUS, type GlobalThemeOverrides,
} from 'naive-ui'
import { locale } from '@/i18n'
import { useUiStore } from '@/stores/ui'
import { useRouter } from 'vue-router'
import { onUnauthorized } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import AppShellHelper from '@/components/AppShellHelper.vue'
import SourceBrowserBridge from '@/components/SourceBrowserBridge.vue'

const ui = useUiStore()
const router = useRouter()
const auth = useAuthStore()

onUnauthorized(() => {
  auth.logout()
  if (router.currentRoute.value.name !== 'login') {
    router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
  }
})

// naive-ui 组件自带文字（日期选择、分页、空状态等）的语言
const naiveLocale = locale === 'en' ? enUS : locale === 'zh-TW' ? zhTW : zhCN
const naiveDateLocale = locale === 'en' ? dateEnUS : locale === 'zh-TW' ? dateZhTW : dateZhCN

const overrides = computed<GlobalThemeOverrides>(() => {
  const v = ui.appVars
  return {
    common: {
      primaryColor: v.accent,
      primaryColorHover: v.hover,
      primaryColorPressed: v.pressed,
      primaryColorSuppl: v.accent,
      baseColor: v.onAccent,
      bodyColor: v.bg,
      cardColor: v.surface,
      modalColor: v.surface,
      popoverColor: v.surface,
      tableColor: v.surface,
      textColor1: v.text,
      textColor2: v.text,
      textColor3: v.text2,
      dividerColor: v.border,
      borderColor: v.border,
      borderRadius: '8px',
      fontFamily:
        '-apple-system, BlinkMacSystemFont, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans SC", sans-serif',
    },
  }
})
</script>

<template>
  <n-config-provider
    :theme="ui.appDark ? darkTheme : null"
    :theme-overrides="overrides"
    :locale="naiveLocale"
    :date-locale="naiveDateLocale"
  >
    <n-loading-bar-provider>
      <n-dialog-provider>
        <n-notification-provider>
          <n-message-provider>
            <router-view />
            <app-shell-helper />
            <source-browser-bridge v-if="auth.loggedIn" />
          </n-message-provider>
        </n-notification-provider>
      </n-dialog-provider>
    </n-loading-bar-provider>
  </n-config-provider>
</template>
