import type { AiRepairEvent, AiRepairStage } from './index'

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isStage(value: unknown): value is AiRepairStage {
  return value === 'search' || value === 'bookInfo' || value === 'toc' || value === 'content'
}

function isRound(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1 && value <= 3
}

export function parseAiRepairEvent(value: unknown): AiRepairEvent | null {
  if (!record(value)) return null
  switch (value.type) {
    case 'end': return { type: 'end' }
    case 'progress': {
      const { phase, stage } = value
      if (!isStage(stage)) return null
      if (phase === 'check') return { type: 'progress', phase, stage }
      if (phase === 'model' && isRound(value.round)) return { type: 'progress', phase, stage, round: value.round }
      return null
    }
    case 'log': {
      const { message, stage, round } = value
      if (typeof message !== 'string' || (stage !== undefined && !isStage(stage)) || (round !== undefined && !isRound(round))) return null
      return { type: 'log', message, ...(stage !== undefined ? { stage } : {}), ...(round !== undefined ? { round } : {}) }
    }
    case 'error':
      return typeof value.message === 'string' ? { type: 'error', message: value.message, ...(value.code === 'challenge' ? { code: 'challenge' } : {}) } : null
    case 'detect-failed':
      return isStage(value.stage) && typeof value.error === 'string'
        ? {
            type: 'detect-failed', stage: value.stage, error: value.error,
            ...(typeof value.errorType === 'string' ? { errorType: value.errorType } : {}),
            ...(value.code === 'needs-verification' || value.code === 'detect-failed' ? { code: value.code } : {}),
          }
        : null
    case 'result':
    case 'awaiting-confirmation': {
      if (typeof value.source !== 'string' || !Array.isArray(value.diff)) return null
      const diff = []
      for (const item of value.diff) {
        if (!record(item) || typeof item.field !== 'string' || typeof item.javascript !== 'boolean') return null
        const old = item.old
        const next = item.new
        if (old !== undefined && old !== null && typeof old !== 'string') return null
        if (next !== undefined && next !== null && typeof next !== 'string') return null
        diff.push({ field: item.field, javascript: item.javascript, old, new: next })
      }
      if (value.type === 'result') return { type: 'result', source: value.source, diff }
      return typeof value.confirmationToken === 'string' ? { type: 'awaiting-confirmation', source: value.source, diff, confirmationToken: value.confirmationToken } : null
    }
    case 'evidence': {
      const evidence = value.evidence
      if (!record(evidence)) return null
      const stage = evidence.stage
      if ((stage !== null && !isStage(stage)) || typeof evidence.error !== 'string' || (evidence.errorType !== null && typeof evidence.errorType !== 'string') || !Array.isArray(evidence.requests) || !record(evidence.hits)) return null
      const requests = []
      for (const request of evidence.requests) {
        if (!record(request)) return null
        const requestStage = request.stage
        if (!isStage(requestStage) || typeof request.method !== 'string' || typeof request.url !== 'string' || typeof request.status !== 'number' || typeof request.body !== 'string') return null
        requests.push({ ...request, stage: requestStage, method: request.method, url: request.url, status: request.status, body: request.body })
      }
      const hits: Record<string, number> = {}
      for (const [key, hit] of Object.entries(evidence.hits)) {
        if (typeof hit !== 'number' || !Number.isFinite(hit)) return null
        hits[key] = hit
      }
      return { type: 'evidence', evidence: { ...evidence, stage, error: evidence.error, errorType: evidence.errorType, requests, hits } }
    }
    default: return null
  }
}
