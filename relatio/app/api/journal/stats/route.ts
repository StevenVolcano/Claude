import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { MOOD_STATES, INFLUENCE_TAGS, MoodState, InfluenceTag } from "@/lib/types";
import { toISODate } from "@/lib/utils";
import { subDays } from "date-fns";

export async function GET() {
  const allEntries = await prisma.journalEntry.findMany({ orderBy: { loggedAt: "desc" } });

  const moodFrequency = Object.fromEntries(
    MOOD_STATES.map((m) => [m, 0])
  ) as Record<MoodState, number>;
  const influenceFrequency = Object.fromEntries(
    INFLUENCE_TAGS.map((t) => [t, 0])
  ) as Record<InfluenceTag, number>;

  for (const e of allEntries) {
    if (moodFrequency[e.mood as MoodState] !== undefined)
      moodFrequency[e.mood as MoodState]++;
    if (influenceFrequency[e.influence as InfluenceTag] !== undefined)
      influenceFrequency[e.influence as InfluenceTag]++;
  }

  const mostCommonMood = (
    Object.entries(moodFrequency).sort(([, a], [, b]) => b - a)[0]?.[0] ?? "Calm"
  ) as MoodState;
  const mostCommonInfluence = (
    Object.entries(influenceFrequency).sort(([, a], [, b]) => b - a)[0]?.[0] ?? "Self"
  ) as InfluenceTag;

  const thirtyDaysAgo = subDays(new Date(), 30);
  const recent = allEntries.filter((e) => e.loggedAt >= thirtyDaysAgo);

  // Streak: count consecutive days with entries going back from today
  const entryDates = new Set(allEntries.map((e) => toISODate(e.loggedAt)));
  let streakDays = 0;
  let checkDate = new Date();
  while (entryDates.has(toISODate(checkDate))) {
    streakDays++;
    checkDate = subDays(checkDate, 1);
  }

  return NextResponse.json({
    stats: {
      moodFrequency,
      last30Days: recent.map((e) => ({
        date: toISODate(e.loggedAt),
        mood: e.mood,
        influence: e.influence,
      })),
      mostCommonMood,
      mostCommonInfluence,
      streakDays,
    },
  });
}
