"use client"

import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  Cell,
} from "recharts"
import type { MoodState } from "@/lib/types"

const moodColors: Record<MoodState, string> = {
  Energized: "#fbbf24",
  Calm: "#38bdf8",
  Anxious: "#fb923c",
  Low: "#71717a",
  Frustrated: "#f87171",
  Grateful: "#34d399",
}

interface MoodEntry {
  date: string
  mood: string
  influence: string
}

interface MoodChartProps {
  entries: MoodEntry[]
}

export function MoodChart({ entries }: MoodChartProps) {
  // Count by mood
  const counts: Record<string, number> = {}
  for (const entry of entries) {
    counts[entry.mood] = (counts[entry.mood] ?? 0) + 1
  }

  const data = Object.entries(counts).map(([mood, count]) => ({ mood, count }))

  if (data.length === 0) {
    return <p className="text-sm text-zinc-500">No entries in the last 30 days.</p>
  }

  return (
    <ResponsiveContainer width="100%" height={180}>
      <BarChart data={data} margin={{ top: 4, right: 4, left: -20, bottom: 4 }}>
        <XAxis
          dataKey="mood"
          tick={{ fontSize: 11, fill: "#71717a" }}
          axisLine={false}
          tickLine={false}
        />
        <YAxis
          tick={{ fontSize: 11, fill: "#71717a" }}
          axisLine={false}
          tickLine={false}
          allowDecimals={false}
        />
        <Tooltip
          contentStyle={{
            backgroundColor: "#18181b",
            border: "1px solid #3f3f46",
            borderRadius: "8px",
            fontSize: "12px",
            color: "#e4e4e7",
          }}
          cursor={{ fill: "rgba(255,255,255,0.04)" }}
        />
        <Bar dataKey="count" radius={[4, 4, 0, 0]}>
          {data.map((entry) => (
            <Cell
              key={entry.mood}
              fill={moodColors[entry.mood as MoodState] ?? "#71717a"}
            />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}
