"use client"

import { useState } from "react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { MoodBadge } from "@/components/shared/MoodBadge"
import { JournalForm } from "./JournalForm"
import { EntryList } from "./EntryList"
import { MoodChartWrapper } from "./MoodChartWrapper"
import type { MoodState, InfluenceTag } from "@/lib/types"

interface JournalEntry {
  id: number
  loggedAt: string
  mood: string
  influence: string
  freeText: string | null
}

interface JournalStats {
  moodFrequency: Record<string, number>
  last30Days: Array<{ date: string; mood: string; influence: string }>
  mostCommonMood: MoodState
  mostCommonInfluence: InfluenceTag
  streakDays: number
}

interface JournalClientProps {
  initialEntries: JournalEntry[]
  stats: JournalStats
}

export function JournalClient({ initialEntries, stats }: JournalClientProps) {
  const [entries, setEntries] = useState(initialEntries)

  function handleEntryAdded(entry: JournalEntry) {
    setEntries((prev) => [entry, ...prev])
  }

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      {/* Left column: form + entries */}
      <div className="space-y-6">
        <JournalForm onEntryAdded={handleEntryAdded} />

        <div>
          <h2 className="text-sm font-semibold text-zinc-500 uppercase tracking-wider mb-3">
            Recent Entries
          </h2>
          <EntryList initialEntries={entries} />
        </div>
      </div>

      {/* Right column: stats + chart */}
      <div className="space-y-6">
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base text-zinc-100">Stats</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-sm text-zinc-400">Most common mood</span>
              <MoodBadge mood={stats.mostCommonMood} />
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm text-zinc-400">Most common influence</span>
              <span className="text-sm font-medium text-zinc-200">{stats.mostCommonInfluence}</span>
            </div>
            {stats.streakDays > 0 && (
              <div className="flex items-center justify-between">
                <span className="text-sm text-zinc-400">Current streak</span>
                <span className="text-sm font-semibold text-amber-400">{stats.streakDays} days 🔥</span>
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base text-zinc-100">Mood (last 30 days)</CardTitle>
          </CardHeader>
          <CardContent>
            <MoodChartWrapper entries={stats.last30Days} />
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
