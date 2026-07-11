import { useState, type FormEvent } from 'react'
import { pb } from '../lib/pb.ts'
import { useAuth } from '../lib/auth.tsx'
import { useProduction } from './Production.tsx'
import { MANAGER_ROLES, ROLE_LABELS } from '../lib/types.ts'
import type { MemberRecord, MemberRole } from '../lib/types.ts'

export default function AdminTab() {
  return (
    <div>
      <JoinCodeSection />
      <NewEventSection />
      <NewAnnouncementSection />
      <MembersSection />
    </div>
  )
}

function JoinCodeSection() {
  const { production } = useProduction()
  return (
    <section>
      <h2>Invite people</h2>
      <p className="hint">
        Share this code at the read-through. Anyone can sign in at this site and join with it —
        no invitation email required.
      </p>
      <div className="join-code">{production.joinCode}</div>
    </section>
  )
}

function NewEventSection() {
  const { production, members } = useProduction()
  const [title, setTitle] = useState('')
  const [date, setDate] = useState('')
  const [startTime, setStartTime] = useState('19:00')
  const [endTime, setEndTime] = useState('')
  const [location, setLocation] = useState('')
  const [notes, setNotes] = useState('')
  const [calledNote, setCalledNote] = useState('')
  const [everyone, setEveryone] = useState(true)
  const [called, setCalled] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState('')

  function toggle(id: string) {
    setCalled((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))
  }

  async function create(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setDone('')
    try {
      await pb.collection('events').create({
        production: production.id,
        title,
        // Entered in local time; stored as UTC.
        start: new Date(`${date}T${startTime}`).toISOString(),
        end: endTime ? new Date(`${date}T${endTime}`).toISOString() : '',
        location,
        notes,
        calledNote,
        called: everyone ? [] : called,
      })
      setTitle('')
      setNotes('')
      setCalledNote('')
      setDone('Added to the schedule. Everyone called was emailed.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <h2>Add to the schedule</h2>
      <form onSubmit={create} className="stack">
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Title — e.g. Act II run-through"
          required
        />
        <div className="row">
          <label>
            Date
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} required />
          </label>
          <label>
            Start
            <input type="time" value={startTime} onChange={(e) => setStartTime(e.target.value)} required />
          </label>
          <label>
            End
            <input type="time" value={endTime} onChange={(e) => setEndTime(e.target.value)} />
          </label>
        </div>
        <input value={location} onChange={(e) => setLocation(e.target.value)} placeholder="Location" />
        <input value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Notes (optional)" />
        <label className="row">
          <input type="checkbox" checked={everyone} onChange={(e) => setEveryone(e.target.checked)} />
          Everyone is called
        </label>
        {!everyone && (
          <>
            <div className="chips">
              {members.map((m) => (
                <button
                  type="button"
                  key={m.id}
                  className={`chip ${called.includes(m.id) ? 'chip-active' : ''}`}
                  onClick={() => toggle(m.id)}
                >
                  {m.expand?.user?.name || m.position}
                </button>
              ))}
            </div>
            <input
              value={calledNote}
              onChange={(e) => setCalledNote(e.target.value)}
              placeholder="Call details — e.g. dancers at 6, full cast at 7"
            />
          </>
        )}
        <button type="submit" disabled={busy || !title || !date}>
          {busy ? 'Adding…' : 'Add event'}
        </button>
        {done && <p className="acked">{done}</p>}
      </form>
    </section>
  )
}

function NewAnnouncementSection() {
  const { production } = useProduction()
  const { user } = useAuth()
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [pinned, setPinned] = useState(false)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState('')

  async function post(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setDone('')
    try {
      await pb.collection('announcements').create({
        production: production.id,
        author: user!.id,
        title,
        body,
        pinned,
      })
      setTitle('')
      setBody('')
      setPinned(false)
      setDone('Posted, and emailed to everyone in the production.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <h2>Post an announcement</h2>
      <form onSubmit={post} className="stack">
        <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Title" required />
        <textarea value={body} onChange={(e) => setBody(e.target.value)} placeholder="Details" rows={3} />
        <label className="row">
          <input type="checkbox" checked={pinned} onChange={(e) => setPinned(e.target.checked)} />
          Pin to the top
        </label>
        <button type="submit" disabled={busy || !title}>
          {busy ? 'Posting…' : 'Post announcement'}
        </button>
        {done && <p className="acked">{done}</p>}
      </form>
    </section>
  )
}

function MembersSection() {
  const { production, members, reload } = useProduction()
  const [busyId, setBusyId] = useState('')

  // Keep the denormalized managers list in sync with member roles so the
  // API rules (which check production.managers) stay correct.
  async function syncManagers(updated: MemberRecord[]) {
    const managerUserIds = updated
      .filter((m) => MANAGER_ROLES.includes(m.role))
      .map((m) => m.user)
    const next = Array.from(new Set(managerUserIds))
    const current = [...production.managers].sort()
    if (JSON.stringify(next.slice().sort()) !== JSON.stringify(current)) {
      await pb.collection('productions').update(production.id, { managers: next })
    }
  }

  async function setRole(member: MemberRecord, role: MemberRole) {
    setBusyId(member.id)
    try {
      await pb.collection('members').update(member.id, { role })
      const updated = members.map((m) => (m.id === member.id ? { ...m, role } : m))
      await syncManagers(updated)
      await reload()
    } finally {
      setBusyId('')
    }
  }

  async function setPosition(member: MemberRecord, position: string) {
    await pb.collection('members').update(member.id, { position })
    await reload()
  }

  async function remove(member: MemberRecord) {
    if (!window.confirm(`Remove ${member.expand?.user?.name} from ${production.title}?`)) return
    setBusyId(member.id)
    try {
      await pb.collection('members').delete(member.id)
      await syncManagers(members.filter((m) => m.id !== member.id))
      await reload()
    } finally {
      setBusyId('')
    }
  }

  return (
    <section>
      <h2>People &amp; roles</h2>
      <ul className="plain-list">
        {members.map((m) => (
          <li key={m.id} className="member-row">
            <strong>{m.expand?.user?.name}</strong>
            <select
              aria-label={`Role for ${m.expand?.user?.name}`}
              value={m.role}
              disabled={busyId === m.id}
              onChange={(e) => setRole(m, e.target.value as MemberRole)}
            >
              {(Object.keys(ROLE_LABELS) as MemberRole[]).map((r) => (
                <option key={r} value={r}>
                  {ROLE_LABELS[r]}
                </option>
              ))}
            </select>
            <input
              aria-label={`Position for ${m.expand?.user?.name}`}
              defaultValue={m.position}
              placeholder="Character / position"
              onBlur={(e) => {
                if (e.target.value !== m.position) setPosition(m, e.target.value)
              }}
            />
            <button className="link" disabled={busyId === m.id} onClick={() => remove(m)}>
              remove
            </button>
          </li>
        ))}
      </ul>
      <p className="hint">
        Directors, assistant directors, and stage managers can manage the production (this tab).
      </p>
    </section>
  )
}
