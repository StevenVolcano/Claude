// Prepares the static base layers checked into public/data/:
//   us-states.json      — TopoJSON, 50 states, properties {key: USPS abbr, name}
//   world-countries.json — TopoJSON, properties {key: ISO numeric (or patch), name}
//   capitols.json       — 50 state capitol buildings with coordinates
//
// Sources: us-atlas / world-atlas npm packages (Census + Natural Earth, public
// domain) and a seed CSV of capitol coordinates cross-checked by hand.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { FIPS_TO_ABBR, ABBR_TO_NAME, NAME_TO_ABBR } from './fips.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const outDir = join(root, 'public', 'data')
mkdirSync(outDir, { recursive: true })

// ---- US states ----
const statesTopo = JSON.parse(readFileSync(join(root, 'node_modules/us-atlas/states-10m.json'), 'utf8'))
statesTopo.objects.states.geometries = statesTopo.objects.states.geometries
  .filter((g) => FIPS_TO_ABBR[g.id])
  .map((g) => {
    const key = FIPS_TO_ABBR[g.id]
    return { ...g, id: undefined, properties: { key, name: ABBR_TO_NAME[key] } }
  })
writeFileSync(join(outDir, 'us-states.json'), JSON.stringify(statesTopo))
console.log(`us-states.json: ${statesTopo.objects.states.geometries.length} states`)

// ---- World countries ----
// world-atlas keys features by ISO 3166-1 numeric code; a few features have no
// id in Natural Earth and get stable hand-assigned keys instead.
const ID_PATCHES = { Kosovo: 'XK', Somaliland: 'XS', 'N. Cyprus': 'XN' }
const countriesTopo = JSON.parse(
  readFileSync(join(root, 'node_modules/world-atlas/countries-110m.json'), 'utf8'),
)
const seenKeys = new Set()
countriesTopo.objects.countries.geometries = countriesTopo.objects.countries.geometries
  .map((g) => {
    const name = g.properties?.name ?? 'Unknown'
    const key = g.id && g.id !== '-99' ? String(g.id) : ID_PATCHES[name]
    if (!key) {
      console.warn(`  skipping country with no usable id: ${name}`)
      return null
    }
    if (seenKeys.has(key)) {
      console.warn(`  duplicate country key ${key} (${name})`)
    }
    seenKeys.add(key)
    return { ...g, id: undefined, properties: { key, name } }
  })
  .filter(Boolean)
writeFileSync(join(outDir, 'world-countries.json'), JSON.stringify(countriesTopo))
console.log(`world-countries.json: ${countriesTopo.objects.countries.geometries.length} countries`)

// ---- State capitol buildings ----
// Seed CSV: jasperdebie/VisInfo us-state-capitals.csv (building-level coords,
// spot-checked against Wikipedia's list of state capitols).
const NON_STANDARD_BUILDINGS = {
  DE: 'Delaware Legislative Hall',
  IN: 'Indiana Statehouse',
  MD: 'Maryland State House',
  MA: 'Massachusetts State House',
  NH: 'New Hampshire State House',
  NJ: 'New Jersey State House',
  OH: 'Ohio Statehouse',
  RI: 'Rhode Island State House',
  SC: 'South Carolina State House',
  VT: 'Vermont State House',
}
const csv = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'us-state-capitals-seed.csv'), 'utf8')
const capitols = csv
  .trim()
  .split('\n')
  .slice(1) // header
  .map((line) => {
    const [stateName, city, lat, lng] = line.split(',').map((s) => s.replace(/<br>/g, '').trim())
    const state = NAME_TO_ABBR[stateName]
    if (!state) throw new Error(`Unknown state in capitals CSV: ${stateName}`)
    return {
      state,
      stateName,
      city,
      building: NON_STANDARD_BUILDINGS[state] ?? `${stateName} State Capitol`,
      lat: Number(lat),
      lng: Number(lng),
    }
  })
  .sort((a, b) => a.stateName.localeCompare(b.stateName))
if (capitols.length !== 50) throw new Error(`Expected 50 capitols, got ${capitols.length}`)
writeFileSync(join(outDir, 'capitols.json'), JSON.stringify(capitols, null, 1))
console.log(`capitols.json: ${capitols.length} capitols`)
