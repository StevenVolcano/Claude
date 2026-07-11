import type { EventRecord } from './types.ts'
import { pbDate } from './types.ts'

// "2026-07-15 19:00:00.000Z" -> "20260715T190000Z"
function calStamp(value: string): string {
  return pbDate(value).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '')
}

function endOrDefault(event: EventRecord): string {
  if (event.end) return calStamp(event.end)
  const end = new Date(pbDate(event.start).getTime() + 2 * 3600e3)
  return end.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '')
}

export function googleCalendarUrl(event: EventRecord, productionTitle: string): string {
  const params = new URLSearchParams({
    action: 'TEMPLATE',
    text: `${event.title} — ${productionTitle}`,
    dates: `${calStamp(event.start)}/${endOrDefault(event)}`,
  })
  if (event.location) params.set('location', event.location)
  const details = [event.calledNote, event.notes].filter(Boolean).join('\n')
  if (details) params.set('details', details)
  return `https://calendar.google.com/calendar/render?${params.toString()}`
}

function icsEscape(text: string): string {
  return text.replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,').replace(/\r?\n/g, '\\n')
}

// Download a single-event .ics — iPhones open it straight into Calendar,
// desktops hand it to the default calendar app.
export function downloadEventIcs(event: EventRecord, productionTitle: string): void {
  const details = [event.calledNote, event.notes].filter(Boolean).join('\n')
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Glow Tape//Event//EN',
    'BEGIN:VEVENT',
    `UID:evt-${event.id}@glowtape`,
    `DTSTAMP:${calStamp(new Date().toISOString().replace('T', ' '))}`,
    `DTSTART:${calStamp(event.start)}`,
    `DTEND:${endOrDefault(event)}`,
    `SUMMARY:${icsEscape(`${event.title} — ${productionTitle}`)}`,
    ...(event.location ? [`LOCATION:${icsEscape(event.location)}`] : []),
    ...(details ? [`DESCRIPTION:${icsEscape(details)}`] : []),
    'END:VEVENT',
    'END:VCALENDAR',
  ]
  const blob = new Blob([lines.join('\r\n') + '\r\n'], { type: 'text/calendar;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${event.title.replace(/[^\w -]/g, '')}.ics`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
