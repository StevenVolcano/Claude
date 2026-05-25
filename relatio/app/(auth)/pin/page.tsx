"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"

export default function PinPage() {
  const router = useRouter()
  const [pin, setPin] = useState("")
  const [error, setError] = useState("")
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError("")
    setLoading(true)
    try {
      const res = await fetch("/api/auth/verify-pin", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ pin }),
      })
      if (res.ok) {
        router.push("/dashboard")
      } else {
        setError("Incorrect PIN")
        setPin("")
      }
    } catch {
      setError("Something went wrong. Please try again.")
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-zinc-950">
      <Card className="w-full max-w-sm">
        <CardHeader className="text-center">
          <CardTitle className="text-2xl font-semibold text-zinc-100">Relatio</CardTitle>
          <p className="text-sm text-zinc-500 mt-1">Enter your PIN to continue</p>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-4">
            <input
              type="password"
              maxLength={4}
              value={pin}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))}
              placeholder="••••"
              className="w-full text-center text-2xl tracking-widest rounded-md border border-zinc-700 bg-zinc-900 px-3 py-3 text-zinc-100 placeholder:text-zinc-600 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber-500"
              autoFocus
            />
            {error && (
              <p className="text-sm text-red-400 text-center">{error}</p>
            )}
            <Button
              type="submit"
              className="w-full"
              disabled={pin.length !== 4 || loading}
            >
              {loading ? "Verifying..." : "Unlock"}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
