import { cn } from "@/lib/utils"
import type { MoodState } from "@/lib/types"

const moodColors: Record<MoodState, string> = {
  Energized: "bg-amber-400/20 text-amber-400 border-amber-400/30",
  Calm: "bg-sky-400/20 text-sky-400 border-sky-400/30",
  Anxious: "bg-orange-400/20 text-orange-400 border-orange-400/30",
  Low: "bg-zinc-500/20 text-zinc-400 border-zinc-500/30",
  Frustrated: "bg-red-400/20 text-red-400 border-red-400/30",
  Grateful: "bg-emerald-400/20 text-emerald-400 border-emerald-400/30",
}

export function MoodBadge({ mood, className }: { mood: MoodState; className?: string }) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-md border px-2 py-0.5 text-xs font-medium",
        moodColors[mood],
        className
      )}
    >
      {mood}
    </span>
  )
}
