import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { subDays } from "date-fns";
import { toISODate } from "@/lib/utils";

export async function GET() {
  const from = toISODate(subDays(new Date(), 30));
  const history = await prisma.dailyMission.findMany({
    where: { date: { gte: from } },
    include: { mission: true },
    orderBy: { date: "desc" },
  });

  return NextResponse.json({
    history: history.map((d) => ({
      date: d.date,
      mission: { slug: d.mission.slug, title: d.mission.title, area: d.mission.area },
      completed: d.completedAt !== null,
      completedAt: d.completedAt?.toISOString() ?? null,
    })),
  });
}
