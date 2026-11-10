import type { AiRepairEvent } from './index'

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

export function parseAiRepairEvent(value: unknown): AiRepairEvent | null {
  if (!record(value)) return null
  switch (value.type) {
    case 'end': return { type: 'end' }
    case 'log': {
      const { message, stage, round } = value
      if (typeof message !== 'string' || (stage !== undefined && typeof stage !== 'string') || (round !== undefined && (typeof round !== 'number' || !Number.isFinite(round)))) return null
      return { type: 'log', message, ...(stage !== undefined ? { stage } : {}), ...(round !== undefined ? { round } : {}) }
    }
    case 'error':
      return typeof value.message === 'string' ? { type: 'error', message: value.message, ...(value.code === 'challenge' ? { code: 'challenge' } : {}) } : null
    case 'detect-failed':
      return typeof value.stage === 'string' && typeof value.error === 'string' ? { type: 'detect-failed', stage: value.stage, error: value.error } : null
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
      if (!record(evidence) || (evidence.stage !== null && typeof evidence.stage !== 'string') || typeof evidence.error !== 'string' || (evidence.errorType !== null && typeof evidence.errorType !== 'string') || !Array.isArray(evidence.requests) || !record(evidence.hits)) return null
      const requests = []
      for (const request of evidence.requests) {
        if (!record(request) || typeof request.stage !== 'string' || typeof request.method !== 'string' || typeof request.url !== 'string' || typeof request.status !== 'number' || typeof request.body !== 'string') return null
        requests.push({ ...request, stage: request.stage, method: request.method, url: request.url, status: request.status, body: request.body })
      }
      const hits: Record<string, number> = {}
      for (const [key, hit] of Object.entries(evidence.hits)) {
        if (typeof hit !== 'number' || !Number.isFinite(hit)) return null
        hits[key] = hit
      }
      return { type: 'evidence', evidence: { ...evidence, stage: evidence.stage, error: evidence.error, errorType: evidence.errorType, requests, hits } }
    }
    default: return null
  }
}
