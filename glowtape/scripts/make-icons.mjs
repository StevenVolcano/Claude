// Generate the PWA PNG icons (a strip of glow tape shining in the dark)
// without image dependencies: draw into an RGBA buffer, encode PNG with
// built-in zlib. Run: node scripts/make-icons.mjs
import { deflateSync } from 'node:zlib'
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const outDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'public', 'icons')

const BG = [22, 24, 31] // #16181f — dark stage floor
const TAPE = [205, 239, 85] // #cdef55
const CORE = [232, 255, 138] // #e8ff8a — bright center of the strip

function makeIcon(size) {
  const px = new Uint8Array(size * size * 4)
  const s = size / 512
  const cx = size / 2
  const cy = size / 2
  const angle = (-35 * Math.PI) / 180
  const cos = Math.cos(angle)
  const sin = Math.sin(angle)

  const blend = (base, top, a) => base.map((c, i) => Math.round(c * (1 - a) + top[i] * a))

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const i = (y * size + x) * 4
      // rotate into tape space: `along` runs the strip, `across` is width
      const dx = x - cx
      const dy = y - cy
      const along = dx * cos + dy * sin
      const across = -dx * sin + dy * cos

      let c = BG
      const len = 195 * s
      const a = Math.abs(across)
      const inLen = Math.abs(along) <= len
      if (inLen && a <= 17 * s) c = CORE
      else if (inLen && a <= 34 * s) c = TAPE
      else {
        // smooth glow falloff around the strip
        const over = Math.max(Math.abs(along) - len, 0)
        const d = Math.hypot(Math.max(a - 34 * s, 0), over)
        const glow = Math.max(0, 0.22 * (1 - d / (70 * s)))
        if (glow > 0.005) c = blend(c, TAPE, glow)
      }

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

writeFileSync(join(outDir, 'icon-192.png'), makeIcon(192))
writeFileSync(join(outDir, 'icon-512.png'), makeIcon(512))
writeFileSync(join(outDir, 'apple-touch-icon.png'), makeIcon(180))
console.log('icons written to', outDir)
