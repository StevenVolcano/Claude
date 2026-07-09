import { useMemo, useState } from 'react'
import { Capitol, InterstateNetwork, TravelData } from '../types'
import { RegionProps } from '../data/load'
import { TravelActions } from '../state/useTravelData'
import type { FeatureCollection, Geometry } from 'geojson'

interface Props {
  states: FeatureCollection<Geometry, RegionProps>
  countries: FeatureCollection<Geometry, RegionProps> | null
  network: InterstateNetwork
  capitols: Capitol[]
  data: TravelData
  actions: TravelActions
}

type Section = 'states' | 'interstates' | 'capitols' | 'countries'

export default function Checklists({ states, countries, network, capitols, data, actions }: Props) {
  const [section, setSection] = useState<Section>('states')
  const [search, setSearch] = useState('')

  const stateList = useMemo(
    () =>
      states.features
        .map((f) => ({ key: f.properties.key, name: f.properties.name }))
        .sort((a, b) => a.name.localeCompare(b.name)),
    [states],
  )

  const countryList = useMemo(
    () =>
      (countries?.features ?? [])
        .map((f) => ({ key: f.properties.key, name: f.properties.name }))
        .sort((a, b) => a.name.localeCompare(b.name)),
    [countries],
  )

  const routes = useMemo(
    () =>
      Object.keys(network).sort((a, b) => {
        const [pa, na] = splitRoute(a)
        const [pb, nb] = splitRoute(b)
        return pa === pb ? na - nb : pa.localeCompare(pb)
      }),
    [network],
  )

  const q = search.trim().toLowerCase()

  const counts = {
    states: Object.keys(data.states).length,
    interstates: Object.keys(data.interstates).length,
    capitols: Object.keys(data.capitols).length,
    countries: Object.keys(data.countries).length,
  }

  return (
    <div className="checklists">
      <nav className="subtabs">
        <button className={section === 'states' ? 'active' : ''} onClick={() => setSection('states')}>
          States {counts.states}/{stateList.length}
        </button>
        <button className={section === 'interstates' ? 'active' : ''} onClick={() => setSection('interstates')}>
          Interstates {counts.interstates}/{routes.length}
        </button>
        <button className={section === 'capitols' ? 'active' : ''} onClick={() => setSection('capitols')}>
          Capitols {counts.capitols}/{capitols.length}
        </button>
        <button className={section === 'countries' ? 'active' : ''} onClick={() => setSection('countries')}>
          Countries {counts.countries}/{countryList.length || '—'}
        </button>
      </nav>

      {(section === 'countries' || section === 'interstates') && (
        <input
          className="search"
          type="search"
          placeholder={section === 'countries' ? 'Search countries…' : 'Search routes (e.g. I-80)…'}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      )}

      {section === 'states' && (
        <ul className="check-grid">
          {stateList.map((s) => (
            <CheckItem
              key={s.key}
              label={s.name}
              checked={!!data.states[s.key]}
              onToggle={() => actions.toggleState(s.key)}
            />
          ))}
        </ul>
      )}

      {section === 'capitols' && (
        <ul className="check-grid">
          {capitols.map((c) => (
            <CheckItem
              key={c.state}
              label={`${c.stateName} — ${c.city}`}
              checked={!!data.capitols[c.state]}
              onToggle={() => actions.toggleCapitol(c.state)}
            />
          ))}
        </ul>
      )}

      {section === 'countries' &&
        (countryList.length === 0 ? (
          <p className="hint">Loading countries…</p>
        ) : (
          <ul className="check-grid">
            {countryList
              .filter((c) => !q || c.name.toLowerCase().includes(q))
              .map((c) => (
                <CheckItem
                  key={c.key}
                  label={c.name}
                  checked={!!data.countries[c.key]}
                  onToggle={() => actions.toggleCountry(c.key)}
                />
              ))}
          </ul>
        ))}

      {section === 'interstates' && (
        <div className="route-list">
          {routes
            .filter((r) => !q || r.toLowerCase().includes(q))
            .map((route) => {
              const allStates = network[route]
              const visited = data.interstates[route] ?? []
              const complete = visited.length === allStates.length
              return (
                <div key={route} className="route-row">
                  <div className="route-head">
                    <span className={`route-badge ${visited.length > 0 ? 'partial' : ''} ${complete ? 'complete' : ''}`}>
                      {route}
                    </span>
                    <span className="route-progress">
                      {visited.length}/{allStates.length} states
                    </span>
                    <button
                      className="route-all"
                      onClick={() => actions.setInterstateAll(route, allStates, !complete)}
                    >
                      {complete ? 'Clear all' : 'Mark all'}
                    </button>
                  </div>
                  <div className="chips">
                    {allStates.map((st) => (
                      <button
                        key={st}
                        className={`chip ${visited.includes(st) ? 'on' : ''}`}
                        onClick={() => actions.toggleInterstate(route, st)}
                      >
                        {st}
                      </button>
                    ))}
                  </div>
                </div>
              )
            })}
        </div>
      )}
    </div>
  )
}

function CheckItem({ label, checked, onToggle }: { label: string; checked: boolean; onToggle: () => void }) {
  return (
    <li>
      <label className={`check-item ${checked ? 'on' : ''}`}>
        <input type="checkbox" checked={checked} onChange={onToggle} />
        <span>{label}</span>
      </label>
    </li>
  )
}

function splitRoute(route: string): [string, number] {
  const m = route.match(/^([A-Z]+)-(\d+)/)
  return m ? [m[1], Number(m[2])] : [route, 0]
}
