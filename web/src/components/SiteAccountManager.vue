<script setup lang="ts">
/**
 * 网站账号：每个用户自己保存的网站登录状态（Cookie）。
 * 书源请求和远程浏览器都会带上这里的 Cookie；登录失效时服务器会提示到这里重新登录。
 */
import { computed, h, onMounted, ref } from 'vue'
import { NButton, NDataTable, NInput, NModal, NSpace, useDialog, useMessage, type DataTableColumns } from 'naive-ui'
import { siteAccountApi, type SiteAccount } from '@/api'
import { getClientId } from '@/api/http'
import { t } from '@/i18n'

const message = useMessage()
const dialog = useDialog()

const accounts = ref<SiteAccount[]>([])
const loading = ref(false)
const url = ref('')
const loggingIn = ref(false)
const showCookie = ref(false)
const cookie = ref('')
const savingCookie = ref(false)
const clearingAll = ref(false)
const expanded = ref(false)
const domainSearch = ref('')
const visibleAccounts = computed(() => {
  const query = domainSearch.value.trim().toLowerCase()
  if (query) return accounts.value.filter((account) => account.domain.toLowerCase().includes(query))
  return expanded.value ? accounts.value : accounts.value.slice(0, 5)
})

/** “刷新”按钮在卡片标题栏（远程浏览器里登录完成后点一下） */
defineExpose({ reload: () => load() })

async function load() {
  loading.value = true
  try {
    accounts.value = await siteAccountApi.list()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function login() {
  if (!url.value.trim()) return message.warning(t('请输入网址'))
  loggingIn.value = true
  try {
    await siteAccountApi.login(url.value.trim(), getClientId())
    message.info(t('已在远程浏览器中打开，登录完成后关闭网页即可保存'))
  } catch (e: any) {
    message.error(e.message)
  } finally {
    loggingIn.value = false
  }
}

async function saveCookie() {
  if (!url.value.trim()) return message.warning(t('请输入网址'))
  savingCookie.value = true
  try {
    const r = await siteAccountApi.setCookie(url.value.trim(), cookie.value)
    message.success(t('已保存 {0} 的 Cookie', [r.domain]))
    showCookie.value = false
    cookie.value = ''
    await load()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    savingCookie.value = false
  }
}

function clear(a: SiteAccount) {
  dialog.warning({
    title: t('退出网站登录'),
    content: t('清除「{0}」保存的 Cookie 后需要重新登录。确定清除？', [a.domain]),
    positiveText: t('清除'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      try {
        await siteAccountApi.clear(a.domain)
        await load()
      } catch (e: any) {
        message.error(e.message)
      }
    },
  })
}

function clearAll() {
  dialog.warning({
    title: t('全部清除'),
    content: t('确定清除全部 {0} 个网站的 Cookie？清除后需要重新登录。', [accounts.value.length]),
    positiveText: t('全部清除'), negativeText: t('取消'),
    onPositiveClick: async () => {
      clearingAll.value = true
      try { await siteAccountApi.clearAll(); await load() }
      catch (e) { if (e instanceof Error) message.error(e.message); else throw e }
      finally { clearingAll.value = false }
    },
  })
}

const columns: DataTableColumns<SiteAccount> = [
  { title: t('网站'), key: 'domain', render: (a) => h('span', { class: 'mono' }, a.domain) },
  {
    title: 'Cookie',
    key: 'names',
    ellipsis: { tooltip: true },
    render: (a) => [...new Set([...a.names, ...a.browserNames])].join(', '),
  },
  {
    title: t('操作'),
    key: 'op',
    width: 80,
    render: (a) => h(NButton, { size: 'tiny', type: 'error', quaternary: true, onClick: () => clear(a) }, () => t('清除')),
  },
]
</script>

<template>
  <div>
    <div class="login-row">
      <n-input v-model:value="url" class="mono" placeholder="https://example.com/login" @keyup.enter="login" />
      <n-button type="primary" :loading="loggingIn" @click="login">{{ t('登录') }}</n-button>
      <n-button @click="showCookie = true">{{ t('填写 Cookie') }}</n-button>
    </div>
    <p class="small muted">{{ t('在远程浏览器中打开网站登录，书源请求会自动带上登录状态；没有远程浏览器时可以手动填写 Cookie。') }}</p>

    <n-space align="center" style="margin-bottom: 12px" wrap>
      <n-input v-model:value="domainSearch" clearable :placeholder="t('搜索域名')" :input-props="{ 'aria-label': t('搜索域名') }" />
      <n-button type="error" secondary :disabled="!accounts.length || loading" :loading="clearingAll" @click="clearAll">{{ t('全部清除') }}</n-button>
    </n-space>
    <n-data-table :columns="columns" :data="visibleAccounts" :loading="loading" size="small" :scroll-x="480" />
    <n-button v-if="!domainSearch.trim() && accounts.length > 5" text style="margin-top: 12px" @click="expanded = !expanded">{{ expanded ? t('收起') : t('展开全部（{0}）', [accounts.length]) }}</n-button>

    <n-modal v-model:show="showCookie" preset="card" :title="t('填写 Cookie')" style="max-width: 520px">
      <n-space vertical>
        <n-input v-model:value="url" class="mono" placeholder="https://example.com" />
        <n-input v-model:value="cookie" type="textarea" class="mono" :rows="4" placeholder="name=value; name2=value2" />
      </n-space>
      <template #footer>
        <n-space justify="end"><n-button type="primary" :loading="savingCookie" @click="saveCookie">{{ t('保存') }}</n-button></n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.login-row {
  display: flex;
  gap: 8px;
}

.small {
  font-size: 13px;
  margin: 8px 0 12px;
}

.muted {
  opacity: 0.7;
}
</style>
