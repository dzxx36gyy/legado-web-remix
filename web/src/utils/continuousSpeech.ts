import { getToken, request, streamNdjson } from '@/api/http'
import { CancelError, type Speaker } from '@/utils/readAloud'
import { t } from '@/i18n'

export interface SpeechPart {
  readonly para: number
  readonly chunk: number
  readonly text: string
  readonly fraction: number
  readonly endFraction: number
}
export interface ContinuousRequest {
  readonly bookId: number
  readonly chapter: number
  readonly parts: readonly SpeechPart[]
  readonly engineId?: number
  readonly voice: string
  readonly speed: number
}
export interface SpeechCue {
  readonly sequence: number
  readonly chapter: number
  readonly part: SpeechPart
  readonly start: number
  readonly end: number
  readonly complete: boolean
}

function cue(value: unknown): value is SpeechCue {
  if (typeof value !== 'object' || value === null) return false
  const part: unknown = Reflect.get(value, 'part')
  return ['sequence', 'chapter', 'start', 'end'].every((key) => Number.isFinite(Reflect.get(value, key))) &&
    typeof part === 'object' && part !== null && typeof Reflect.get(part, 'text') === 'string' &&
    ['para', 'chunk', 'fraction', 'endFraction'].every((key) => Number.isFinite(Reflect.get(part, key)))
}

export class ContinuousSpeaker implements Speaker {
  private readonly audio = new Audio()
  private controller: AbortController | null = null
  private session: string | null = null
  private finish: ((error?: Error) => void) | null = null
  private cues: SpeechCue[] = []
  private done = false
  private paused = false
  private ended = false
  private timer: ReturnType<typeof setInterval> | null = null
  private rate: number

  constructor(private readonly input: () => ContinuousRequest,
    private readonly progress: (cue: SpeechCue, fraction: number) => void, rate: number) {
    this.rate = rate
    this.audio.preload = 'auto'
  }

  async speak(_text: string): Promise<void> {
    this.stop()
    const controller = new AbortController()
    this.controller = controller
    this.paused = false
    this.done = false
    this.ended = false
    this.cues = []
    const response = await request<unknown>('POST', '/api/tts/continuous', { ...this.input(), speed: Math.round(this.rate * 10) }, { signal: controller.signal })
    const id: unknown = typeof response === 'object' && response !== null ? Reflect.get(response, 'id') : null
    if (typeof id !== 'string') throw new Error(t('朗读音频播放失败'))
    if (controller.signal.aborted) {
      await request('DELETE', `/api/tts/continuous/${encodeURIComponent(id)}`)
      throw new CancelError()
    }
    this.session = id
    const path = `/api/tts/continuous/${encodeURIComponent(id)}`
    await new Promise<void>((resolve, reject) => {
      let mediaError: Error | null = null
      const failMedia = (error: Error) => {
        mediaError = error
        if (this.done) this.finish?.(error)
      }
      this.finish = (error) => {
        this.finish = null
        this.stop()
        error ? reject(error) : resolve()
      }
      this.audio.onended = () => {
        this.ended = true
        if (this.done) this.finish?.()
      }
      this.audio.onerror = () => {
        if (controller.signal.aborted) return
        // The next progress event carries the provider/format error before cleanup aborts that stream.
        failMedia(new Error(t('朗读音频播放失败')))
      }
      this.audio.ontimeupdate = () => this.update()
      this.timer = setInterval(() => this.update(), 250)
      streamNdjson<unknown>('GET', `${path}/events`, undefined, (event) => {
        if (controller.signal.aborted) return
        if (typeof event !== 'object' || event === null) throw new Error(t('朗读音频播放失败'))
        const cues: unknown = Reflect.get(event, 'cues')
        const error: unknown = Reflect.get(event, 'error')
        const seconds: unknown = Reflect.get(event, 'seconds')
        if (!Array.isArray(cues) || !cues.every(cue) || typeof seconds !== 'number') throw new Error(t('朗读音频播放失败'))
        const current = this.audio.currentTime
        this.cues = cues.filter((item) => item.end >= current - 2)
        this.done = Reflect.get(event, 'done') === true
        if (Reflect.get(event, 'errorCode') === 'upstream_disconnected') {
          this.finish?.(new Error(t('朗读服务连接中断。点「继续」可从当前段开头重播。')))
        } else if (typeof error === 'string' && error) this.finish?.(new Error(error))
        else if (mediaError && (this.done || seconds > 0)) this.finish?.(mediaError)
        else if (this.done && this.ended) this.finish?.()
        else this.update()
      }, controller.signal).catch((error: unknown) => {
        if (!controller.signal.aborted) this.finish?.(error instanceof Error ? error : new Error(t('朗读音频播放失败')))
      })
      this.audio.src = `${path}/audio?${new URLSearchParams({ token: getToken() })}`
      if (this.paused) this.setPaused(true)
      else this.audio.play().catch((error: unknown) => {
          if (controller.signal.aborted) return
          const failure = error instanceof Error ? error : new Error(t('朗读音频播放失败'))
          if (failure.name === 'NotSupportedError') failMedia(failure)
          else this.finish?.(failure)
        })
    })
  }

  private update() {
    if (this.paused || this.audio.paused) return
    const current = this.audio.currentTime
    const active = this.cues.find((item) => current >= item.start && current < item.end)
    if (active) this.progress(active, active.complete ? Math.max(0, Math.min(1, (current - active.start) / (active.end - active.start))) : 0)
  }

  prefetch(_texts: string[]) {}
  pause() {
    this.paused = true
    this.audio.pause()
    this.setPaused(true)
  }
  resume() {
    this.paused = false
    this.setPaused(false)
    this.audio.play().catch((error: unknown) => this.finish?.(error instanceof Error ? error : new Error(t('朗读音频播放失败'))))
  }
  private setPaused(paused: boolean) {
    if (this.session) request('POST', `/api/tts/continuous/${encodeURIComponent(this.session)}/pause`, { paused }).catch((error: unknown) => {
      this.finish?.(error instanceof Error ? error : new Error(t('朗读音频播放失败')))
    })
  }
  stop() {
    const finish = this.finish
    this.finish = null
    this.controller?.abort()
    this.controller = null
    this.audio.pause()
    this.audio.onended = null
    this.audio.onerror = null
    this.audio.ontimeupdate = null
    this.audio.removeAttribute('src')
    this.audio.load()
    if (this.timer) clearInterval(this.timer)
    this.timer = null
    const id = this.session
    this.session = null
    if (id) request('DELETE', `/api/tts/continuous/${encodeURIComponent(id)}`).catch((error: unknown) => {
      // The media connection is already closed; a failed cleanup cannot resume playback.
      if (error instanceof Error) console.warn('TTS session cleanup failed', error.message)
    })
    finish?.(new CancelError())
  }
  setRate(rate: number) { this.rate = rate }
}
