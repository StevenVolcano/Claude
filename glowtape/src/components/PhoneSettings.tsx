import { useState, type FormEvent } from 'react'
import { pb } from '../lib/pb.ts'
import type { UserRecord } from '../lib/types.ts'

// Attach a cell number and opt into text reminders. Verification codes only
// go out once the server has an SMS provider configured.
export default function PhoneSettings({ user }: { user: UserRecord }) {
  const [editing, setEditing] = useState(false)
  const [step, setStep] = useState<'phone' | 'code'>('phone')
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function refreshAuth() {
    await pb.collection('users').authRefresh()
  }

  async function start(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await pb.send('/api/glowtape/phone/start', { method: 'POST', body: { phone } })
      setStep('code')
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't send a code to that number.")
    } finally {
      setBusy(false)
    }
  }

  async function confirm(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await pb.send('/api/glowtape/phone/confirm', { method: 'POST', body: { phone, code } })
      await refreshAuth()
      setEditing(false)
      setStep('phone')
      setCode('')
    } catch (err) {
      setError(err instanceof Error ? err.message : "That code didn't match.")
    } finally {
      setBusy(false)
    }
  }

  async function toggleOptIn(on: boolean) {
    await pb.collection('users').update(user.id, { smsOptIn: on })
    await refreshAuth()
  }

  if (user.phoneVerified && !editing) {
    return (
      <section>
        <h2>Text reminders</h2>
        <div className="card stack">
          <div>
            📱 <strong>{user.phone}</strong> is verified.
          </div>
          <label className="row">
            <input
              type="checkbox"
              checked={user.smsOptIn}
              onChange={(e) => toggleOptIn(e.target.checked)}
            />
            Text me reminders about my calls (message &amp; data rates may apply; reply STOP anytime)
          </label>
          <button className="link" onClick={() => setEditing(true)}>
            Change number
          </button>
        </div>
      </section>
    )
  }

  return (
    <section>
      <h2>Text reminders</h2>
      <div className="card stack">
        <p className="hint">
          Add your cell number and Glow Tape will text you before your calls — 10 hours ahead,
          and again about 2 hours out. Email reminders happen either way.
        </p>
        {step === 'phone' ? (
          <form onSubmit={start} className="row">
            <input
              aria-label="Cell phone number"
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder="(360) 555-0123"
            />
            <button type="submit" disabled={busy || phone.replace(/\D/g, '').length < 10}>
              {busy ? 'Sending…' : 'Text me a code'}
            </button>
          </form>
        ) : (
          <form onSubmit={confirm} className="row">
            <input
              aria-label="Verification code"
              inputMode="numeric"
              autoComplete="one-time-code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="6-digit code"
            />
            <button type="submit" disabled={busy || code.trim().length < 6}>
              {busy ? 'Checking…' : 'Verify'}
            </button>
            <button type="button" className="link" onClick={() => setStep('phone')}>
              Start over
            </button>
          </form>
        )}
        {editing && (
          <button className="link" onClick={() => setEditing(false)}>
            Never mind
          </button>
        )}
        {error && <p className="error">{error}</p>}
      </div>
    </section>
  )
}
