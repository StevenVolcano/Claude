import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { prisma } from "@/lib/prisma";
import { mapAnswersToProgram } from "@/lib/onboarding/programMapper";

const submitSchema = z.object({
  answers: z.array(z.object({ questionId: z.string(), answerId: z.string() })),
});

export async function GET() {
  const profile = await prisma.onboardingProfile.findUnique({ where: { id: 1 } });
  if (!profile) return NextResponse.json({ profile: null });

  const program = await prisma.program.findUnique({
    where: { slug: profile.programId },
    include: { courses: { include: { course: true }, orderBy: { orderIndex: "asc" } } },
  });

  return NextResponse.json({
    profile: {
      completedAt: profile.completedAt,
      programSlug: profile.programId,
      programTitle: program?.title ?? "",
      recommendedCourses:
        program?.courses.map((pc) => ({
          slug: pc.course.slug,
          title: pc.course.title,
          area: pc.course.area,
          orderIndex: pc.orderIndex,
        })) ?? [],
    },
  });
}

export async function POST(req: NextRequest) {
  const body = await req.json().catch(() => ({}));
  const parsed = submitSchema.safeParse(body);
  if (!parsed.success) return NextResponse.json({ error: "Invalid answers" }, { status: 400 });

  const programSlug = mapAnswersToProgram(parsed.data.answers);

  const profile = await prisma.onboardingProfile.upsert({
    where: { id: 1 },
    create: {
      id: 1,
      completedAt: new Date(),
      answers: JSON.stringify(parsed.data.answers),
      programId: programSlug,
    },
    update: {
      completedAt: new Date(),
      answers: JSON.stringify(parsed.data.answers),
      programId: programSlug,
    },
  });

  const program = await prisma.program.findUnique({
    where: { slug: programSlug },
    include: { courses: { include: { course: true }, orderBy: { orderIndex: "asc" } } },
  });

  return NextResponse.json({
    profile: {
      completedAt: profile.completedAt,
      programSlug,
      programTitle: program?.title ?? "",
      recommendedCourses:
        program?.courses.map((pc) => ({
          slug: pc.course.slug,
          title: pc.course.title,
          area: pc.course.area,
          orderIndex: pc.orderIndex,
        })) ?? [],
    },
  });
}

export async function DELETE() {
  await prisma.onboardingProfile.deleteMany({ where: { id: 1 } });
  return NextResponse.json({ ok: true });
}
