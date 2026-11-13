<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCard, NDescriptions, NDescriptionsItem, NIcon, NInput, NRadioButton, NRadioGroup, NSpace, NSwitch, NTabPane, NTabs, useMessage } from 'naive-ui'
import ResponsiveDialog from '@/components/ResponsiveDialog.vue'
import { ChevronForwardOutline, CodeSlashOutline, ShieldCheckmarkOutline, SwapHorizontalOutline } from '@vicons/ionicons5'
import ApiKeyManager from '@/components/ApiKeyManager.vue'
import AiModelManager from '@/components/AiModelManager.vue'
import TtsEngineManager from '@/components/TtsEngineManager.vue'
import TtsSubscriptionList from '@/components/TtsSubscriptionList.vue'
import AppThemeManager from '@/components/AppThemeManager.vue'
import PwaIconManager from '@/components/PwaIconManager.vue'
import HelpTip from '@/components/HelpTip.vue'
import OfflineManager from '@/components/OfflineManager.vue'
import BackupManager from '@/components/BackupManager.vue'
import ReadingStatsCard from '@/components/ReadingStatsCard.vue'
import SiteAccountManager from '@/components/SiteAccountManager.vue'
import { authApi, prefsApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useIsMobile } from '@/composables/useIsMobile'
import { formatDate } from '@/utils/media'
import { LOCALES, locale, setLocale, t } from '@/i18n'

const auth = useAuthStore()
const router = useRouter()
const message = useMessage()
/** 手机底栏只有书架、搜索、发现、我的：书源、规则、管理从这里进入 */
const isMobile = useIsMobile()

const pwd = ref({ old: '', new1: '', new2: '' })
const apiKeys = ref<InstanceType<typeof ApiKeyManager> | null>(null)
const siteAccounts = ref<InstanceType<typeof SiteAccountManager> | null>(null)
const saving = ref(false)
const progressSync = ref(false)
const progressSyncSaving = ref(false)
prefsApi.get<boolean>('progressSync').then((value) => { progressSync.value = value === true }).catch((error: unknown) => {
  message.error(error instanceof Error ? error.message : t('读取设置失败'))
})
async function setProgressSync(value: boolean) {
  progressSyncSaving.value = true
  try {
    await prefsApi.set('progressSync', value)
    progressSync.value = value
  } catch (error) {
    message.error(error instanceof Error ? error.message : t('保存失败'))
  } finally {
    progressSyncSaving.value = false
  }
}
const tool = ref<'tts' | 'ai' | null>(null)
/** 朗读引擎对话框里的“引擎 | 订阅”，每次打开回到引擎 */
const ttsTab = ref<'engines' | 'subscriptions'>('engines')
watch(tool, (value) => { if (value === 'tts') ttsTab.value = 'engines' })

