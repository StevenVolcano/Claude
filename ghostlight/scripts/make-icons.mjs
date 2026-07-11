// Generate the PWA PNG icons (ghost light on dark) without any image
// dependencies: draw into an RGBA buffer, encode PNG with built-in zlib.
// Run: node scripts/make-icons.mjs
import { deflateSync } from 'node:zlib'
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const outDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'public', 'icons')

const BG = [26, 20, 35] // #1a1423
const LAMP = [232, 163, 61] // #e8a33d
const BULB = [246, 199, 107] // #f6c76b
const METAL = [143, 134, 168] // #8f86a8

function makeIcon(size, { rounded }) {
  const px = new Uint8Array(size * size * 4)
  const s = size / 512 // all geometry defined on a 512 grid
  const radius = rounded ? 96 * s : 0

  const inRoundedRect = (x, y) => {
    if (!rounded) return true
    const rx = Math.max(radius - x, x - (size - radius), 0)
    const ry = Math.max(radius - y, y - (size - radius), 0)
    return rx * rx + ry * ry <= radius * radius
  }
  const inCircle = (x, y, cx, cy, r) => {
    const dx = x - cx * s
    const dy = y - cy * s
    return dx * dx + dy * dy <= r * s * (r * s)
  }
  const inRect = (x, y, rx, ry, rw, rh) =>
    x >= rx * s && x <= (rx + rw) * s && y >= ry * s && y <= (ry + rh) * s

  const blend = (base, top, a) => base.map((c, i) => Math.round(c * (1 - a) + top[i] * a))

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const i = (y * size + x) * 4
      if (!inRoundedRect(x, y)) {
        px[i + 3] = 0
        continue
      }
      let c = BG
      if (inCircle(x, y, 256, 168, 120)) c = blend(c, LAMP, 0.15)
      if (inCircle(x, y, 256, 168, 80)) c = blend(c, LAMP, 0.25)
      if (inCircle(x, y, 256, 168, 47)) c = LAMP
      if (inCircle(x, y, 256, 168, 41)) c = BULB
      if (inRect(x, y, 248, 212, 16, 180)) c = METAL
      if (inRect(x, y, 208, 374, 96, 20)) c = METAL
      if (inRect(x, y, 176, 392, 160, 20)) c = METAL
      px[i] = c[0]
      px[i + 1] = c[1]
      px[i + 2] = c[2]
      px[i + 3] = 255
    }
  }
  return encodePng(size, size, px)
}

function encodePng(width, height, rgba) {
  const raw = Buffer.alloc(height * (1 + width * 4))
  for (let y = 0; y < height; y++) {
    raw[y * (1 + width * 4)] = 0 // filter: none
    rgba.subarray(y * width * 4, (y + 1) * width * 4).forEach((v, k) => {
      raw[y * (1 + width * 4) + 1 + k] = v
    })
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(width, 0)
  ihdr.writeUInt32BE(height, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 6 // color type RGBA
  const chunks = [
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]
  return Buffer.concat(chunks)
}

function chunk(type, data) {
  const out = Buffer.alloc(12 + data.length)
  out.writeUInt32BE(data.length, 0)
  out.write(type, 4, 'ascii')
  data.copy(out, 8)
  out.writeUInt32BE(crc32(out.subarray(4, 8 + data.length)), 8 + data.length)
  return out
}

let crcTable
function crc32(buf) {
  if (!crcTable) {
    crcTable = new Uint32Array(256)
    for (let n = 0; n < 256; n++) {
      let c = n
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
      crcTable[n] = c
    }
  }
  let c = 0xffffffff
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

writeFileSync(join(outDir, 'icon-192.png'), makeIcon(192, { rounded: false }))
writeFileSync(join(outDir, 'icon-512.png'), makeIcon(512, { rounded: false }))
// apple-touch-icon: iOS applies its own mask; keep it square full-bleed
writeFileSync(join(outDir, 'apple-touch-icon.png'), makeIcon(180, { rounded: false }))
console.log('icons written to', outDir)
