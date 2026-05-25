import { baseUrl } from "@/lib/utils"
import { JournalClient } from "./JournalClient"

async function getJournalData() {
  const [entriesRes, statsRes] = await Promise.all([
    fetch(`${baseUrl()}/api/journal?limit=20`, { cache: "no-store" }),
    fetch(`${baseUrl()}/api/journal/stats`, { cache: "no-store" }),
  ])

  const [entriesData, statsData] = await Promise.all([
    entriesRes.json(),
    statsRes.json(),
  ])

  return {
    entries: entriesData.entries ?? [],
    stats: statsData.stats,
  }
}

export default async function JournalPage() {
  const { entries, stats } = await getJournalData()

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-zinc-100">Journal</h1>
        <p className="text-sm text-zinc-500 mt-1">Track your mood and emotional patterns</p>
      </div>
      <JournalClient initialEntries={entries} stats={stats} />
    </div>
  )
}
