"use client"

import { useState } from "react"
import { MoodBadge } from "@/components/shared/MoodBadge"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Trash2 } from "lucide-react"
import { format } from "date-fns"
import type { MoodState } from "@/lib/types"
import { toast } from "sonner"

interface JournalEntry {
  id: number
  loggedAt: string
  mood: string
  influence: string
  freeText: string | null
}

interface EntryListProps {
  initialEntries: JournalEntry[]
}

export function EntryList({ initialEntries }: EntryListProps) {
  const [entries, setEntries] = useState(initialEntries)

  async function handleDelete(id: number) {
    try {
      const res = await fetch(`/api/journal/${id}`, { method: "DELETE" })
      if (res.ok) {
        setEntries((prev) => prev.filter((e) => e.id !== id))
        toast.success("Entry deleted.")
      } else {
        toast.error("Could not delete entry.")
      }
    } catch {
      toast.error("Something went wrong.")
    }
  }

  if (entries.length === 0) {
    return <p className="text-sm text-zinc-500 py-4">No entries yet. Start logging above.</p>
  }

  return (
    <div className="space-y-2">
      {entries.map((entry) => (
        <div
          key={entry.id}
          className="flex items-start gap-3 rounded-lg border border-zinc-800 bg-zinc-900 px-4 py-3"
        >
          <div className="flex-1 min-w-0 space-y-1">
            <div className="flex items-center gap-2 flex-wrap">
              <MoodBadge mood={entry.mood as MoodState} />
              <Badge variant="secondary" className="text-xs">{entry.influence}</Badge>
              <span className="text-xs text-zinc-600">
                {format(new Date(entry.loggedAt), "MMM d, h:mm a")}
              </span>
            </div>
            {entry.freeText && (
              <p className="text-sm text-zinc-400 truncate">{entry.freeText}</p>
            )}
          </div>
          <button
            onClick={() => handleDelete(entry.id)}
            className="text-zinc-600 hover:text-red-400 transition-colors shrink-0 mt-0.5"
            aria-label="Delete entry"
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      ))}
    </div>
  )
}
