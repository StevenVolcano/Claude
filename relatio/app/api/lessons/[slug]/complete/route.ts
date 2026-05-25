import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { prisma } from "@/lib/prisma";

const schema = z.object({ cacheNarration: z.string().optional() });

export async function POST(
  req: NextRequest,
  { params }: { params: Promise<{ slug: string }> }
) {
  const { slug } = await params;
  const lesson = await prisma.lesson.findUnique({ where: { slug } });
  if (!lesson) return NextResponse.json({ error: "Not found" }, { status: 404 });

  const body = await req.json().catch(() => ({}));
  const parsed = schema.safeParse(body);

  const progress = await prisma.lessonProgress.upsert({
    where: { lessonId: lesson.id },
    create: {
      lessonId: lesson.id,
      completedAt: new Date(),
      coachNarration: parsed.success ? (parsed.data.cacheNarration ?? null) : null,
    },
    update: {
      completedAt: new Date(),
      ...(parsed.success && parsed.data.cacheNarration
        ? { coachNarration: parsed.data.cacheNarration }
        : {}),
    },
  });

  return NextResponse.json({ completedAt: progress.completedAt.toISOString() });
}

export async function DELETE(
  _req: NextRequest,
  { params }: { params: Promise<{ slug: string }> }
) {
  const { slug } = await params;
  const lesson = await prisma.lesson.findUnique({ where: { slug } });
  if (!lesson) return NextResponse.json({ error: "Not found" }, { status: 404 });
  await prisma.lessonProgress.deleteMany({ where: { lessonId: lesson.id } });
  return NextResponse.json({ ok: true });
}
