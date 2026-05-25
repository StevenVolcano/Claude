"use client"

import { useState } from "react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { MOOD_STATES, INFLUENCE_TAGS, type MoodState, type InfluenceTag } from "@/lib/types"
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

interface JournalEntry {
  id: number
  loggedAt: string
  mood: string
  influence: string
  freeText: string | null
}

interface JournalFormProps {
  onEntryAdded: (entry: JournalEntry) => void
}

export function JournalForm({ onEntryAdded }: JournalFormProps) {
  const [mood, setMood] = useState<MoodState | null>(null)
  const [influence, setInfluence] = useState<InfluenceTag | null>(null)
  const [freeText, setFreeText] = useState("")
  const [loading, setLoading] = useState(false)

  async function handleSave() {
    if (!mood || !influence) return
    setLoading(true)
    try {
      const res = await fetch("/api/journal", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ mood, influence, freeText: freeText || undefined }),
      })
      if (res.ok) {
        const data = await res.json()
        onEntryAdded(data.entry)
        setMood(null)
        setInfluence(null)
        setFreeText("")
        toast.success("Entry saved!")
      } else {
        toast.error("Could not save entry.")
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
        <CardTitle className="text-base text-zinc-100">Log New Entry</CardTitle>
      </CardHeader>
      <CardContent className="space-y-5">
        {/* Mood selector */}
        <div className="space-y-2">
          <p className="text-xs font-medium text-zinc-500 uppercase tracking-wider">Mood</p>
          <div className="grid grid-cols-3 gap-2">
            {MOOD_STATES.map((m) => (
              <button
                key={m}
                onClick={() => setMood(m)}
                className={cn(
                  "flex flex-col items-center gap-1 rounded-lg border px-2 py-2.5 text-xs font-medium transition-colors",
                  mood === m
                    ? "border-amber-500 bg-amber-500/10 text-zinc-100"
                    : "border-zinc-700 bg-zinc-900 text-zinc-400 hover:border-zinc-600 hover:text-zinc-200"
                )}
              >
                <span className="text-base">{moodEmojis[m]}</span>
                <span>{m}</span>
              </button>
            ))}
          </div>
        </div>

        {/* Influence selector */}
        <div className="space-y-2">
          <p className="text-xs font-medium text-zinc-500 uppercase tracking-wider">Influenced by</p>
          <div className="flex flex-wrap gap-2">
            {INFLUENCE_TAGS.map((tag) => (
              <button
                key={tag}
                onClick={() => setInfluence(tag)}
                className={cn(
                  "rounded-md border px-3 py-1.5 text-xs font-medium transition-colors",
                  influence === tag
                    ? "border-amber-500 bg-amber-500/10 text-zinc-100"
                    : "border-zinc-700 bg-zinc-900 text-zinc-400 hover:border-zinc-600 hover:text-zinc-200"
                )}
              >
                {tag}
              </button>
            ))}
          </div>
        </div>

        {/* Free text */}
        <div className="space-y-2">
          <p className="text-xs font-medium text-zinc-500 uppercase tracking-wider">Notes (optional)</p>
          <Textarea
            value={freeText}
            onChange={(e) => setFreeText(e.target.value)}
            placeholder="What's on your mind?"
            rows={3}
            maxLength={1000}
          />
        </div>

        <Button
          onClick={handleSave}
          disabled={!mood || !influence || loading}
          className="w-full"
        >
          {loading ? "Saving..." : "Save Entry"}
        </Button>
      </CardContent>
    </Card>
  )
}
