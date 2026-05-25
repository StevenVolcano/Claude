import { COACHES } from "@/lib/coaches"

export function CoachAvatar({ coachSlug }: { coachSlug: string }) {
  const coach = COACHES[coachSlug] ?? COACHES["julie"]
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full bg-zinc-800 border border-zinc-700 px-3 py-1 text-sm text-zinc-300">
      <span>{coach.avatarEmoji}</span>
      <span className="font-medium">{coach.name}</span>
    </span>
  )
}
