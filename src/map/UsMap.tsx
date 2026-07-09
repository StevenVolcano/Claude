import { useEffect, useRef } from 'react'
import L from 'leaflet'
import type { FeatureCollection, Geometry } from 'geojson'
import { Capitol, TravelData } from '../types'
import { InterstateProps, RegionProps } from '../data/load'
import { TravelActions } from '../state/useTravelData'

export interface UsLayerToggles {
  interstates: boolean
  capitols: boolean
}

interface Props {
  states: FeatureCollection<Geometry, RegionProps>
  interstates: FeatureCollection<Geometry, InterstateProps>
  capitols: Capitol[]
  data: TravelData
  actions: TravelActions
  show: UsLayerToggles
}

const LOWER_48_BOUNDS = L.latLngBounds([24.5, -125], [49.5, -66.9])

const stateStyle = (visited: boolean): L.PathOptions => ({
  color: '#5b6b7a',
  weight: 1,
  fillColor: visited ? '#2e8b57' : '#e3e8ee',
  fillOpacity: visited ? 0.65 : 0.45,
})

const interstateStyle = (visited: boolean): L.PathOptions => ({
  color: visited ? '#d03427' : '#8d99a8',
  weight: visited ? 3.5 : 1.75,
  opacity: visited ? 0.95 : 0.6,
})

const capitolStyle = (visited: boolean): L.CircleMarkerOptions => ({
  radius: 6,
  color: '#5d4037',
  weight: 1.5,
  fillColor: visited ? '#f2b705' : '#ffffff',
  fillOpacity: 1,
})

export default function UsMap({ states, interstates, capitols, data, actions, show }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const statesLayerRef = useRef<L.GeoJSON | null>(null)
  const interstatesLayerRef = useRef<L.GeoJSON | null>(null)
  const capitolMarkersRef = useRef<Map<string, L.CircleMarker>>(new Map())
  const capitolGroupRef = useRef<L.LayerGroup | null>(null)

  // Actions/data change every render; keep the latest in refs so the Leaflet
  // layers (created once) always call the current versions.
  const actionsRef = useRef(actions)
  actionsRef.current = actions
  const dataRef = useRef(data)
  dataRef.current = data

  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    const map = L.map(container, {
      zoomSnap: 0.25,
      attributionControl: false,
      zoomControl: true,
    })
    map.fitBounds(LOWER_48_BOUNDS)
    mapRef.current = map

    const statesLayer = L.geoJSON(states, {
      style: (f) => stateStyle(!!dataRef.current.states[f?.properties.key]),
      onEachFeature: (f, layer) => {
        layer.bindTooltip(f.properties.name, { sticky: true })
        layer.on('click', () => actionsRef.current.toggleState(f.properties.key))
      },
    }).addTo(map)
    statesLayerRef.current = statesLayer

    const interstatesLayer = L.geoJSON(interstates, {
      style: (f) => {
        const p = f?.properties as InterstateProps
        return interstateStyle(!!dataRef.current.interstates[p.route]?.includes(p.state))
      },
      onEachFeature: (f, layer) => {
        const p = f.properties as InterstateProps
        layer.bindTooltip(`${p.route} — ${p.state}`, { sticky: true })
        layer.on('click', () => actionsRef.current.toggleInterstate(p.route, p.state))
      },
    })
    interstatesLayerRef.current = interstatesLayer

    const capitolGroup = L.layerGroup()
    for (const c of capitols) {
      const marker = L.circleMarker([c.lat, c.lng], capitolStyle(!!dataRef.current.capitols[c.state]))
      marker.bindTooltip(`${c.building}<br>${c.city}, ${c.state}`)
      marker.on('click', () => actionsRef.current.toggleCapitol(c.state))
      marker.addTo(capitolGroup)
      capitolMarkersRef.current.set(c.state, marker)
    }
    capitolGroupRef.current = capitolGroup

    return () => {
      map.remove()
      mapRef.current = null
      capitolMarkersRef.current = new Map()
    }
    // Base layers are static datasets; the map is built once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [states, interstates, capitols])

  // Restyle on visited-data changes
  useEffect(() => {
    statesLayerRef.current?.eachLayer((layer) => {
      const f = (layer as L.Path & { feature: GeoJSON.Feature<Geometry, RegionProps> }).feature
      ;(layer as L.Path).setStyle(stateStyle(!!data.states[f.properties.key]))
    })
    interstatesLayerRef.current?.eachLayer((layer) => {
      const f = (layer as L.Path & { feature: GeoJSON.Feature<Geometry, InterstateProps> }).feature
      const p = f.properties
      ;(layer as L.Path).setStyle(interstateStyle(!!data.interstates[p.route]?.includes(p.state)))
    })
    for (const [state, marker] of capitolMarkersRef.current) {
      marker.setStyle(capitolStyle(!!data.capitols[state]))
    }
  }, [data])

  // Layer visibility toggles
  useEffect(() => {
    const map = mapRef.current
    const layer = interstatesLayerRef.current
    if (!map || !layer) return
    if (show.interstates) layer.addTo(map)
    else map.removeLayer(layer)
  }, [show.interstates])

  useEffect(() => {
    const map = mapRef.current
    const group = capitolGroupRef.current
    if (!map || !group) return
    if (show.capitols) group.addTo(map)
    else map.removeLayer(group)
  }, [show.capitols])

  return <div ref={containerRef} className="map-container" />
}
