import { useState, type FormEvent } from 'react'
import { pb } from '../lib/pb.ts'

// Passwordless sign-in: email + name -> 6-digit code from email -> done.
export default function SignIn() {
  const [step, setStep] = useState<'email' | 'code'>('email')
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [otpId, setOtpId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function sendCode(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await pb.send('/api/ghostlight/signup', { method: 'POST', body: { email, name } })
      const result = await pb.collection('users').requestOTP(email.trim().toLowerCase())
      setOtpId(result.otpId)
      setStep('code')
    } catch {
      setError("We couldn't send a code to that address. Check the email and try again.")
    } finally {
      setBusy(false)
    }
  }

  async function confirmCode(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await pb.collection('users').authWithOTP(otpId, code.trim())
    } catch {
      setError("That code didn't match. Check the email (and spam folder) and try again.")
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="signin">
      <div className="signin-card">
        <div className="brand">
          <img className="brand-lamp" src="/icons/ghostlight.svg" alt="" width="44" height="44" />
          <h1>Ghostlight</h1>
        </div>
        <p className="tagline">Grays Harbor's theater community</p>

        {step === 'email' ? (
          <form onSubmit={sendCode}>
            <label htmlFor="name">Your name</label>
            <input
              id="name"
              type="text"
              autoComplete="name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="As it should appear on the contact sheet"
              required
            />
            <label htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
              required
            />
            <button type="submit" disabled={busy}>
              {busy ? 'Sending…' : 'Email me a sign-in code'}
            </button>
            <p className="hint">No password to remember. We email you a 6-digit code instead.</p>
          </form>
        ) : (
          <form onSubmit={confirmCode}>
            <label htmlFor="code">Enter the 6-digit code we emailed to {email}</label>
            <input
              id="code"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="123456"
              required
            />
            <button type="submit" disabled={busy}>
              {busy ? 'Checking…' : 'Sign in'}
            </button>
            <button type="button" className="link" onClick={() => setStep('email')}>
              Use a different email
            </button>
          </form>
        )}

        {error && <p className="error">{error}</p>}
      </div>
    </main>
  )
}
