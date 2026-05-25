import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { getGreeting, toISODate } from "@/lib/utils";
import { subDays } from "date-fns";

export async function GET() {
  const today = toISODate();
  const profile = await prisma.onboardingProfile.findUnique({ where: { id: 1 } });

  let activeCourse = null;
  if (profile) {
    const program = await prisma.program.findUnique({
      where: { slug: profile.programId },
      include: {
        courses: {
          include: {
            course: {
              include: {
                lessons: {
                  include: { progress: true },
                  orderBy: { orderIndex: "asc" },
                },
              },
            },
          },
          orderBy: { orderIndex: "asc" },
        },
      },
    });

    if (program) {
      const firstCourse = program.courses[0]?.course;
      if (firstCourse) {
        const total = firstCourse.lessons.length;
        const completed = firstCourse.lessons.filter((l) => l.progress !== null).length;
        const nextLesson = firstCourse.lessons.find((l) => l.progress === null);
        activeCourse = {
          slug: firstCourse.slug,
          title: firstCourse.title,
          progressPct: total > 0 ? Math.round((completed / total) * 100) : 0,
          nextLesson: nextLesson
            ? { slug: nextLesson.slug, title: nextLesson.title }
            : null,
        };
      }
    }
  }

  const todayMissionRecord = await prisma.dailyMission.findUnique({
    where: { date: today },
    include: { mission: true },
  });

  const todayJournal = await prisma.journalEntry.findFirst({
    where: { loggedAt: { gte: new Date(today) } },
    orderBy: { loggedAt: "desc" },
  });

  // Streak
  const allEntries = await prisma.journalEntry.findMany({
    select: { loggedAt: true },
    orderBy: { loggedAt: "desc" },
  });
  const entryDates = new Set(allEntries.map((e) => toISODate(e.loggedAt)));
  let streakDays = 0;
  let checkDate = new Date();
  while (entryDates.has(toISODate(checkDate))) {
    streakDays++;
    checkDate = subDays(checkDate, 1);
  }

  return NextResponse.json({
    greeting: getGreeting(),
    activeCourse,
    todayMission: todayMissionRecord
      ? {
          slug: todayMissionRecord.mission.slug,
          title: todayMissionRecord.mission.title,
          body: todayMissionRecord.mission.body,
          completed: todayMissionRecord.completedAt !== null,
        }
      : null,
    todayJournalEntry: todayJournal
      ? { mood: todayJournal.mood, loggedAt: todayJournal.loggedAt.toISOString() }
      : null,
    streakDays,
  });
}
