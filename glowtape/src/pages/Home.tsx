import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { pb } from '../lib/pb.ts'
import { useAuth } from '../lib/auth.tsx'
import PhoneSettings from '../components/PhoneSettings.tsx'
import type { ProductionRecord } from '../lib/types.ts'

export default function Home() {
  const { user, signOut } = useAuth()
  const [productions, setProductions] = useState<ProductionRecord[]>([])
  const [joinCode, setJoinCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [loaded, setLoaded] = useState(false)

  async function load() {
    const list = await pb.collection('productions').getFullList<ProductionRecord>({ sort: '-created' })
    setProductions(list)
    setLoaded(true)
  }

  useEffect(() => {
    load().catch(() => setLoaded(true))
  }, [])

  async function join(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await pb.send('/api/glowtape/join', { method: 'POST', body: { code: joinCode } })
      setJoinCode('')
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'That code did not work.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="page">
      <header className="topbar">
        <span className="brand-small">Glow Tape</span>
        <button className="link" onClick={signOut}>
          Sign out
        </button>
      </header>

      <h1>Hi, {user?.name || 'there'}</h1>

      <section>
        <h2>Your productions</h2>
        {!loaded ? (
          <p>Loading…</p>
        ) : productions.length === 0 ? (
          <p className="hint">
            You're not in a production yet. Enter the join code your director or stage manager gave
            you below.
          </p>
        ) : (
          <ul className="cards">
            {productions.map((p) => (
              <li key={p.id}>
                <Link className="card card-link" to={`/production/${p.id}/schedule`}>
                  <strong>{p.title}</strong>
                  <span className="pill">{p.status}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <h2>Join a production</h2>
        <form onSubmit={join} className="row">
          <input
            aria-label="Join code"
            value={joinCode}
            onChange={(e) => setJoinCode(e.target.value.toUpperCase())}
            placeholder="Join code, e.g. TR7PDQ"
          />
          <button type="submit" disabled={busy || joinCode.trim().length < 4}>
            {busy ? 'Joining…' : 'Join'}
          </button>
        </form>
        {error && <p className="error">{error}</p>}
      </section>

      {user && <PhoneSettings user={user} />}
    </main>
  )
}
