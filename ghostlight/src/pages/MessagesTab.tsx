import { useEffect, useRef, useState, type FormEvent } from 'react'
import { pb } from '../lib/pb.ts'
import { useAuth } from '../lib/auth.tsx'
import { useProduction } from './Production.tsx'
import type {
  AnnouncementAckRecord,
  AnnouncementRecord,
  ChannelRecord,
  MessageRecord,
} from '../lib/types.ts'

export default function MessagesTab() {
  const { production, myMember, isManager } = useProduction()
  const [channels, setChannels] = useState<ChannelRecord[]>([])
  const [active, setActive] = useState<string | null>(null)

  useEffect(() => {
    pb.collection('channels')
      .getFullList<ChannelRecord>({
        filter: pb.filter('production = {:p}', { p: production.id }),
        sort: 'created',
      })
      .then((list) => {
        // Audience filtering is a UI courtesy in the pilot; managers see all.
        const role = myMember?.role
        const visible = list.filter((c) => {
          if (isManager || !role) return true
          if (c.audience === 'all') return true
          if (c.audience === 'cast') return role === 'performer'
          if (c.audience === 'crew') return role === 'crew'
          return false // team
        })
        setChannels(visible)
        setActive((prev) => prev ?? visible[0]?.id ?? null)
      })
      .catch(() => {})
  }, [production.id, myMember?.role, isManager])

  return (
    <div>
      <Announcements />
      <section>
        <h2>Channels</h2>
        <div className="chips" role="tablist">
          {channels.map((c) => (
            <button
              key={c.id}
              role="tab"
              aria-selected={active === c.id}
              className={`chip ${active === c.id ? 'chip-active' : ''}`}
              onClick={() => setActive(c.id)}
            >
              {c.name}
            </button>
          ))}
        </div>
        {active && <ChannelView channelId={active} />}
      </section>
    </div>
  )
}

function ChannelView({ channelId }: { channelId: string }) {
  const { user } = useAuth()
  const [messages, setMessages] = useState<MessageRecord[]>([])
  const [text, setText] = useState('')
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let cancelled = false

    pb.collection('messages')
      .getList<MessageRecord>(1, 100, {
        filter: pb.filter('channel = {:c}', { c: channelId }),
        sort: '-created',
        expand: 'author',
      })
      .then((res) => {
        if (!cancelled) setMessages(res.items.reverse())
      })
      .catch(() => {})

    const unsubscribe = pb.collection('messages').subscribe<MessageRecord>(
      '*',
      (e) => {
        if (e.action === 'create' && e.record.channel === channelId) {
          setMessages((prev) => [...prev, e.record])
        }
      },
      { expand: 'author' },
    )

    return () => {
      cancelled = true
      unsubscribe.then((fn) => fn()).catch(() => {})
    }
  }, [channelId])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'end' })
  }, [messages.length])

  async function send(e: FormEvent) {
    e.preventDefault()
    const body = text.trim()
    if (!body) return
    setText('')
    await pb.collection('messages').create({ channel: channelId, author: user!.id, text: body })
  }

  return (
    <div className="channel">
      <div className="messages">
        {messages.length === 0 && <p className="hint">No messages yet. Say hi!</p>}
        {messages.map((m) => (
          <div key={m.id} className={`msg ${m.author === user?.id ? 'mine' : ''}`}>
            <span className="msg-author">{m.expand?.author?.name || '…'}</span>
            <span className="msg-text">{m.text}</span>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
      <form onSubmit={send} className="row">
        <input
          aria-label="Message"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Type a message…"
        />
        <button type="submit" disabled={!text.trim()}>
          Send
        </button>
      </form>
    </div>
  )
}

function Announcements() {
  const { production, isManager } = useProduction()
  const { user } = useAuth()
  const [items, setItems] = useState<AnnouncementRecord[]>([])
  const [acks, setAcks] = useState<AnnouncementAckRecord[]>([])

  async function load() {
    const [a, ak] = await Promise.all([
      pb.collection('announcements').getFullList<AnnouncementRecord>({
        filter: pb.filter('production = {:p}', { p: production.id }),
        sort: '-pinned,-created',
        expand: 'author',
      }),
      pb.collection('announcement_acks').getFullList<AnnouncementAckRecord>({
        filter: pb.filter('announcement.production = {:p}', { p: production.id }),
      }),
    ])
    setItems(a)
    setAcks(ak)
  }

  useEffect(() => {
    load().catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [production.id])

  async function ack(a: AnnouncementRecord) {
    await pb.collection('announcement_acks').create({ announcement: a.id, user: user!.id })
    await load()
  }

  if (items.length === 0) return null

  return (
    <section>
      <h2>Announcements</h2>
      <ul className="cards">
        {items.map((a) => {
          const mine = acks.some((k) => k.announcement === a.id && k.user === user?.id)
          const count = acks.filter((k) => k.announcement === a.id).length
          return (
            <li key={a.id} className="card announcement">
              {a.pinned && <span className="pill">Pinned</span>}
              <strong>{a.title}</strong>
              {a.body && <p>{a.body}</p>}
              <div className="hint">— {a.expand?.author?.name}</div>
              {mine ? (
                <div className="acked">✓ Acknowledged</div>
              ) : (
                <button onClick={() => ack(a)}>Got it 👍</button>
              )}
              {isManager && (
                <div className="hint">
                  {count} {count === 1 ? 'person has' : 'people have'} acknowledged
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
