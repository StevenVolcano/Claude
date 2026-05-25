"use client"

import dynamic from "next/dynamic"

const MoodChart = dynamic(() => import("./MoodChart").then((m) => m.MoodChart), {
  ssr: false,
  loading: () => <div className="h-[180px] flex items-center justify-center text-sm text-zinc-500">Loading chart...</div>,
})

interface MoodEntry {
  date: string
  mood: string
  influence: string
}

export function MoodChartWrapper({ entries }: { entries: MoodEntry[] }) {
  return <MoodChart entries={entries} />
}
