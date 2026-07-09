import { TravelData, emptyTravelData } from '../types'

const STORAGE_KEY = 'travel-tracker-data'

export function loadData(): TravelData {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return emptyTravelData()
    return validateData(JSON.parse(raw))
  } catch {
    return emptyTravelData()
  }
}

export function saveData(data: TravelData): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(data))
}

/**
 * Validates an imported/stored document and normalizes it to the current
 * schema. Throws with a readable message on malformed input.
 */
export function validateData(input: unknown): TravelData {
  if (typeof input !== 'object' || input === null) {
    throw new Error('Not a JSON object')
  }
  const doc = input as Record<string, unknown>
  if (doc.schemaVersion !== 1) {
    throw new Error(`Unsupported schemaVersion: ${String(doc.schemaVersion)}`)
  }
  const out = emptyTravelData()
  for (const key of ['states', 'capitols', 'countries'] as const) {
    const section = doc[key]
    if (section === undefined) continue
    if (typeof section !== 'object' || section === null || Array.isArray(section)) {
      throw new Error(`"${key}" must be an object`)
    }
    for (const [k, v] of Object.entries(section)) {
      if (v) out[key][k] = true
    }
  }
  if (doc.interstates !== undefined) {
    if (typeof doc.interstates !== 'object' || doc.interstates === null || Array.isArray(doc.interstates)) {
      throw new Error('"interstates" must be an object')
    }
    for (const [route, states] of Object.entries(doc.interstates)) {
      if (!Array.isArray(states) || states.some((s) => typeof s !== 'string')) {
        throw new Error(`interstates["${route}"] must be an array of state abbreviations`)
      }
      if (states.length > 0) out.interstates[route] = [...new Set(states as string[])].sort()
    }
  }
  return out
}

export function exportToFile(data: TravelData): void {
  const json = JSON.stringify(data, null, 2)
  const blob = new Blob([json], { type: 'application/json' })
  const file = new File([blob], 'travel-data.json', { type: 'application/json' })
  // On Android, sharing lets the user send the file straight to Drive.
  if (navigator.canShare?.({ files: [file] })) {
    navigator.share({ files: [file], title: 'Travel data' }).catch((err) => {
      if (err instanceof DOMException && err.name === 'AbortError') return
      downloadBlob(blob)
    })
  } else {
    downloadBlob(blob)
  }
}

function downloadBlob(blob: Blob): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'travel-data.json'
  a.click()
  URL.revokeObjectURL(url)
}

export async function importFromFile(file: File): Promise<TravelData> {
  const text = await file.text()
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error('File is not valid JSON')
  }
  return validateData(parsed)
}
