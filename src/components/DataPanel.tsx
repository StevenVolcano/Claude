import { useRef, useState } from 'react'
import { TravelData, emptyTravelData } from '../types'
import { exportToFile, importFromFile } from '../state/storage'
import { TravelActions } from '../state/useTravelData'

interface Props {
  data: TravelData
  actions: TravelActions
}

export default function DataPanel({ data, actions }: Props) {
  const fileRef = useRef<HTMLInputElement>(null)
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)

  async function handleImport(file: File) {
    try {
      const imported = await importFromFile(file)
      const summary = describe(imported)
      if (!window.confirm(`Replace your current data with this file?\n\n${summary}`)) return
      actions.replaceAll(imported)
      setMessage({ kind: 'ok', text: `Imported: ${summary}` })
    } catch (err) {
      setMessage({ kind: 'error', text: `Import failed: ${err instanceof Error ? err.message : String(err)}` })
    }
  }

  function handleReset() {
    if (!window.confirm('Erase ALL travel data on this device? Export a backup first if you want to keep it.')) return
    actions.replaceAll(emptyTravelData())
    setMessage({ kind: 'ok', text: 'All data cleared.' })
  }

  return (
    <div className="data-panel">
      <h2>Your data</h2>
      <p className="stats-line">{describe(data)}</p>
      <p className="hint">
        Data is saved automatically on this device (browser storage). To back it up or move it to another
        device, export it as a JSON file — on Android you can share it straight into Google Drive — and
        import it on the other side.
      </p>
      <div className="button-row">
        <button className="primary" onClick={() => exportToFile(data)}>
          Export / share backup
        </button>
        <button onClick={() => fileRef.current?.click()}>Import backup…</button>
        <button className="danger" onClick={handleReset}>
          Reset all data
        </button>
      </div>
      <input
        ref={fileRef}
        type="file"
        accept=".json,application/json"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) handleImport(file)
          e.target.value = ''
        }}
      />
      {message && <p className={`message ${message.kind}`}>{message.text}</p>}
    </div>
  )
}

function describe(d: TravelData): string {
  const segments = Object.values(d.interstates).reduce((n, states) => n + states.length, 0)
  return [
    `${Object.keys(d.states).length} states`,
    `${Object.keys(d.interstates).length} interstates (${segments} state segments)`,
    `${Object.keys(d.capitols).length} capitols`,
    `${Object.keys(d.countries).length} countries`,
  ].join(' · ')
}
