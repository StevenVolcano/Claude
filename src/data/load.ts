import { feature } from 'topojson-client'
import type { Topology, GeometryCollection } from 'topojson-specification'
import type { FeatureCollection, Geometry } from 'geojson'
import { Capitol, InterstateNetwork } from '../types'

export interface RegionProps {
  key: string
  name: string
}

export interface InterstateProps {
  route: string
  state: string
}

const base = import.meta.env.BASE_URL

const cache = new Map<string, Promise<unknown>>()

function fetchJson<T>(path: string): Promise<T> {
  let p = cache.get(path)
  if (!p) {
    p = fetch(base + path).then((r) => {
      if (!r.ok) throw new Error(`Failed to load ${path}: ${r.status}`)
      return r.json()
    })
    cache.set(path, p)
  }
  return p as Promise<T>
}

async function fetchTopology(path: string, objectName: string): Promise<FeatureCollection<Geometry, RegionProps>> {
  const topo = await fetchJson<Topology>(path)
  const object = topo.objects[objectName] as GeometryCollection<RegionProps>
  return feature(topo, object) as FeatureCollection<Geometry, RegionProps>
}

export const loadStates = () => fetchTopology('data/us-states.json', 'states')
export const loadCountries = () =>
  fetchTopology('data/world-countries.json', 'countries').then(unwrapAntimeridian)

/**
 * Countries crossing the antimeridian (Russia, Fiji) otherwise render as
 * full-width streaks in Leaflet. Shift their western-hemisphere longitudes
 * by +360 so each ring is contiguous. Antarctica legitimately spans all
 * longitudes and is left alone.
 */
function unwrapAntimeridian(
  fc: FeatureCollection<Geometry, RegionProps>,
): FeatureCollection<Geometry, RegionProps> {
  for (const f of fc.features) {
    if (f.properties.key === '010') continue // Antarctica
    const polygons =
      f.geometry.type === 'Polygon'
        ? [f.geometry.coordinates]
        : f.geometry.type === 'MultiPolygon'
          ? f.geometry.coordinates
          : []
    for (const polygon of polygons) {
      for (const ring of polygon) {
        let min = Infinity
        let max = -Infinity
        for (const [lon] of ring) {
          if (lon < min) min = lon
          if (lon > max) max = lon
        }
        if (max - min > 300) {
          for (const point of ring) {
            if (point[0] < 0) point[0] += 360
          }
        }
      }
    }
  }
  return fc
}
export const loadInterstates = () =>
  fetchJson<FeatureCollection<Geometry, InterstateProps>>('data/interstates-by-state.json')
export const loadInterstateNetwork = () => fetchJson<InterstateNetwork>('data/interstate-states.json')
export const loadCapitols = () => fetchJson<Capitol[]>('data/capitols.json')
