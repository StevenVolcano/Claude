import { useEffect, useRef } from 'react'
import L from 'leaflet'
import type { FeatureCollection, Geometry } from 'geojson'
import { TravelData } from '../types'
import { RegionProps } from '../data/load'
import { TravelActions } from '../state/useTravelData'

interface Props {
  countries: FeatureCollection<Geometry, RegionProps>
  data: TravelData
  actions: TravelActions
}

const countryStyle = (visited: boolean): L.PathOptions => ({
  color: '#5b6b7a',
  weight: 0.75,
  fillColor: visited ? '#2e6da4' : '#e3e8ee',
  fillOpacity: visited ? 0.7 : 0.45,
})

export default function WorldMap({ countries, data, actions }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const layerRef = useRef<L.GeoJSON | null>(null)

  const actionsRef = useRef(actions)
  actionsRef.current = actions
  const dataRef = useRef(data)
  dataRef.current = data

  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    const map = L.map(container, {
      attributionControl: false,
      minZoom: 1,
      worldCopyJump: true,
    }).setView([25, 10], 2)

    const layer = L.geoJSON(countries, {
      style: (f) => countryStyle(!!dataRef.current.countries[f?.properties.key]),
      onEachFeature: (f, l) => {
        l.bindTooltip(f.properties.name, { sticky: true })
        l.on('click', () => actionsRef.current.toggleCountry(f.properties.key))
      },
    }).addTo(map)
    layerRef.current = layer

    return () => {
      map.remove()
      layerRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [countries])

  useEffect(() => {
    layerRef.current?.eachLayer((layer) => {
      const f = (layer as L.Path & { feature: GeoJSON.Feature<Geometry, RegionProps> }).feature
      ;(layer as L.Path).setStyle(countryStyle(!!data.countries[f.properties.key]))
    })
  }, [data])

  return <div ref={containerRef} className="map-container" />
}
