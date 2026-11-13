/**
 * 应用图标（PWA）：每台设备自己选择，存在本地存储里。
 *
 * - 内置图标是 public/pwa-icons/<id>/ 下的静态文件，每套都有自己的 manifest.webmanifest，
 *   安装时浏览器读取的是固定地址的清单和图标（不用 blob: / data: 地址，那样的清单无法可靠安装）
 * - 自定义图标在浏览器里裁成 512×512 PNG 后上传，服务器按内容哈希保存，图标和清单都是固定的公开地址
 * - 换图标只改页面里的 <link rel="manifest">、favicon、apple-touch-icon；已经安装到主屏幕的图标
 *   由系统决定是否更新（iOS 需要删除后重新添加）
 */
import { ApiError, requestResponse } from '@/api/http'
import { t } from '@/i18n'

export const BUILTIN_ICONS = ['default', 'ink', 'celadon', 'paper', 'midnight'] as const
export type BuiltinIcon = (typeof BUILTIN_ICONS)[number]
export type IconSelection = BuiltinIcon | 'custom'

export interface PwaIconState {
  readonly selected: IconSelection
  /** 上传过的自定义图标（内容哈希），切换到内置图标后仍保留，可以再切回来 */
  readonly customId: string | null
}

interface IconUrls {
  readonly manifest: string
  readonly favicon: string
  readonly faviconType: string
  readonly appleTouch: string
  /** 设置页里显示的预览 */
  readonly preview: string
}

const STORAGE_KEY = 'legado_pwa_icon'
const CUSTOM_ID = /^[0-9a-f]{64}$/
export const DEFAULT_STATE: PwaIconState = { selected: 'default', customId: null }

/** 构建时 vite-plugin-pwa 写进 index.html 的清单地址（开发模式下没有清单） */
const originalManifest = document.querySelector<HTMLLinkElement>('link[rel="manifest"]')?.getAttribute('href') ?? null

function isBuiltin(value: unknown): value is BuiltinIcon {
  return typeof value === 'string' && (BUILTIN_ICONS as readonly string[]).includes(value)
}

function parseState(raw: string | null): PwaIconState {
  if (!raw) return DEFAULT_STATE
  let data: unknown
  try {
    data = JSON.parse(raw)
  } catch {
    return DEFAULT_STATE
  }
  if (typeof data !== 'object' || data === null) return DEFAULT_STATE
  const rawCustom: unknown = Reflect.get(data, 'customId')
  const customId = typeof rawCustom === 'string' && CUSTOM_ID.test(rawCustom) ? rawCustom : null
  const selected: unknown = Reflect.get(data, 'selected')
  if (selected === 'custom' && customId) return { selected, customId }
  return { selected: isBuiltin(selected) ? selected : 'default', customId }
}

export function loadPwaIcon(): PwaIconState {
  try {
    return parseState(localStorage.getItem(STORAGE_KEY))
  } catch {
    return DEFAULT_STATE
  }
}

export function builtinIconUrls(id: BuiltinIcon): IconUrls {
  if (id === 'default') {
    return {
      manifest: originalManifest ?? '',
      favicon: '/favicon.svg',
      faviconType: 'image/svg+xml',
      appleTouch: '/apple-touch-icon-180x180.png',
      preview: '/pwa-192x192.png',
    }
  }
  const base = `/pwa-icons/${id}`
  return {
    manifest: `${base}/manifest.webmanifest`,
    favicon: `${base}/favicon.svg`,
    faviconType: 'image/svg+xml',
    appleTouch: `${base}/apple-touch-icon-180x180.png`,
    preview: `${base}/pwa-192x192.png`,
  }
}

export function customIconUrls(id: string): IconUrls {
  const base = `/api/pwa-icons/${id}`
  const icon = `${base}/icon.png`
  return { manifest: `${base}/manifest.webmanifest`, favicon: icon, faviconType: 'image/png', appleTouch: icon, preview: icon }
}

function urlsFor(state: PwaIconState): IconUrls {
  if (state.selected === 'custom' && state.customId) return customIconUrls(state.customId)
  return builtinIconUrls(state.selected === 'custom' ? 'default' : state.selected)
}

