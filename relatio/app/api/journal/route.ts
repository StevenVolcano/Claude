import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { prisma } from "@/lib/prisma";
import { MOOD_STATES, INFLUENCE_TAGS } from "@/lib/types";
import { getDateParts } from "@/lib/utils";

const createSchema = z.object({
  mood: z.enum(MOOD_STATES),
  influence: z.enum(INFLUENCE_TAGS),
  freeText: z.string().max(1000).optional(),
});

export async function GET(req: NextRequest) {
  const { searchParams } = new URL(req.url);
  const limit = Math.min(parseInt(searchParams.get("limit") ?? "20"), 100);
  const offset = parseInt(searchParams.get("offset") ?? "0");
  const mood = searchParams.get("mood");
  const from = searchParams.get("from");
  const to = searchParams.get("to");

  const where: Record<string, unknown> = {};
  if (mood) where.mood = mood;
  if (from || to) {
    where.loggedAt = {
      ...(from ? { gte: new Date(from) } : {}),
      ...(to ? { lte: new Date(to + "T23:59:59Z") } : {}),
    };
  }

  const [entries, total] = await prisma.$transaction([
    prisma.journalEntry.findMany({
      where,
      orderBy: { loggedAt: "desc" },
      skip: offset,
      take: limit,
    }),
    prisma.journalEntry.count({ where }),
  ]);

  return NextResponse.json({
    entries: entries.map((e) => ({
      id: e.id,
      loggedAt: e.loggedAt.toISOString(),
      mood: e.mood,
      influence: e.influence,
      freeText: e.freeText,
    })),
    total,
  });
}

export async function POST(req: NextRequest) {
  const body = await req.json().catch(() => ({}));
  const parsed = createSchema.safeParse(body);
  if (!parsed.success) return NextResponse.json({ error: "Invalid data" }, { status: 400 });

  const now = new Date();
  const parts = getDateParts(now);

  const entry = await prisma.journalEntry.create({
    data: {
      mood: parsed.data.mood,
      influence: parsed.data.influence,
      freeText: parsed.data.freeText ?? null,
      ...parts,
    },
  });

  return NextResponse.json({
    entry: {
      id: entry.id,
      loggedAt: entry.loggedAt.toISOString(),
      mood: entry.mood,
      influence: entry.influence,
      freeText: entry.freeText,
    },
  });
}