async function changePassword() {
  if (pwd.value.new1.length < 6) return message.warning(t('新密码至少 6 位'))
  if (pwd.value.new1 !== pwd.value.new2) return message.warning(t('两次输入的密码不一致'))
  saving.value = true
  try {
    const r = await authApi.changePassword(pwd.value.old, pwd.value.new1)
    auth.applyToken(r.token)
    pwd.value = { old: '', new1: '', new2: '' }
    message.success(t('密码已修改，其他设备需要重新登录'))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

function logout() {
  auth.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="page narrow">
    <div class="page-header"><h2>{{ t('我的') }}</h2></div>

    <n-card :title="t('账号')" size="small" class="block">
      <n-descriptions :column="1" label-placement="left" size="small">
        <n-descriptions-item :label="t('用户名')">{{ auth.user?.username }}</n-descriptions-item>
        <n-descriptions-item :label="t('注册时间')">{{ formatDate(auth.user?.createdAt) }}</n-descriptions-item>
      </n-descriptions>
      <template #header-extra>
        <n-button size="small" @click="logout">{{ t('退出登录') }}</n-button>
      </template>
    </n-card>

    <h3>{{ t('内容与来源') }}</h3>
    <n-card size="small" class="block" content-style="padding: 0">
      <router-link :to="{ name: 'sources' }" class="entry">
        <n-icon :component="CodeSlashOutline" size="20" /><span>{{ t('书源') }}</span><n-icon :component="ChevronForwardOutline" class="entry-arrow" />
      </router-link>
      <router-link :to="{ name: 'rules' }" class="entry">
        <n-icon :component="SwapHorizontalOutline" size="20" /><span>{{ t('规则') }}</span><n-icon :component="ChevronForwardOutline" class="entry-arrow" />
      </router-link>
      <router-link v-if="isMobile && auth.isAdmin" :to="{ name: 'admin' }" class="entry">
        <n-icon :component="ShieldCheckmarkOutline" size="20" /><span>{{ t('管理') }}</span><n-icon :component="ChevronForwardOutline" class="entry-arrow" />
      </router-link>
    </n-card>

    <n-card size="small" class="block">
      <template #header>{{ t('网站账号') }}<help-tip>{{ t('书源用到的网站的登录状态，只对你自己生效') }}</help-tip></template>
      <template #header-extra>
        <n-button size="small" @click="siteAccounts?.reload()">{{ t('刷新') }}</n-button>
      </template>
      <site-account-manager ref="siteAccounts" />
    </n-card>
    <h3>{{ t('朗读与 AI') }}</h3>
    <n-card size="small" class="block" content-style="padding: 0">
      <button class="entry tool-entry" @click="tool = 'tts'"><span>{{ t('朗读引擎') }}</span><n-icon :component="ChevronForwardOutline" class="entry-arrow" /></button>
      <button class="entry tool-entry" @click="tool = 'ai'"><span>{{ t('AI 模型') }}</span><n-icon :component="ChevronForwardOutline" class="entry-arrow" /></button>
    </n-card>
    <responsive-dialog :show="tool !== null" :title="tool === 'tts' ? t('朗读引擎') : t('AI 模型')" @update:show="(v: boolean) => { if (!v) tool = null }">
      <template v-if="tool === 'tts'">
        <n-tabs v-model:value="ttsTab" type="segment" size="small" class="tts-tabs">
          <n-tab-pane name="engines" :tab="t('引擎')"><tts-engine-manager /></n-tab-pane>
          <n-tab-pane name="subscriptions" :tab="t('订阅')"><tts-subscription-list /></n-tab-pane>
        </n-tabs>
      </template>
      <ai-model-manager v-else-if="tool === 'ai'" />
    </responsive-dialog>

    <h3>{{ t('偏好') }}</h3>
    <n-card :title="t('外观')" size="small" class="block">
      <app-theme-manager />
      <div class="pref-row">
        <span class="pref-label">{{ t('界面语言') }}</span>
        <n-radio-group :value="locale" size="small" @update:value="setLocale">
          <n-radio-button v-for="l in LOCALES" :key="l.value" :value="l.value">{{ l.label }}</n-radio-button>
        </n-radio-group>
      </div>
    </n-card>

    <n-card :title="t('应用图标')" size="small" class="block">
      <pwa-icon-manager />
    </n-card>

    <n-card size="small" class="block">
      <template #header>{{ t('多端阅读同步') }}<help-tip>{{ t('同时在多个设备或 IDE 插件里读同一本书时，自动跟随翻章和滚动') }}</help-tip></template>
      <template #header-extra><n-switch :value="progressSync" :loading="progressSyncSaving" :aria-label="t('多端阅读同步')" @update:value="setProgressSync" /></template>
    </n-card>

    <h3>{{ t('数据') }}</h3>
    <n-card :title="t('阅读统计')" size="small" class="block">
      <reading-stats-card />
    </n-card>
    <n-card size="small" class="block">
      <template #header>{{ t('离线阅读') }}<help-tip>{{ t('在书籍详情页点“离线下载”，没有网络也能阅读，进度联网后自动同步') }}</help-tip></template>
      <offline-manager />
    </n-card>

    <n-card size="small" class="block">
      <template #header>{{ t('备份与还原') }}<help-tip>{{ t('备份文件与阅读 App 通用') }}</help-tip></template>
      <backup-manager />
    </n-card>

    <h3>{{ t('安全') }}</h3>
    <n-card :title="t('修改密码')" size="small" class="block">
      <n-space vertical style="max-width: 360px">
        <n-input v-model:value="pwd.old" type="password" :placeholder="t('原密码')" />
        <n-input v-model:value="pwd.new1" type="password" :placeholder="t('新密码')" />
        <p class="muted small">{{ t('新密码至少 6 位') }}</p>
        <n-input v-model:value="pwd.new2" type="password" :placeholder="t('确认新密码')" />
        <n-button type="primary" :loading="saving" @click="changePassword">{{ t('修改密码') }}</n-button>
      </n-space>
    </n-card>

    <n-card :title="t('开放接口令牌')" size="small" class="block">
      <template #header-extra>
        <n-button size="small" type="primary" @click="apiKeys?.openCreate()">{{ t('生成开放接口令牌') }}</n-button>
      </template>
      <api-key-manager ref="apiKeys" />
    </n-card>
  </div>
</template>

<style scoped>
.narrow {
  max-width: 820px;
}

.block {
  margin-bottom: 16px;
}

.small {
  font-size: 13px;
}

.pref-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.pref-row + .pref-row {
  margin-top: 12px;
}

.pref-label {
  min-width: 4em;
}

.entry {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 48px;
  padding: 0 16px;
  color: inherit;
  text-decoration: none;
}

.entry + .entry {
  border-top: 1px solid var(--app-border);
}

.entry span {
  flex: 1;
}

.entry-arrow {
  opacity: 0.45;
}

.tool-entry {
  width: 100%;
  background: transparent;
  border: 0;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.tts-tabs :deep(.n-tab-pane) {
  padding-top: 12px;
}
.entry:hover { background: var(--app-surface); }
.entry:focus-visible { outline: 2px solid currentColor; outline-offset: -3px; }
</style>
