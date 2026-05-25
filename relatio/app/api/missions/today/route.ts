import { NextResponse } from "next/server";
import { selectMissionForToday } from "@/lib/missions/selector";

export async function GET() {
  const daily = await selectMissionForToday();

  return NextResponse.json({
    mission: {
      slug: daily.mission.slug,
      title: daily.mission.title,
      body: daily.mission.body,
      area: daily.mission.area,
      date: daily.date,
      completed: daily.completedAt !== null,
      completedAt: daily.completedAt?.toISOString() ?? null,
    },
  });
}
