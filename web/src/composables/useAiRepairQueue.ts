import { computed, ref } from 'vue'
import type { SourceCheckResult } from '@/api'

/** 队列中单个书源的处理结果：unsaved 表示有候选规则但用户没有保存，书源未改动 */
export type RepairOutcome =
  | { readonly kind: 'saved' | 'passed' | 'unsaved' | 'skipped' }
  | { readonly kind: 'failed'; readonly error: string }

export interface RepairQueueItem { readonly id: number; readonly name: string }

/** AiRepairModal 显示的队列位置；autoStart 为 true 时打开即开始检测（沿用上一个书源选的模型） */
export interface RepairQueueView { readonly name: string; readonly position: number; readonly total: number; readonly next: string | null; readonly cancelled: boolean; readonly autoStart: boolean }

/** 网络或网站验证问题不是改规则能解决的，必须先处理；HTTP 错误可能来自错误的请求规则，允许修复 */
const networkTypes: ReadonlySet<string> = new Set(['noResult', 'blocked', 'dns', 'tls', 'connection', 'timeout'])

export function isRepairable(errorType: SourceCheckResult['errorType']): boolean {
  return errorType === null || !networkTypes.has(errorType)
}

/**
 * 引导式顺序修复：一次只打开一个书源，由用户决定保存、下一个、跳过或取消。
 * 队列从不自动保存，也不自动确认 JavaScript。
 */
export function useAiRepairQueue() {
  const items = ref<RepairQueueItem[]>([])
  const index = ref(-1)
  const cancelled = ref(false)
  const done = ref<{ readonly item: RepairQueueItem; readonly outcome: RepairOutcome }[]>([])
  const current = computed(() => items.value[index.value] ?? null)
  const next = computed(() => cancelled.value ? null : items.value[index.value + 1] ?? null)
  /** 取消后没有处理的书源数 */
  const remaining = computed(() => items.value.length - done.value.length - (current.value ? 1 : 0))

  function start(list: RepairQueueItem[]) {
    items.value = list
    index.value = list.length ? 0 : -1
    cancelled.value = false
    done.value = []
  }
  /** 记录当前书源的结果；未取消时进入下一个，返回下一个书源（没有则为 null） */
  function record(outcome: RepairOutcome): RepairQueueItem | null {
    const item = current.value
    if (!item) return null
    done.value.push({ item, outcome })
    index.value = cancelled.value ? -1 : index.value + 1
    return current.value
  }
  /** 取消剩余书源；当前书源保留，直到用户保存后点“结束队列”或关闭弹窗才记录 */
  function cancel() {
    if (current.value) cancelled.value = true
  }
  function clear() {
    items.value = []
    index.value = -1
    cancelled.value = false
    done.value = []
  }
  return { items, index, cancelled, current, next, done, remaining, start, record, cancel, clear }
}