function setLink(rel: string, href: string, type?: string) {
  let link = document.querySelector<HTMLLinkElement>(`link[rel="${rel}"]`)
  if (!href) {
    link?.remove()
    return
  }
  if (!link) {
    link = document.createElement('link')
    link.rel = rel
    document.head.appendChild(link)
  }
  if (type) link.type = type
  if (link.getAttribute('href') !== href) link.href = href
}

/** 把选择写进页面的图标和清单链接（页面启动时和用户切换时调用） */
export function applyPwaIcon(state: PwaIconState) {
  const urls = urlsFor(state)
  setLink('manifest', urls.manifest)
  setLink('icon', urls.favicon, urls.faviconType)
  setLink('apple-touch-icon', urls.appleTouch)
}

export function savePwaIcon(state: PwaIconState) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    // 存不下时只对本次生效
  }
  applyPwaIcon(state)
}

// ---------------- 自定义图标 ----------------

const ACCEPT_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/svg+xml'] as const
export const ICON_ACCEPT = ACCEPT_TYPES.join(',')
const MAX_FILE_BYTES = 5 * 1024 * 1024
const MIN_SIDE = 192
const OUTPUT_SIDE = 512

/** 选择的图片不能用作图标（类型、大小、尺寸或无法解码） */
export class IconFileError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'IconFileError'
  }
}

async function loadImage(file: Blob): Promise<HTMLImageElement> {
  const url = URL.createObjectURL(file)
  try {
    const img = new Image()
    img.src = url
    await img.decode()
    return img
  } catch {
    throw new IconFileError(t('图片无法读取'))
  } finally {
    URL.revokeObjectURL(url)
  }
}

/**
 * 校验图片并生成 512×512 的 PNG：居中裁成正方形、铺满画布，透明部分垫白色
 * （iOS 会把透明背景显示成黑色）
 */
export async function renderIconFile(file: File): Promise<Blob> {
  if (!(ACCEPT_TYPES as readonly string[]).includes(file.type)) throw new IconFileError(t('只支持 PNG、JPEG、WebP 或 SVG 图片'))
  if (file.size > MAX_FILE_BYTES) throw new IconFileError(t('图片不能超过 5MB'))
  const img = await loadImage(file)
  const isSvg = file.type === 'image/svg+xml'
  const width = img.naturalWidth || (isSvg ? OUTPUT_SIDE : 0)
  const height = img.naturalHeight || (isSvg ? OUTPUT_SIDE : 0)
  if (!isSvg && Math.min(width, height) < MIN_SIDE) throw new IconFileError(t('图片至少需要 {0}×{0} 像素', [MIN_SIDE]))
  const side = Math.min(width, height)
  const canvas = document.createElement('canvas')
  canvas.width = OUTPUT_SIDE
  canvas.height = OUTPUT_SIDE
  const context = canvas.getContext('2d')
  if (!context) throw new IconFileError(t('图片处理失败'))
  context.fillStyle = '#ffffff'
  context.fillRect(0, 0, OUTPUT_SIDE, OUTPUT_SIDE)
  context.imageSmoothingQuality = 'high'
  context.drawImage(img, (width - side) / 2, (height - side) / 2, side, side, 0, 0, OUTPUT_SIDE, OUTPUT_SIDE)
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
  if (!blob) throw new IconFileError(t('图片处理失败'))
  return blob
}

/** 上传 512×512 PNG，返回服务器保存的图标 id（内容的 SHA-256） */
export async function uploadCustomIcon(png: Blob): Promise<string> {
  const res = await requestResponse('POST', '/api/pwa-icons', {
    body: png,
    headers: { 'Content-Type': 'image/png' },
    errorMessage: (status) => t('上传失败（{0}）', [status]),
  })
  const data: unknown = await res.json().catch(() => null)
  const id: unknown = typeof data === 'object' && data !== null ? Reflect.get(data, 'id') : undefined
  if (typeof id !== 'string' || !CUSTOM_ID.test(id)) throw new ApiError(res.status, t('上传失败（{0}）', [res.status]))
  return id
}
