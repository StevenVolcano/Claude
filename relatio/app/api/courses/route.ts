import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";

export async function GET() {
  const profile = await prisma.onboardingProfile.findUnique({ where: { id: 1 } });
  let activeCourseSlug: string | null = null;

  if (profile) {
    const program = await prisma.program.findUnique({
      where: { slug: profile.programId },
      include: { courses: { include: { course: true }, orderBy: { orderIndex: "asc" } } },
    });
    activeCourseSlug = program?.courses[0]?.course.slug ?? null;
  }

  const courses = await prisma.course.findMany({
    orderBy: { orderIndex: "asc" },
    include: {
      lessons: {
        include: { progress: true },
        orderBy: { orderIndex: "asc" },
      },
    },
  });

  return NextResponse.json({
    courses: courses.map((c) => {
      const total = c.lessons.length;
      const completed = c.lessons.filter((l) => l.progress !== null).length;
      return {
        slug: c.slug,
        title: c.title,
        area: c.area,
        coverEmoji: c.coverEmoji,
        description: c.description,
        lessonCount: total,
        completedCount: completed,
        progressPct: total > 0 ? Math.round((completed / total) * 100) : 0,
        isInActiveProgram: c.slug === activeCourseSlug,
      };
    }),
  });
}
