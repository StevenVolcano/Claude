import { useEffect, useState, type FormEvent } from 'react'
import { pb } from '../lib/pb.ts'
import { useAuth } from '../lib/auth.tsx'
import { useProduction } from './Production.tsx'
import { formatDay, formatWhen, pbDate } from '../lib/types.ts'
import { downloadEventIcs, googleCalendarUrl } from '../lib/calendar.ts'
import type { AckRecord, ConflictRecord, EventRecord } from '../lib/types.ts'

export default function ScheduleTab() {
  const { production, members, myMember, isManager } = useProduction()
  const { user } = useAuth()
  const [events, setEvents] = useState<EventRecord[]>([])
  const [acks, setAcks] = useState<AckRecord[]>([])
  const [conflicts, setConflicts] = useState<ConflictRecord[]>([])
  const [showPast, setShowPast] = useState(false)

  async function load() {
    const [ev, ak, cf] = await Promise.all([
      pb.collection('events').getFullList<EventRecord>({
        filter: pb.filter('production = {:p}', { p: production.id }),
        sort: 'start',
      }),
      pb.collection('acks').getFullList<AckRecord>({
        filter: pb.filter('event.production = {:p}', { p: production.id }),
      }),
      pb.collection('conflicts').getFullList<ConflictRecord>({
        filter: pb.filter('production = {:p}', { p: production.id }),
        expand: 'user',
        sort: 'start',
      }),
    ])
    setEvents(ev)
    setAcks(ak)
    setConflicts(cf)
  }

  useEffect(() => {
    load().catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [production.id])

  async function gotIt(event: EventRecord) {
    await pb.collection('acks').create({ event: event.id, user: user!.id })
    await load()
  }

  const now = new Date()
  const visible = events.filter((e) => showPast || pbDate(e.end || e.start) >= now)

  function calledLabel(e: EventRecord): string {
    if (e.called.length === 0) return 'Everyone'
    const names = e.called
      .map((mid) => members.find((m) => m.id === mid))
      .filter(Boolean)
      .map((m) => m!.expand?.user?.name || m!.position || 'someone')
    return names.join(', ')
  }

  return (
    <div>
      <section>
        <h2>Coming up</h2>
        {visible.length === 0 && <p className="hint">Nothing on the schedule yet.</p>}
        <ul className="cards">
          {visible.map((e) => {
            const iAmCalled = e.called.length === 0 || (myMember != null && e.called.includes(myMember.id))
            const myAck = acks.find((a) => a.event === e.id && a.user === user?.id)
            const ackCount = acks.filter((a) => a.event === e.id).length
            return (
              <li key={e.id} className={`card event ${iAmCalled ? 'called' : 'not-called'}`}>
                <div className="event-head">
                  <strong>{e.title}</strong>
                  <span>{formatWhen(e.start, e.end)}</span>
                </div>
                {e.location && <div className="event-line">📍 {e.location}</div>}
                <div className="event-line">
                  <strong>Called:</strong> {calledLabel(e)}
                  {e.calledNote && <em> — {e.calledNote}</em>}
                </div>
                {e.notes && <div className="event-line">{e.notes}</div>}
                {iAmCalled &&
                  (myAck ? (
                    <div className="acked">✓ You got it</div>
                  ) : (
                    <button onClick={() => gotIt(e)}>Got it 👍</button>
                  ))}
                <div className="row cal-links">
                  <a
                    className="link"
                    href={googleCalendarUrl(e, production.title)}
                    target="_blank"
                    rel="noreferrer"
                  >
                    + Google Calendar
                  </a>
                  <button className="link" onClick={() => downloadEventIcs(e, production.title)}>
                    + Apple / other calendar
                  </button>
                </div>
                {isManager && (
                  <div className="hint">
                    {ackCount} {ackCount === 1 ? 'person has' : 'people have'} tapped “Got it”
                  </div>
                )}
              </li>
            )
          })}
        </ul>
        <button className="link" onClick={() => setShowPast(!showPast)}>
          {showPast ? 'Hide past events' : 'Show past events'}
        </button>
      </section>

      <ConflictsSection conflicts={conflicts} reload={load} />
      <CalendarSubscribeSection />
    </div>
  )
}

function CalendarSubscribeSection() {
  const [url, setUrl] = useState('')
  const [copied, setCopied] = useState(false)
  const [busy, setBusy] = useState(false)

  async function getUrl() {
    setBusy(true)
    try {
      const res = await pb.send('/api/glowtape/calendar-url', { method: 'GET' })
      setUrl(res.url)
    } finally {
      setBusy(false)
    }
  }

  async function copy() {
    await navigator.clipboard.writeText(url)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <section>
      <h2>Put your calls on your own calendar</h2>
      <p className="hint">
        Subscribe once and every event you're called for shows up in Google Calendar, Apple
        Calendar, or your phone's calendar — and stays up to date when the schedule changes.
      </p>
      {!url ? (
        <button onClick={getUrl} disabled={busy}>
          {busy ? 'One moment…' : 'Get my calendar link'}
        </button>
      ) : (
        <div className="stack">
          <div className="row">
            <input aria-label="Calendar link" readOnly value={url} onFocus={(e) => e.target.select()} />
            <button onClick={copy}>{copied ? 'Copied ✓' : 'Copy'}</button>
          </div>
          <p className="hint">
            <strong>Google Calendar:</strong> Other calendars → + → From URL → paste.{' '}
            <strong>iPhone:</strong> Settings → Calendar → Accounts → Add Account → Other → Add
            Subscribed Calendar → paste. Keep this link to yourself — it's your personal schedule.
          </p>
        </div>
      )}
    </section>
  )
}

function ConflictsSection({ conflicts, reload }: { conflicts: ConflictRecord[]; reload: () => Promise<void> }) {
  const { production, isManager } = useProduction()
  const { user } = useAuth()
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)

  const mine = conflicts.filter((c) => c.user === user?.id)
  const shown = isManager ? conflicts : mine

  async function add(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    try {
      await pb.collection('conflicts').create({
        production: production.id,
        user: user!.id,
        start,
        end: end || start,
        note,
      })
      setStart('')
      setEnd('')
      setNote('')
      await reload()
    } finally {
      setBusy(false)
    }
  }

  async function remove(id: string) {
    await pb.collection('conflicts').delete(id)
    await reload()
  }

  return (
    <section>
      <h2>{isManager ? 'Conflicts (everyone)' : 'My conflicts'}</h2>
      <p className="hint">
        Tell your stage manager when you're <em>not</em> available, before the schedule is built.
      </p>
      <ul className="plain-list">
        {shown.map((c) => (
          <li key={c.id}>
            <strong>{isManager ? `${c.expand?.user?.name ?? '?'}: ` : ''}</strong>
            {formatDay(c.start)}
            {c.end && c.end !== c.start ? ` – ${formatDay(c.end)}` : ''}
            {c.note && ` — ${c.note}`}
            {c.user === user?.id && (
              <button className="link" onClick={() => remove(c.id)}>
                remove
              </button>
            )}
          </li>
        ))}
      </ul>
      <form onSubmit={add} className="stack">
        <div className="row">
          <label>
            From
            <input type="date" value={start} onChange={(e) => setStart(e.target.value)} required />
          </label>
          <label>
            To (optional)
            <input type="date" value={end} onChange={(e) => setEnd(e.target.value)} />
          </label>
        </div>
        <input
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="Reason (optional) — e.g. work trip"
        />
        <button type="submit" disabled={busy || !start}>
          Add conflict
        </button>
      </form>
    </section>
  )
}
