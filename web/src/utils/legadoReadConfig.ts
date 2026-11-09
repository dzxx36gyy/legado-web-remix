import { normalizeColor } from '@/utils/appTheme'
import type { ReaderConfig } from '@/stores/reader'

export interface ImportedReadStyle {
  name: string
  /** 日间颜色；背景是图片（bgType 不为 0）时 bg 为 null */
  day: { bg: string | null; text: string | null }
  night: { bg: string | null; text: string | null } | null
  typography: Partial<ReaderConfig>
}

const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : null)
const clamp = (v: number, min: number, max: number) => Math.min(max, Math.max(min, v))
const round = (v: number, step: number) => Math.round(v / step) * step

/** CJK 字体在 Android 上的字高约为字号的 1.2 倍，App 的行距、段距按字高计算 */
const TEXT_HEIGHT_EM = 1.2

/**
 * 解析阅读 App「阅读界面 → 样式 → 导出」得到的 config.json（或 readConfig.json 数组中的一项）。
 * 换算规则（App 源码 ChapterProvider / TextChapterLayout）：
 * - 行高 = 字高 × (1 + lineSpacingExtra / 10)
 * - 段距 = 字高 × paragraphSpacing / 10
 * - 标题字号 = textSize + titleSize；标题上下间距单位为 dp，按正文字号换算为 em
 * - 缩进 paragraphIndent 是若干全角空格，每个占 1em
 */
export function parseLegadoReadConfig(json: string): ImportedReadStyle {
  let data: unknown = JSON.parse(json)
  if (Array.isArray(data)) data = data[0]
  if (!data || typeof data !== 'object') throw new Error('not an object')
  const o = data as Record<string, unknown>
  if (!('textSize' in o) && !('bgStr' in o) && !('textColor' in o)) throw new Error('not a read config')

  const color = (bgType: unknown, v: unknown) => ((num(bgType) ?? 0) === 0 ? normalizeColor(v) : null)
  const day = { bg: color(o.bgType, o.bgStr), text: normalizeColor(o.textColor) }
  const nightBg = color(o.bgTypeNight, o.bgStrNight)
  const nightText = normalizeColor(o.textColorNight)
  const night = nightBg || nightText ? { bg: nightBg, text: nightText } : null

  const typo: Partial<ReaderConfig> = {}
  const textSize = num(o.textSize)
  if (textSize) typo.fontSize = clamp(Math.round(textSize), 12, 40)
  const size = textSize || 20
  const lse = num(o.lineSpacingExtra)
  if (lse !== null) typo.lineHeight = clamp(round(TEXT_HEIGHT_EM * (1 + lse / 10), 0.1), 1.2, 3)
  const ps = num(o.paragraphSpacing)
  if (ps !== null) typo.paragraphSpacing = clamp(round((TEXT_HEIGHT_EM * ps) / 10, 0.1), 0, 2)
  const ls = num(o.letterSpacing)
  if (ls !== null) typo.letterSpacing = clamp(round(ls, 0.01), 0, 0.3)
  if (typeof o.paragraphIndent === 'string') {
    const chars = [...o.paragraphIndent].reduce((n, c) => n + (c === '　' ? 1 : c === ' ' ? 0.5 : 0), 0)
    typo.indent = chars > 0
    typo.indentChars = clamp(chars, 0, 4)
  }
  const bold = num(o.textBold)
  if (bold !== null) typo.fontWeight = bold === 1 ? 700 : bold === 2 ? 300 : 400
  const titleMode = num(o.titleMode)
  if (titleMode !== null) typo.titleAlign = titleMode === 1 ? 'center' : titleMode === 2 ? 'hidden' : 'left'
  const titleSize = num(o.titleSize)
  if (titleSize !== null) typo.titleSize = clamp(round((size + titleSize) / size, 0.1), 1, 2.4)
  const tt = num(o.titleTopSpacing)
  if (tt !== null) typo.titleTop = clamp(round(tt / size, 0.1), 0, 4)
  const tb = num(o.titleBottomSpacing)
  if (tb !== null) typo.titleBottom = clamp(round(tb / size, 0.1), 0, 4)
  const pl = num(o.paddingLeft)
  const pr = num(o.paddingRight)
  if (pl !== null || pr !== null) typo.paddingX = clamp(Math.round(((pl ?? pr ?? 0) + (pr ?? pl ?? 0)) / 2), 0, 120)
  const pt = num(o.paddingTop)
  if (pt !== null) typo.paddingTop = clamp(Math.round(pt), 0, 120)
  const pb = num(o.paddingBottom)
  if (pb !== null) typo.paddingBottom = clamp(Math.round(pb), 0, 120)

  const name = typeof o.name === 'string' && o.name.trim() ? o.name.trim().slice(0, 12) : ''
  return { name, day, night, typography: typo }
}
