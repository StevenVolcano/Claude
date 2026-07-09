import { useEffect, useState } from 'react'
import type { FeatureCollection, Geometry } from 'geojson'
import UsMap, { UsLayerToggles } from './map/UsMap'
import WorldMap from './map/WorldMap'
import Checklists from './components/Checklists'
import DataPanel from './components/DataPanel'
import { useTravelData } from './state/useTravelData'
import {
  InterstateProps,
  RegionProps,
  loadCapitols,
  loadCountries,
  loadInterstateNetwork,
  loadInterstates,
  loadStates,
} from './data/load'
import { Capitol, InterstateNetwork } from './types'

type Tab = 'us' | 'world' | 'lists' | 'data'

interface UsData {
  states: FeatureCollection<Geometry, RegionProps>
  interstates: FeatureCollection<Geometry, InterstateProps>
  network: InterstateNetwork
  capitols: Capitol[]
}

export default function App() {
  const [data, actions] = useTravelData()
  const [tab, setTab] = useState<Tab>('us')
  const [show, setShow] = useState<UsLayerToggles>({ interstates: true, capitols: true })

  const [usData, setUsData] = useState<UsData | null>(null)
  const [countries, setCountries] = useState<FeatureCollection<Geometry, RegionProps> | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    Promise.all([loadStates(), loadInterstates(), loadInterstateNetwork(), loadCapitols()])
      .then(([states, interstates, network, capitols]) =>
        setUsData({ states, interstates, network, capitols }),
      )
      .catch((err) => setLoadError(err instanceof Error ? err.message : String(err)))
  }, [])

  // World data is heavier and only needed on some tabs — load lazily.
  useEffect(() => {
    if (countries || (tab !== 'world' && tab !== 'lists')) return
    loadCountries()
      .then(setCountries)
      .catch((err) => setLoadError(err instanceof Error ? err.message : String(err)))
  }, [tab, countries])

  if (loadError) {
    return <div className="fatal">Failed to load map data: {loadError}</div>
  }

  return (
    <div className="app">
      <header className="topbar">
        <h1>Travel Tracker</h1>
        <nav className="tabs">
          <button className={tab === 'us' ? 'active' : ''} onClick={() => setTab('us')}>
            US Map
          </button>
          <button className={tab === 'world' ? 'active' : ''} onClick={() => setTab('world')}>
            World
          </button>
          <button className={tab === 'lists' ? 'active' : ''} onClick={() => setTab('lists')}>
            Lists
          </button>
          <button className={tab === 'data' ? 'active' : ''} onClick={() => setTab('data')}>
            Data
          </button>
        </nav>
      </header>

      <main className="content">
        {tab === 'us' &&
          (usData ? (
            <>
              <div className="layer-toggles">
                <label>
                  <input
                    type="checkbox"
                    checked={show.interstates}
                    onChange={(e) => setShow((s) => ({ ...s, interstates: e.target.checked }))}
                  />
                  Interstates
                </label>
                <label>
                  <input
                    type="checkbox"
                    checked={show.capitols}
                    onChange={(e) => setShow((s) => ({ ...s, capitols: e.target.checked }))}
                  />
                  Capitols
                </label>
                <span className="legend">
                  <i className="swatch state" /> state visited
                  <i className="swatch road" /> interstate driven
                  <i className="swatch capitol" /> capitol visited
                </span>
              </div>
              <UsMap
                states={usData.states}
                interstates={usData.interstates}
                capitols={usData.capitols}
                data={data}
                actions={actions}
                show={show}
              />
            </>
          ) : (
            <p className="hint">Loading map…</p>
          ))}

        {tab === 'world' &&
          (countries ? (
            <WorldMap countries={countries} data={data} actions={actions} />
          ) : (
            <p className="hint">Loading world map…</p>
          ))}

        {tab === 'lists' &&
          (usData ? (
            <Checklists
              states={usData.states}
              countries={countries}
              network={usData.network}
              capitols={usData.capitols}
              data={data}
              actions={actions}
            />
          ) : (
            <p className="hint">Loading…</p>
          ))}

        {tab === 'data' && <DataPanel data={data} actions={actions} />}
      </main>
    </div>
  )
}
