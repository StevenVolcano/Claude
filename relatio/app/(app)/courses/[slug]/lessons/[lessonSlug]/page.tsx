import { baseUrl } from "@/lib/utils"
import { notFound } from "next/navigation"
import Link from "next/link"
import ReactMarkdown from "react-markdown"
import { ChevronLeft, ChevronRight, Clock } from "lucide-react"
import { NarrationPlayer } from "./NarrationPlayer"
import { CompleteButton } from "./CompleteButton"
import { cn } from "@/lib/utils"

interface LessonData {
  slug: string
  title: string
  bodyMarkdown: string
  coachSlug: string
  coach: { name: string; avatarEmoji: string; tagline: string }
  orderIndex: number
  durationMin: number
  completed: boolean
  completedAt: string | null
  cachedNarration: string | null
}

interface CourseLessonRef {
  slug: string
  title: string
  orderIndex: number
}

async function getLesson(slug: string): Promise<{ lesson: LessonData } | null> {
  const res = await fetch(`${baseUrl()}/api/lessons/${slug}`, {
    cache: "no-store",
  })
  if (!res.ok) return null
  return res.json()
}

async function getCourse(slug: string): Promise<{ lessons: CourseLessonRef[] } | null> {
  const res = await fetch(`${baseUrl()}/api/courses/${slug}`, {
    cache: "no-store",
  })
  if (!res.ok) return null
  const data = await res.json()
  return data.course ?? null
}

export default async function LessonPage({
  params,
}: {
  params: Promise<{ slug: string; lessonSlug: string }>
}) {
  const { slug, lessonSlug } = await params
  const [lessonData, courseData] = await Promise.all([
    getLesson(lessonSlug),
    getCourse(slug),
  ])

  if (!lessonData) notFound()

  const { lesson } = lessonData
  const lessons = courseData?.lessons ?? []
  const sortedLessons = [...lessons].sort((a, b) => a.orderIndex - b.orderIndex)
  const currentIndex = sortedLessons.findIndex((l) => l.slug === lessonSlug)
  const prevLesson = currentIndex > 0 ? sortedLessons[currentIndex - 1] : null
  const nextLesson = currentIndex < sortedLessons.length - 1 ? sortedLessons[currentIndex + 1] : null

  return (
    <div className="max-w-2xl mx-auto space-y-8">
      {/* Back link */}
      <Link
        href={`/courses/${slug}`}
        className="inline-flex items-center gap-1.5 text-sm text-zinc-500 hover:text-zinc-300 transition-colors"
      >
        <ChevronLeft className="h-4 w-4" />
        Back to course
      </Link>

      {/* Lesson header */}
      <div className="space-y-2">
        <div className="flex items-center gap-2 text-xs text-zinc-500">
          <Clock className="h-3 w-3" />
          <span>{lesson.durationMin} min</span>
          {lesson.completed && (
            <span className="text-emerald-400 font-medium">Completed</span>
          )}
        </div>
        <h1 className="text-2xl font-semibold text-zinc-100">{lesson.title}</h1>
      </div>

      {/* Coach bubble */}
      <div className="rounded-lg border-l-4 border-amber-500 bg-zinc-900 border border-zinc-800 p-4 flex items-start gap-3">
        <span className="text-2xl">{lesson.coach.avatarEmoji}</span>
        <div>
          <p className="font-medium text-zinc-100 text-sm">{lesson.coach.name}</p>
          <p className="text-xs text-zinc-500">{lesson.coach.tagline}</p>
        </div>
      </div>

      {/* Lesson body */}
      <div className="prose prose-zinc prose-invert prose-sm max-w-none">
        <ReactMarkdown
          components={{
            h1: ({ children }) => <h1 className="text-xl font-semibold text-zinc-100 mt-6 mb-3">{children}</h1>,
            h2: ({ children }) => <h2 className="text-lg font-semibold text-zinc-100 mt-5 mb-2">{children}</h2>,
            h3: ({ children }) => <h3 className="text-base font-semibold text-zinc-200 mt-4 mb-2">{children}</h3>,
            p: ({ children }) => <p className="text-zinc-300 leading-relaxed mb-4">{children}</p>,
            ul: ({ children }) => <ul className="list-disc pl-5 space-y-1.5 mb-4 text-zinc-300">{children}</ul>,
            ol: ({ children }) => <ol className="list-decimal pl-5 space-y-1.5 mb-4 text-zinc-300">{children}</ol>,
            li: ({ children }) => <li className="leading-relaxed">{children}</li>,
            strong: ({ children }) => <strong className="font-semibold text-zinc-100">{children}</strong>,
            em: ({ children }) => <em className="italic text-zinc-300">{children}</em>,
            blockquote: ({ children }) => (
              <blockquote className="border-l-4 border-zinc-700 pl-4 italic text-zinc-400 my-4">{children}</blockquote>
            ),
            hr: () => <hr className="border-zinc-800 my-6" />,
          }}
        >
          {lesson.bodyMarkdown}
        </ReactMarkdown>
      </div>

      {/* Narration */}
      <div className="space-y-2">
        <h2 className="text-sm font-semibold text-zinc-400 uppercase tracking-wider">
          Coach Narration
        </h2>
        <NarrationPlayer
          lessonSlug={lesson.slug}
          coachName={lesson.coach.name}
          cachedNarration={lesson.cachedNarration}
        />
      </div>

      {/* Complete button */}
      <div className="pt-2">
        <CompleteButton lessonSlug={lesson.slug} completed={lesson.completed} />
      </div>

      {/* Prev / Next navigation */}
      <div className={cn("flex gap-3 pt-2", prevLesson && nextLesson ? "justify-between" : nextLesson ? "justify-end" : "justify-start")}>
        {prevLesson && (
          <Link
            href={`/courses/${slug}/lessons/${prevLesson.slug}`}
            className="flex items-center gap-1.5 text-sm text-zinc-500 hover:text-zinc-300 transition-colors"
          >
            <ChevronLeft className="h-4 w-4" />
            {prevLesson.title}
          </Link>
        )}
        {nextLesson && (
          <Link
            href={`/courses/${slug}/lessons/${nextLesson.slug}`}
            className="flex items-center gap-1.5 text-sm text-zinc-500 hover:text-zinc-300 transition-colors"
          >
            {nextLesson.title}
            <ChevronRight className="h-4 w-4" />
          </Link>
        )}
      </div>
    </div>
  )
}
