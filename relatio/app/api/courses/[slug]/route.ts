import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";

export async function GET(
  _req: NextRequest,
  { params }: { params: Promise<{ slug: string }> }
) {
  const { slug } = await params;

  const course = await prisma.course.findUnique({
    where: { slug },
    include: {
      lessons: {
        include: { progress: true },
        orderBy: { orderIndex: "asc" },
      },
    },
  });

  if (!course) return NextResponse.json({ error: "Not found" }, { status: 404 });

  const total = course.lessons.length;
  const completed = course.lessons.filter((l) => l.progress !== null).length;

  return NextResponse.json({
    course: {
      slug: course.slug,
      title: course.title,
      area: course.area,
      description: course.description,
      progressPct: total > 0 ? Math.round((completed / total) * 100) : 0,
      lessons: course.lessons.map((l) => ({
        slug: l.slug,
        title: l.title,
        orderIndex: l.orderIndex,
        durationMin: l.durationMin,
        coachSlug: l.coachSlug,
        completed: l.progress !== null,
        completedAt: l.progress?.completedAt?.toISOString() ?? null,
      })),
    },
  });
}
