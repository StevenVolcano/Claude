// Builds the interstate layers checked into public/data/:
//   interstates-by-state.json — GeoJSON, one feature per (route, state), simplified
//   interstate-states.json    — route -> [state abbrs], drives the checklist
//
// Source: Natural Earth ne_10m_roads_north_america (public domain), which
// attributes each road segment with class/prefix/number/state. Download the
// .shp/.shx/.dbf/.prj into scripts/prepare-data/raw/ first:
//   https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/10m_cultural/ne_10m_roads_north_america.<ext>
// (The fresher BTS/FHWA NHPN dataset is preferred if you can reach geodata.bts.gov;
// this environment could not, so NE + the patch table below is used instead.)
//
// Only mainline interstates are kept — 1-2 digit routes plus Hawaii's H-1..H-3.
// Three-digit auxiliary routes (belts/spurs) and business loops are dropped.
// Suffixed branches (I-35E/I-35W) are folded into their parent route.
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import mapshaper from 'mapshaper'
import { NAME_TO_ABBR } from './fips.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..', '..')
const shp = join(here, 'raw', 'ne_10m_roads_north_america.shp')
const outDir = join(root, 'public', 'data')

// Natural Earth mislabels these segments; the routes do not enter these states.
const DATA_ERRORS = [
  ['I-27', 'Kentucky'],
  ['I-75', 'Oklahoma'],
]

// Mainline routes/extensions newer than the ~2012 Natural Earth roads vintage.
// These appear in the checklist but have no geometry on the map.
const PATCHES = {
  'I-2': ['TX'],
  'I-11': ['NV'],
  'I-14': ['TX'],
  'I-22': ['MS', 'AL'],
  'I-41': ['WI'],
  'I-49': ['AR', 'MO'],
  'I-69': ['KY', 'TX'],
  'I-87': ['NC'],
  'I-99': ['NY'],
}

if (!existsSync(shp)) {
  console.error(`Missing ${shp} — download the Natural Earth roads shapefile first (see header).`)
  process.exit(1)
}

const routeExpr = `route = (function () {
  var m = /^(\\d{1,2})[EW]?$/.exec(number)
  if (m) return 'I-' + m[1]
  m = /^H([123])$/.exec(number)
  return m ? 'H-' + m[1] : null
})()`

const errorExpr = DATA_ERRORS.map(
  ([route, state]) => `!(route == '${route}' && state == '${state}')`,
).join(' && ')

const tmpOut = join(here, 'raw', 'interstates-dissolved.geojson')
await mapshaper.runCommands(
  [
    `-i ${shp}`,
    `-filter "this.properties.class == 'Interstate' && country == 'United States' && prefix == 'I'"`,
    `-each "${routeExpr}"`,
    `-filter "route != null && ${errorExpr}"`,
    '-filter-fields route,state',
    '-dissolve route,state',
    '-simplify visvalingam weighted 8% keep-shapes',
    `-o precision=0.0001 format=geojson ${tmpOut}`,
  ].join(' '),
)

const dissolved = JSON.parse(readFileSync(tmpOut, 'utf8'))
// DC is not in the 50-state table but I-66 and I-95 legitimately touch it.
const STATE_NAMES = { ...NAME_TO_ABBR, 'District of Columbia': 'DC' }
const features = dissolved.features
  .map((f) => {
    const abbr = STATE_NAMES[f.properties.state]
    if (!abbr) throw new Error(`Unmapped state name: ${f.properties.state}`)
    return { ...f, properties: { route: f.properties.route, state: abbr } }
  })
  .sort((a, b) => routeOrder(a.properties.route, b.properties.route) || a.properties.state.localeCompare(b.properties.state))

writeFileSync(
  join(outDir, 'interstates-by-state.json'),
  JSON.stringify({ type: 'FeatureCollection', features }),
)

const network = {}
for (const f of features) {
  ;(network[f.properties.route] ??= []).push(f.properties.state)
}
for (const [route, states] of Object.entries(PATCHES)) {
  network[route] = [...new Set([...(network[route] ?? []), ...states])]
}
for (const route of Object.keys(network)) network[route].sort()
const ordered = Object.fromEntries(
  Object.keys(network)
    .sort(routeOrder)
    .map((r) => [r, network[r]]),
)
writeFileSync(join(outDir, 'interstate-states.json'), JSON.stringify(ordered, null, 1))

const segTotal = Object.values(ordered).reduce((n, s) => n + s.length, 0)
console.log(
  `interstates-by-state.json: ${features.length} features; interstate-states.json: ${Object.keys(ordered).length} routes, ${segTotal} (route,state) segments`,
)

function routeOrder(a, b) {
  const [pa, na] = a.split('-')
  const [pb, nb] = b.split('-')
  return pa === pb ? Number(na) - Number(nb) : pa.localeCompare(pb)
}
