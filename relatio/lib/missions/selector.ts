import { prisma } from "@/lib/prisma";
import { toISODate } from "@/lib/utils";

function deterministicIndex(date: string, length: number): number {
  let hash = 0;
  for (let i = 0; i < date.length; i++) {
    hash = (hash * 31 + date.charCodeAt(i)) >>> 0;
  }
  return hash % length;
}

export async function selectMissionForToday() {
  const today = toISODate();

  // Check if today's mission already exists
  const existing = await prisma.dailyMission.findUnique({
    where: { date: today },
    include: { mission: true },
  });
  if (existing) return existing;

  // Get user's active program phase
  const profile = await prisma.onboardingProfile.findUnique({ where: { id: 1 } });

  let phase = "Foundation";
  let activeCourseIds: number[] = [];

  if (profile) {
    const program = await prisma.program.findUnique({
      where: { slug: profile.programId },
      include: { courses: { include: { course: true } } },
    });
    if (program) {
      phase = program.phase;
      activeCourseIds = program.courses.map((pc) => pc.courseId);
    }
  }

  // Get recently used mission slugs (last 14 days)
  const recent = await prisma.dailyMission.findMany({
    orderBy: { date: "desc" },
    take: 14,
    select: { missionId: true },
  });
  const recentIds = new Set(recent.map((r) => r.missionId));

  // Query eligible missions
  const findEligible = async (excludeRecent: boolean) => {
    const candidates = await prisma.mission.findMany({
      where: {
        phase,
        OR: [
          { courseId: null },
          ...(activeCourseIds.length > 0
            ? [{ courseId: { in: activeCourseIds } }]
            : []),
        ],
      },
    });
    return excludeRecent ? candidates.filter((m) => !recentIds.has(m.id)) : candidates;
  };

  let eligible = await findEligible(true);
  if (eligible.length === 0) eligible = await findEligible(false);
  if (eligible.length === 0) {
    eligible = await prisma.mission.findMany({ take: 10 });
  }

  if (eligible.length === 0) {
    throw new Error("No missions in database — run prisma db seed first");
  }
  const selected = eligible[deterministicIndex(today, eligible.length)];

  const daily = await prisma.dailyMission.create({
    data: { date: today, missionId: selected.id },
    include: { mission: true },
  });

  return daily;
}
