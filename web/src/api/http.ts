import { locale } from '@/i18n'
import { t } from '@/i18n'

const TOKEN_KEY = 'legado_token'

export function getToken(): string {
  try {
    return localStorage.getItem(TOKEN_KEY) || ''
  } catch {
    return ''
  }
}

export function setToken(token: string) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* ignore */
  }
}

/** 当前浏览器标签页的 id：书源脚本弹出的网页只推送给发起操作的标签页 */
const clientId = (() => {
  const rand = () => Math.random().toString(36).slice(2, 10)
  return `${Date.now().toString(36)}${rand()}${rand()}`
})()

export function getClientId(): string {
  return clientId
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 连不上服务器（断网、服务器不可用） */
export class NetworkError extends ApiError {
  constructor() {
    super(0, t('网络不可用，无法连接服务器'))
    this.name = 'NetworkError'
  }
}

export function isNetworkError(e: unknown): boolean {
  return e instanceof NetworkError || (typeof navigator !== 'undefined' && navigator.onLine === false)
}

type Handler = () => void
let unauthorizedHandler: Handler | null = null
export function onUnauthorized(h: Handler) {
  unauthorizedHandler = h
}

/** 只有 401 才清除登录态；502/503 等服务器不可用不能把用户踢下线 */
export function notifyUnauthorized(status: number) {
  if (status === 401) unauthorizedHandler?.()
}

export function isAbortError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && Reflect.get(error, 'name') === 'AbortError'
}

function errorText(value: unknown): string | null {
  if (typeof value !== 'object' || value === null) return null
  const error = Reflect.get(value, 'error')
  return typeof error === 'string' && error ? error : null
}

export type ErrorMessage = string | ((status: number) => string)

/** requestResponse 的输入；JSON / Blob / 流共用同一套状态与网络异常处理 */
export interface RequestInitLite {
  body?: unknown
  raw?: boolean
  signal?: AbortSignal
  headers?: Record<string, string>
  /** 非 2xx 且服务器没有返回 error 时使用的提示 */
  errorMessage?: ErrorMessage
}

export type RequestOptions = Omit<RequestInitLite, 'body'>

async function parseError(res: Response, fallback?: ErrorMessage): Promise<ApiError> {
  const fallbackMessage = typeof fallback === 'function' ? fallback(res.status) : fallback
  const data: unknown = await res.json().catch(() => null)
  const msg = errorText(data) || fallbackMessage || t('请求失败 ({0})', [res.status])
  notifyUnauthorized(res.status)
  return new ApiError(res.status, msg)
}

async function ensureOk(res: Response, fallback?: ErrorMessage): Promise<Response> {
  if (res.ok) return res
  // 反向代理在后端不可用时返回的 502/503/504（非 JSON）也按网络不可用处理
  if (res.status >= 500 && !res.headers.get('content-type')?.includes('json')) {
    throw new NetworkError()
  }
  throw await parseError(res, fallback)
}

/**
 * 发请求并只处理状态码 / 网络错误；成功响应体由调用方按 JSON、Blob、ArrayBuffer 或流解码。
 * 所有入口共用这里，401 统一触发登录失效，502/503 不会。
 */
export async function requestResponse(
  method: string,
  url: string,
  init: RequestInitLite = {},
): Promise<Response> {
  const headers: Record<string, string> = { 'X-Client-Id': clientId, 'Accept-Language': locale, ...init.headers }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  let payload: BodyInit | undefined
  if (init.body !== undefined) {
    if (init.body instanceof Blob) {
      payload = init.body
      headers['Content-Type'] ??= init.body.type || 'application/octet-stream'
    } else if (typeof init.body === 'string' && init.raw) {
      payload = init.body
      headers['Content-Type'] ??= 'text/plain; charset=utf-8'
    } else {
      payload = JSON.stringify(init.body)
      headers['Content-Type'] ??= 'application/json'
    }
  }
  let res: Response
  try {
    res = await fetch(url, { method, headers, body: payload, signal: init.signal })
  } catch (e: unknown) {
    if (isAbortError(e)) throw e
    throw new NetworkError()
  }
  return ensureOk(res, init.errorMessage)
}

export async function request<T = any>(
  method: string,
  url: string,
  body?: unknown,
  opts: RequestOptions = {},
): Promise<T> {
  const res = await requestResponse(method, url, { ...opts, body })
  const text = await res.text()
  const data: unknown = text ? JSON.parse(text) : undefined
  const pending = typeof data === 'object' && data !== null ? Reflect.get(data, 'pending') : undefined
  // 处理时间较长（如书源脚本等用户在网页里登录）：服务器先返回 202，再分段等待结果，避免反向代理超时（Cloudflare 524）
  if (res.status === 202 && typeof pending === 'string') {
    return request<T>('GET', `/api/jobs/${encodeURIComponent(pending)}`, undefined, { signal: opts.signal })
  }
  return data as T
}

export async function requestBlob(method: string, url: string, init: RequestInitLite = {}): Promise<Blob> {
  return (await requestResponse(method, url, init)).blob()
}

export async function requestArrayBuffer(method: string, url: string, init: RequestInitLite = {}): Promise<ArrayBuffer> {
  return (await requestResponse(method, url, init)).arrayBuffer()
}

export const http = {
  get: <T = any>(url: string, signal?: AbortSignal) => request<T>('GET', url, undefined, { signal }),
  post: <T = any>(url: string, body?: unknown) => request<T>('POST', url, body ?? {}),
  postRaw: <T = any>(url: string, body: string) => request<T>('POST', url, body, { raw: true }),
  postBlob: <T = any>(url: string, body: Blob) => request<T>('POST', url, body),
  put: <T = any>(url: string, body?: unknown) => request<T>('PUT', url, body ?? {}),
  putRaw: <T = any>(url: string, body: string) => request<T>('PUT', url, body, { raw: true }),
  patch: <T = any>(url: string, body?: unknown) => request<T>('PATCH', url, body ?? {}),
  del: <T = any>(url: string) => request<T>('DELETE', url),
}

/**
 * 读取 NDJSON 流（搜索、书源调试）
 */
export async function streamNdjson<T = unknown>(
  method: 'GET' | 'POST',
  url: string,
  body: unknown,
  onItem: (item: T) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await requestResponse(method, url, { body, signal })
  if (!res.body) throw new ApiError(res.status, t('请求失败 ({0})', [res.status]))
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).trim()
      buf = buf.slice(idx + 1)
      if (line) {
        try {
          onItem(JSON.parse(line))
        } catch {
          // 单行解析失败不影响后续事件
        }
      }
    }
  }
  if (buf.trim()) {
    try {
      onItem(JSON.parse(buf))
    } catch {
      // 末尾不完整的一行（连接中断等）直接丢弃
    }
  }
}

/** 通过后端代理加载图片（带书源请求头、cookie，支持封面解密） */
export function proxyImage(
  url: string | null | undefined,
  opts: { sourceId?: number | null; origin?: string; bookId?: number; cover?: boolean } = {},
): string {
  if (!url) return ''
  if (url.startsWith('data:image')) return url
  // 本站接口返回的图片（本地书籍的插图、封面）：直接请求，带上登录凭据
  if (url.startsWith('/api/')) return url + (url.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(getToken())
  const p = new URLSearchParams({ url, token: getToken() })
  if (opts.sourceId) p.set('sourceId', String(opts.sourceId))
  if (opts.origin) p.set('origin', opts.origin)
  if (opts.bookId) p.set('bookId', String(opts.bookId))
  if (opts.cover) p.set('cover', 'true')
  return `/api/proxy/image?${p.toString()}`
}
