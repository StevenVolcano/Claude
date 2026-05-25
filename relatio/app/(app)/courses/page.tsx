import { baseUrl } from "@/lib/utils"
import Link from "next/link"
import { Badge } from "@/components/ui/badge"
import { Progress } from "@/components/ui/progress"
import { Card, CardContent } from "@/components/ui/card"
import type { CourseArea } from "@/lib/types"

interface CourseItem {
  slug: string
  title: string
  area: CourseArea
  coverEmoji: string
  description: string
  lessonCount: number
  completedCount: number
  progressPct: number
  isInActiveProgram: boolean
}

const AREA_LABELS: Record<CourseArea, string> = {
  Communication: "Communication",
  EmotionalHealing: "Emotional Healing",
  Intimacy: "Intimacy",
  DailyWellness: "Daily Wellness",
}

async function getCourses(): Promise<{ courses: CourseItem[] }> {
  const res = await fetch(`${baseUrl()}/api/courses`, {
    cache: "no-store",
  })
  if (!res.ok) throw new Error("Failed to fetch courses")
  return res.json()
}

export default async function CoursesPage() {
  const { courses } = await getCourses()

  // Group by area
  const grouped = courses.reduce<Record<string, CourseItem[]>>((acc, course) => {
    const area = course.area
    if (!acc[area]) acc[area] = []
    acc[area].push(course)
    return acc
  }, {})

  const areaOrder: CourseArea[] = ["Communication", "EmotionalHealing", "Intimacy", "DailyWellness"]

  return (
    <div className="max-w-3xl mx-auto space-y-8">
      <div>
        <h1 className="text-2xl font-semibold text-zinc-100">Courses</h1>
        <p className="text-sm text-zinc-500 mt-1">Choose a course to start or continue</p>
      </div>

      {areaOrder.map((area) => {
        const areaCourses = grouped[area]
        if (!areaCourses?.length) return null
        return (
          <section key={area} className="space-y-3">
            <h2 className="text-sm font-semibold uppercase tracking-wider text-zinc-500">
              {AREA_LABELS[area]}
            </h2>
            <div className="grid gap-3 sm:grid-cols-2">
              {areaCourses.map((course) => (
                <Link key={course.slug} href={`/courses/${course.slug}`}>
                  <Card className="h-full hover:border-zinc-700 transition-colors cursor-pointer">
                    <CardContent className="p-5 space-y-3">
                      <div className="flex items-start justify-between gap-2">
                        <div className="flex items-center gap-2.5">
                          <span className="text-2xl">{course.coverEmoji}</span>
                          <h3 className="font-medium text-zinc-100 leading-snug">{course.title}</h3>
                        </div>
                        {course.isInActiveProgram && (
                          <Badge className="shrink-0 text-xs">In program</Badge>
                        )}
                      </div>
                      <p className="text-sm text-zinc-400 line-clamp-2 leading-relaxed">
                        {course.description}
                      </p>
                      <div className="space-y-1.5">
                        <Progress value={course.progressPct} className="h-1" />
                        <p className="text-xs text-zinc-500">
                          {course.completedCount}/{course.lessonCount} lessons
                        </p>
                      </div>
                    </CardContent>
                  </Card>
                </Link>
              ))}
            </div>
          </section>
        )
      })}
    </div>
  )
}
