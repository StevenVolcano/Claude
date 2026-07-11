import { useState, type FormEvent } from 'react'
import { pb } from '../lib/pb.ts'

type Method = 'email' | 'phone'
type Step = 'enter' | 'code'

// Passwordless sign-in, two ways: email a code, or text a code.
// (Text sign-in only works for phones already verified on an account,
// and only once the server has an SMS provider configured.)
export default function SignIn() {
  const [method, setMethod] = useState<Method>('email')
  const [step, setStep] = useState<Step>('enter')
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [otpId, setOtpId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  function switchMethod(m: Method) {
    setMethod(m)
    setStep('enter')
    setCode('')
    setError('')
  }

  async function sendCode(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      if (method === 'email') {
        await pb.send('/api/glowtape/signup', { method: 'POST', body: { email, name } })
        const result = await pb.collection('users').requestOTP(email.trim().toLowerCase())
        setOtpId(result.otpId)
      } else {
        await pb.send('/api/glowtape/signin-sms/start', { method: 'POST', body: { phone } })
      }
      setStep('code')
    } catch (err) {
      setError(
        err instanceof Error && err.message.length < 120
          ? err.message
          : "We couldn't send a code. Check what you entered and try again.",
      )
    } finally {
      setBusy(false)
    }
  }

  async function confirmCode(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      if (method === 'email') {
        await pb.collection('users').authWithOTP(otpId, code.trim())
      } else {
        const res = await pb.send('/api/glowtape/signin-sms/confirm', {
          method: 'POST',
          body: { phone, code },
        })
        pb.authStore.save(res.token, res.record)
      }
    } catch {
      setError("That code didn't match. Check the message and try again.")
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="signin">
      <div className="signin-card">
        <div className="brand">
          <img className="brand-lamp" src="/icons/glowtape.svg" alt="" width="44" height="44" />
          <h1>Glow Tape</h1>
        </div>
        <p className="tagline">Grays Harbor's theater community</p>

        <div className="chips" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={method === 'email'}
            className={`chip ${method === 'email' ? 'chip-active' : ''}`}
            onClick={() => switchMethod('email')}
          >
            Email me a code
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={method === 'phone'}
            className={`chip ${method === 'phone' ? 'chip-active' : ''}`}
            onClick={() => switchMethod('phone')}
          >
            Text me a code
          </button>
        </div>

        {step === 'enter' ? (
          <form onSubmit={sendCode}>
            {method === 'email' ? (
              <>
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
              </>
            ) : (
              <>
                <label htmlFor="phone">Cell phone number</label>
                <input
                  id="phone"
                  type="tel"
                  autoComplete="tel"
                  inputMode="tel"
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  placeholder="(360) 555-0123"
                  required
                />
                <p className="hint">
                  Works once you've added your phone in Glow Tape. New here? Start with email —
                  it takes a minute.
                </p>
              </>
            )}
            <button type="submit" disabled={busy}>
              {busy ? 'Sending…' : method === 'email' ? 'Email me a sign-in code' : 'Text me a sign-in code'}
            </button>
            {method === 'email' && (
              <p className="hint">No password to remember. We email you a 6-digit code instead.</p>
            )}
          </form>
        ) : (
          <form onSubmit={confirmCode}>
            <label htmlFor="code">
              Enter the 6-digit code we {method === 'email' ? `emailed to ${email}` : `texted to ${phone}`}
            </label>
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
            <button type="button" className="link" onClick={() => setStep('enter')}>
              Start over
            </button>
          </form>
        )}

        {error && <p className="error">{error}</p>}
      </div>
    </main>
  )
}
