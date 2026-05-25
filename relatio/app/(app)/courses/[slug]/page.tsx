import { baseUrl } from "@/lib/utils"
import Link from "next/link"
import { notFound } from "next/navigation"
import { Badge } from "@/components/ui/badge"
import { Progress } from "@/components/ui/progress"
import { Check, ChevronLeft, Clock } from "lucide-react"
import { cn } from "@/lib/utils"
import type { CourseArea } from "@/lib/types"

interface LessonRow {
  slug: string
  title: string
  orderIndex: number
  durationMin: number
  completed: boolean
  completedAt: string | null
}

interface CourseDetail {
  slug: string
  title: string
  area: CourseArea
  description: string
  progressPct: number
  lessons: LessonRow[]
}

const AREA_LABELS: Record<CourseArea, string> = {
  Communication: "Communication",
  EmotionalHealing: "Emotional Healing",
  Intimacy: "Intimacy",
  DailyWellness: "Daily Wellness",
}

async function getCourse(slug: string): Promise<{ course: CourseDetail } | null> {
  const res = await fetch(`${baseUrl()}/api/courses/${slug}`, {
    cache: "no-store",
  })
  if (!res.ok) return null
  return res.json()
}

export default async function CourseDetailPage({
  params,
}: {
  params: Promise<{ slug: string }>
}) {
  const { slug } = await params
  const data = await getCourse(slug)
  if (!data) notFound()

  const { course } = data
  const completedCount = course.lessons.filter((l) => l.completed).length

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      {/* Back */}
      <Link
        href="/courses"
        className="inline-flex items-center gap-1.5 text-sm text-zinc-500 hover:text-zinc-300 transition-colors"
      >
        <ChevronLeft className="h-4 w-4" />
        All courses
      </Link>

      {/* Header */}
      <div className="space-y-3">
        <div className="flex items-center gap-2 flex-wrap">
          <Badge variant="secondary">{AREA_LABELS[course.area]}</Badge>
        </div>
        <h1 className="text-2xl font-semibold text-zinc-100">{course.title}</h1>
        <p className="text-sm text-zinc-400 leading-relaxed">{course.description}</p>
        <div className="space-y-1.5">
          <div className="flex items-center gap-2">
            <Progress value={course.progressPct} className="flex-1 h-1.5" />
            <span className="text-xs text-zinc-500 shrink-0">
              {completedCount}/{course.lessons.length} lessons
            </span>
          </div>
        </div>
      </div>

      {/* Lessons list */}
      <div className="space-y-1">
        {course.lessons.map((lesson, index) => (
          <Link
            key={lesson.slug}
            href={`/courses/${course.slug}/lessons/${lesson.slug}`}
            className={cn(
              "flex items-center gap-3 px-4 py-3.5 rounded-lg border transition-colors group",
              lesson.completed
                ? "border-zinc-800 bg-zinc-900/50 hover:border-zinc-700"
                : "border-zinc-800 bg-zinc-900 hover:border-zinc-700"
            )}
          >
            {/* Number / check */}
            <div className="flex items-center justify-center w-6 h-6 shrink-0">
              {lesson.completed ? (
                <Check className="h-4 w-4 text-emerald-400" />
              ) : (
                <span className="text-xs font-medium text-zinc-500">{index + 1}</span>
              )}
            </div>

            {/* Title */}
            <span
              className={cn(
                "flex-1 text-sm font-medium",
                lesson.completed ? "text-zinc-600" : "text-zinc-100 group-hover:text-zinc-50"
              )}
            >
              {lesson.title}
            </span>

            {/* Duration */}
            <span className="flex items-center gap-1 text-xs text-zinc-600 shrink-0">
              <Clock className="h-3 w-3" />
              {lesson.durationMin}m
            </span>
          </Link>
        ))}
      </div>
    </div>
  )
}
