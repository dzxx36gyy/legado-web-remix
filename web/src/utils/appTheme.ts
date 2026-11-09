import { isLight, luminance, mix, parseHex, toHex } from '@/utils/color'

/**
 * 界面主题，字段与阅读 App 的「主题设置 → 导出主题」相同，可直接导入 App 分享的主题 JSON。
 * - primaryColor：侧栏 / 标题栏颜色
 * - accentColor：强调色（按钮、选中项）
 * - backgroundColor：页面背景
 * - bottomBackground：底部导航栏背景
 */
export interface AppTheme {
  themeName: string
  isNightTheme: boolean
  primaryColor: string
  accentColor: string
  backgroundColor: string
  bottomBackground: string
}

/** 内置主题。前两个为默认的日间 / 夜间主题，其后是阅读 App 自带的主题和网上分享的主题 */
export const presetAppThemes: AppTheme[] = [
  { themeName: '羊皮纸', isNightTheme: false, primaryColor: '#fffdf8', accentColor: '#9c6b30', backgroundColor: '#f6f1e7', bottomBackground: '#fffdf8' },
  { themeName: '夜读', isNightTheme: true, primaryColor: '#211e18', accentColor: '#d4a15c', backgroundColor: '#16140f', bottomBackground: '#211e18' },
  { themeName: '默认', isNightTheme: false, primaryColor: '#795548', accentColor: '#e53935', backgroundColor: '#f5f5f5', bottomBackground: '#eeeeee' },
  { themeName: '典雅蓝', isNightTheme: false, primaryColor: '#03a9f4', accentColor: '#ad1457', backgroundColor: '#f5f5f5', bottomBackground: '#eeeeee' },
  { themeName: '白昼鸿雁', isNightTheme: false, primaryColor: '#c9d0d7', accentColor: '#606c6b', backgroundColor: '#9fb1ba', bottomBackground: '#9fb1ba' },
  { themeName: '黑白', isNightTheme: true, primaryColor: '#303030', accentColor: '#e0e0e0', backgroundColor: '#424242', bottomBackground: '#424242' },
  { themeName: 'A屏黑', isNightTheme: true, primaryColor: '#000000', accentColor: '#ffffff', backgroundColor: '#000000', bottomBackground: '#000000' },
  { themeName: '鹤·夜', isNightTheme: true, primaryColor: '#101420', accentColor: '#616362', backgroundColor: '#101420', bottomBackground: '#101420' },
]

/**
 * App 导出的颜色是 #AARRGGBB（如 #ff9fb1ba），也可能是有符号的 ARGB 整数。
 * 统一为 #rrggbb，不透明度忽略；无法识别时返回 null
 */
export function normalizeColor(v: unknown): string | null {
  if (typeof v === 'number' && Number.isFinite(v)) return toHex([(v >> 16) & 255, (v >> 8) & 255, v & 255])
  if (typeof v !== 'string') return null
  let h = v.trim()
  if (/^#?[0-9a-f]{8}$/i.test(h)) h = '#' + h.replace('#', '').slice(2)
  const rgb = parseHex(h)
  return rgb ? toHex(rgb) : null
}

/** 把导入的 JSON 解析为主题列表（单个对象或数组），字段不全的条目跳过 */
export function parseAppThemes(json: string): AppTheme[] {
  const data: unknown = JSON.parse(json)
  const list = Array.isArray(data) ? data : [data]
  const out: AppTheme[] = []
  for (const item of list) {
    if (!item || typeof item !== 'object') continue
    const o = item as Record<string, unknown>
    const primary = normalizeColor(o.primaryColor)
    const accent = normalizeColor(o.accentColor)
    const bg = normalizeColor(o.backgroundColor)
    if (!primary || !accent || !bg) continue
    out.push({
      themeName: typeof o.themeName === 'string' && o.themeName.trim() ? o.themeName.trim().slice(0, 40) : '导入的主题',
      isNightTheme: o.isNightTheme === true,
      primaryColor: primary,
      accentColor: accent,
      backgroundColor: bg,
      bottomBackground: normalizeColor(o.bottomBackground) ?? bg,
    })
  }
  return out
}

/** 导出为 App 可导入的 JSON（颜色带不透明度前缀） */
export function exportAppTheme(t: AppTheme): string {
  const argb = (c: string) => '#ff' + c.replace('#', '')
  return JSON.stringify(
    {
      accentColor: argb(t.accentColor),
      backgroundColor: argb(t.backgroundColor),
      bottomBackground: argb(t.bottomBackground),
      isNightTheme: t.isNightTheme,
      primaryColor: argb(t.primaryColor),
      themeName: t.themeName,
    },
    null,
    2,
  )
}

/** WCAG 对比度（1 ~ 21） */
function contrast(a: string, b: string): number {
  const x = luminance(a)
  const y = luminance(b)
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)
}

/**
 * 主题的四个颜色推算出界面使用的全部颜色：文字颜色按所在背景的深浅决定；
 * 分享的主题里强调色常与背景色相近，强调色在某个背景上看不清时，该处的选中项改用文字颜色
 */
export function appThemeVars(t: AppTheme) {
  const bgLight = isLight(t.backgroundColor)
  const ink = bgLight ? '#000000' : '#ffffff'
  const surface = bgLight ? mix(t.backgroundColor, '#ffffff', 0.65) : mix(t.backgroundColor, '#ffffff', 0.06)
  const text = mix(t.backgroundColor, ink, 0.85)
  const accent = contrast(t.accentColor, surface) >= 2.5 ? t.accentColor : text
  const barText = isLight(t.primaryColor) ? '#1f1a12' : '#f2f2f2'
  const navText = isLight(t.bottomBackground) ? '#1f1a12' : '#f2f2f2'
  return {
    bg: t.backgroundColor,
    surface,
    border: mix(t.backgroundColor, ink, bgLight ? 0.12 : 0.16),
    text,
    text2: mix(t.backgroundColor, ink, 0.55),
    accent,
    bar: t.primaryColor,
    barText,
    barText2: mix(t.primaryColor, barText, 0.7),
    barAccent: contrast(t.accentColor, t.primaryColor) >= 2.5 ? t.accentColor : barText,
    nav: t.bottomBackground,
    navText: mix(t.bottomBackground, navText, 0.6),
    navAccent: contrast(t.accentColor, t.bottomBackground) >= 2.5 ? t.accentColor : navText,
    onAccent: isLight(accent) ? '#1a1a1a' : '#ffffff',
    hover: mix(accent, bgLight ? '#ffffff' : '#000000', 0.15),
    pressed: mix(accent, bgLight ? '#000000' : '#ffffff', 0.15),
  }
}
