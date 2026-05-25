"use client"

import { useState } from "react"
import Link from "next/link"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { MoodBadge } from "@/components/shared/MoodBadge"
import { MOOD_STATES, type MoodState } from "@/lib/types"
import { cn } from "@/lib/utils"
import { toast } from "sonner"

const moodEmojis: Record<MoodState, string> = {
  Energized: "⚡",
  Calm: "🌊",
  Anxious: "😰",
  Low: "😔",
  Frustrated: "😤",
  Grateful: "🙏",
}

interface MoodQuickLogProps {
  todayEntry: { mood: string; loggedAt: string } | null
}

export function MoodQuickLog({ todayEntry }: MoodQuickLogProps) {
  const [selected, setSelected] = useState<MoodState | null>(null)
  const [logged, setLogged] = useState<MoodState | null>(
    todayEntry ? (todayEntry.mood as MoodState) : null
  )
  const [loading, setLoading] = useState(false)

  async function handleLog() {
    if (!selected) return
    setLoading(true)
    try {
      const res = await fetch("/api/journal", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ mood: selected, influence: "Self" }),
      })
      if (res.ok) {
        setLogged(selected)
        toast.success("Mood logged!")
      } else {
        toast.error("Could not log mood.")
      }
    } catch {
      toast.error("Something went wrong.")
    } finally {
      setLoading(false)
    }
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base text-zinc-100">How are you feeling?</CardTitle>
      </CardHeader>
      <CardContent>
        {logged ? (
          <div className="flex items-center gap-3">
            <span className="text-sm text-zinc-400">Today:</span>
            <MoodBadge mood={logged} />
            <Link
              href="/journal"
              className="ml-auto text-xs text-amber-500 hover:text-amber-400 transition-colors"
            >
              View journal
            </Link>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="grid grid-cols-3 gap-2">
              {MOOD_STATES.map((mood) => (
                <button
                  key={mood}
                  onClick={() => setSelected(mood)}
                  className={cn(
                    "flex flex-col items-center gap-1 rounded-lg border px-2 py-2.5 text-xs font-medium transition-colors",
                    selected === mood
                      ? "border-amber-500 bg-amber-500/10 text-zinc-100"
                      : "border-zinc-700 bg-zinc-900 text-zinc-400 hover:border-zinc-600 hover:text-zinc-200"
                  )}
                >
                  <span className="text-base">{moodEmojis[mood]}</span>
                  <span>{mood}</span>
                </button>
              ))}
            </div>
            <Button
              size="sm"
              onClick={handleLog}
              disabled={!selected || loading}
              className="w-full"
            >
              {loading ? "Saving..." : "Log mood"}
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
