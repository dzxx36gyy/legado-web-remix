<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from 'vue'
import { NButton, NEmpty, NSpin, useMessage, useThemeVars } from 'naive-ui'
import { statsApi } from '@/api'
import type { ReadingSummary } from '@/api/types'
import { localDay } from '@/composables/useReadingStats'
import { compactNumber, t } from '@/i18n'

/** “我的”页面：阅读统计（今天、最近 7 天、累计、读得最多的书） */
const summary = ref<ReadingSummary | null>(null)
const loading = ref(false)
const error = ref('')

/** 最近 7 天的日期（含今天），从早到晚 */
const last7 = computed(() => {
  const out: string[] = []
  for (let i = 6; i >= 0; i--) {
    const d = new Date()
    d.setDate(d.getDate() - i)
    out.push(localDay(d))
  }
  return out
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    summary.value = await statsApi.summary(last7.value[0])
  } catch (e: any) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
const editing = ref(false)
const busy = ref<number | null>(null)
const message = useMessage()

async function setHidden(bookId: number, hidden: boolean) {
  busy.value = bookId
  try {
    await statsApi.setHidden(bookId, hidden)
    await load()
  } catch (e: any) {
    message.error(e.message)
  } finally {
    busy.value = null
  }
}

onMounted(load)
// “我的”页面被缓存时，切回来刷新
onActivated(load)
const themeVars = useThemeVars()

const byDay = computed(() => new Map((summary.value?.days ?? []).map((d) => [d.day, d])))
const today = computed(() => byDay.value.get(last7.value[6]))
const bars = computed(() => {
  const max = Math.max(60, ...last7.value.map((d) => byDay.value.get(d)?.seconds ?? 0))
  const week = [t('日'), t('一'), t('二'), t('三'), t('四'), t('五'), t('六')]
  return last7.value.map((d, i) => {
    const sec = byDay.value.get(d)?.seconds ?? 0
    return {
      day: d,
      label: i === 6 ? t('今天') : t('周{0}', [week[new Date(`${d}T00:00:00`).getDay()]]),
      seconds: sec,
      height: Math.round((sec / max) * 100),
    }
  })
})

function duration(sec: number): string {
  const m = Math.round(sec / 60)
  if (m < 60) return t('{0} 分钟', [m])
  const h = Math.floor(m / 60)
  return m % 60 ? t('{0} 小时 {1} 分', [h, m % 60]) : t('{0} 小时', [h])
}
function shortDuration(sec: number): string {
  const m = Math.round(sec / 60)
  return m < 60 ? t('{0}分', [m]) : t('{0}时', [(m / 60).toFixed(1)])
}
function chars(n: number): string {
  return t('{0} 字', [compactNumber(n)])
}

defineExpose({ load })
</script>

<template>
  <div class="stats">
    <div v-if="loading && !summary" class="center"><n-spin size="small" /></div>
    <div v-else-if="error" class="muted small">{{ t('加载失败：{0}', [error]) }}</div>
    <template v-else-if="summary">
      <n-empty v-if="!summary.totalSeconds && !summary.totalChars && !summary.hidden.length" :description="t('还没有阅读记录，阅读时会自动统计')" size="small" />
      <template v-else>
        <div class="today">
          <div>
            <div class="big">{{ duration(today?.seconds ?? 0) }}</div>
            <div class="muted small">{{ t('今天阅读') }}</div>
          </div>
          <div>
            <div class="big">{{ chars(today?.chars ?? 0) }}</div>
            <div class="muted small">{{ t('今天读完的章节字数') }}</div>
          </div>
        </div>

        <div class="chart" role="img" :aria-label="t('最近 7 天的阅读时间')">
          <div v-for="b in bars" :key="b.day" class="col" :title="`${b.day}：${duration(b.seconds)}`">
            <span class="val">{{ b.seconds ? shortDuration(b.seconds) : '' }}</span>
            <div class="bar-wrap"><div class="bar" :style="{ height: `${b.height}%`, background: themeVars.primaryColor }" /></div>
            <span class="label">{{ b.label }}</span>
          </div>
        </div>

        <div class="total muted small">
          {{ t('累计阅读 {0} · {1} 天 · {2}', [duration(summary.totalSeconds), summary.totalDays, chars(summary.totalChars)]) }}
        </div>

        <div v-if="summary.books.length || summary.otherSeconds || summary.hidden.length" class="books">
          <div class="books-head">
            <span class="muted small">{{ t('读得最多的书') }}</span>
            <n-button text size="small" type="primary" @click="editing = !editing">{{ editing ? t('完成') : t('编辑') }}</n-button>
          </div>
          <div v-for="b in summary.books.slice(0, editing ? 10 : 5)" :key="b.bookId" class="book">
            <span class="name">{{ b.name }}<span v-if="b.author" class="muted"> · {{ b.author }}</span></span>
            <n-button v-if="editing" size="tiny" :loading="busy === b.bookId" @click="setHidden(b.bookId, true)">{{ t('隐藏') }}</n-button>
            <span v-else class="muted small">{{ duration(b.seconds) }}</span>
          </div>
          <div v-if="summary.otherSeconds" class="book">
            <span class="name muted">{{ t('其他（不在书架上的书）') }}</span>
            <span v-if="!editing" class="muted small">{{ duration(summary.otherSeconds) }}</span>
          </div>
          <template v-if="editing">
            <div class="muted small hint">{{ t('隐藏的书不计入任何阅读统计，可随时恢复') }}</div>
            <div v-if="summary.hidden.length" class="muted small" style="margin: 8px 0 4px">{{ t('已隐藏') }}</div>
            <div v-for="b in summary.hidden" :key="b.bookId" class="book">
              <span class="name muted">{{ b.name }}<span v-if="b.author"> · {{ b.author }}</span></span>
              <n-button size="tiny" :loading="busy === b.bookId" @click="setHidden(b.bookId, false)">{{ t('恢复') }}</n-button>
            </div>
          </template>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.center {
  display: flex;
  justify-content: center;
  padding: 16px 0;
}

.today {
  display: flex;
  gap: 32px;
}

.big {
  font-size: 22px;
  font-weight: 600;
}

.chart {
  display: flex;
  gap: 6px;
  height: 120px;
  margin: 16px 0 8px;
}

.col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 0;
}

.val {
  height: 16px;
  font-size: 11px;
  opacity: 0.7;
  white-space: nowrap;
}

.bar-wrap {
  flex: 1;
  display: flex;
  align-items: flex-end;
  width: 100%;
  max-width: 28px;
}

.bar {
  width: 100%;
  min-height: 2px;
  border-radius: 4px 4px 0 0;
  opacity: 0.85;
}

.label {
  margin-top: 4px;
  font-size: 11px;
  opacity: 0.7;
}

.total {
  margin-bottom: 12px;
}

.books-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.hint {
  margin-top: 6px;
}

.book {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 13px;
}

.book > .small {
  flex-shrink: 0;
  white-space: nowrap;
}

.name {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
