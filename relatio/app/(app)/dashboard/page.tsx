import { baseUrl } from "@/lib/utils"
import Link from "next/link"
import { Progress } from "@/components/ui/progress"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { MissionCard } from "./_components/MissionCard"
import { MoodQuickLog } from "./_components/MoodQuickLog"
import { format } from "date-fns"

interface DashboardData {
  greeting: string
  activeCourse: {
    slug: string
    title: string
    progressPct: number
    nextLesson: { slug: string; title: string } | null
  } | null
  todayMission: {
    slug: string
    title: string
    body: string
    completed: boolean
  } | null
  todayJournalEntry: { mood: string; loggedAt: string } | null
  streakDays: number
}

async function getDashboard(): Promise<DashboardData> {
  const res = await fetch(`${baseUrl()}/api/dashboard`, {
    cache: "no-store",
  })
  if (!res.ok) throw new Error("Failed to fetch dashboard")
  return res.json()
}

export default async function DashboardPage() {
  const data = await getDashboard()
  const today = format(new Date(), "EEEE, MMMM d")

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      {/* Greeting */}
      <div>
        <h1 className="text-2xl md:text-3xl font-semibold text-zinc-100">
          {data.greeting}, Steven
        </h1>
        <p className="text-sm text-zinc-500 mt-1">{today}</p>
      </div>

      {/* Streak */}
      {data.streakDays > 0 && (
        <div className="flex items-center gap-2 text-sm text-zinc-400">
          <span className="font-semibold text-amber-400">{data.streakDays} day streak 🔥</span>
        </div>
      )}

      {/* Active course */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base text-zinc-100">Your Course</CardTitle>
        </CardHeader>
        <CardContent>
          {data.activeCourse ? (
            <div className="space-y-3">
              <div>
                <p className="font-medium text-zinc-100">{data.activeCourse.title}</p>
                <div className="flex items-center gap-2 mt-2">
                  <Progress value={data.activeCourse.progressPct} className="flex-1 h-1.5" />
                  <span className="text-xs text-zinc-500 shrink-0">
                    {data.activeCourse.progressPct}%
                  </span>
                </div>
              </div>
              {data.activeCourse.nextLesson && (
                <Link
                  href={`/courses/${data.activeCourse.slug}/lessons/${data.activeCourse.nextLesson.slug}`}
                  className="text-sm text-amber-500 hover:text-amber-400 transition-colors"
                >
                  Next: {data.activeCourse.nextLesson.title} &rarr;
                </Link>
              )}
            </div>
          ) : (
            <div className="flex items-center justify-between">
              <p className="text-sm text-zinc-400">You haven&apos;t started a course yet.</p>
              <Link
                href="/courses"
                className="text-sm font-medium text-amber-500 hover:text-amber-400 transition-colors"
              >
                Start a course &rarr;
              </Link>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Today's mission */}
      {data.todayMission ? (
        <MissionCard
          title={data.todayMission.title}
          body={data.todayMission.body}
          completed={data.todayMission.completed}
        />
      ) : (
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base text-zinc-100">Today&apos;s Mission</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-zinc-400">
              Complete onboarding to unlock daily missions.
            </p>
          </CardContent>
        </Card>
      )}

      {/* Mood quick log */}
      <MoodQuickLog todayEntry={data.todayJournalEntry} />
    </div>
  )
}
