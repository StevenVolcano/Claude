import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { getCoach, resolveCoachFromProfile } from "@/lib/coaches";

export async function GET(
  _req: NextRequest,
  { params }: { params: Promise<{ slug: string }> }
) {
  const { slug } = await params;

  const lesson = await prisma.lesson.findUnique({
    where: { slug },
    include: { progress: true },
  });

  if (!lesson) return NextResponse.json({ error: "Not found" }, { status: 404 });

  const profile = await prisma.onboardingProfile.findUnique({ where: { id: 1 } });
  const coachSlug =
    lesson.coachSlug === "auto"
      ? resolveCoachFromProfile(profile?.answers ?? null)
      : lesson.coachSlug;

  const coach = getCoach(coachSlug);

  return NextResponse.json({
    lesson: {
      slug: lesson.slug,
      title: lesson.title,
      bodyMarkdown: lesson.bodyMarkdown,
      coachSlug,
      coach: { name: coach.name, avatarEmoji: coach.avatarEmoji, tagline: coach.tagline },
      orderIndex: lesson.orderIndex,
      durationMin: lesson.durationMin,
      completed: lesson.progress !== null,
      completedAt: lesson.progress?.completedAt?.toISOString() ?? null,
      cachedNarration: lesson.progress?.coachNarration ?? null,
    },
  });
}
