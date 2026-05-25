import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { toISODate } from "@/lib/utils";

export async function POST() {
  const today = toISODate();
  const daily = await prisma.dailyMission.findUnique({ where: { date: today } });
  if (!daily) return NextResponse.json({ error: "No mission for today" }, { status: 404 });

  const updated = await prisma.dailyMission.update({
    where: { date: today },
    data: { completedAt: new Date() },
    include: { mission: true },
  });

  return NextResponse.json({ completedAt: updated.completedAt?.toISOString() });
}
