import * as fs from 'fs';
import * as path from 'path';
import { PrismaClient } from '../app/generated/prisma';
import { PrismaBetterSqlite3 } from '@prisma/adapter-better-sqlite3';

// Load env vars
require('dotenv').config({ path: path.join(__dirname, '..', '.env') });

const rawUrl = process.env.DATABASE_URL ?? 'file:./dev.db';
// Resolve relative file path to absolute, since seed runs from project root
const dbFile = rawUrl.replace(/^file:/, '');
const dbAbsolute = path.isAbsolute(dbFile)
  ? dbFile
  : path.resolve(path.join(__dirname, '..'), dbFile);

const adapter = new PrismaBetterSqlite3({ url: dbAbsolute });
const prisma = new PrismaClient({ adapter } as any);

// ─────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────

function readLesson(relPath: string): string {
  const abs = path.join(__dirname, '..', 'content', relPath);
  return fs.readFileSync(abs, 'utf-8');
}

function readMissions(): any[] {
  const abs = path.join(__dirname, '..', 'content', 'missions', 'missions.json');
  return JSON.parse(fs.readFileSync(abs, 'utf-8'));
}

// ─────────────────────────────────────────────────────────────────
// Data definitions
// ─────────────────────────────────────────────────────────────────

const programs = [
  {
    slug: 'foundation-healing',
    title: 'Healing Foundation',
    phase: 'Foundation',
    description: 'For those beginning emotional recovery',
  },
  {
    slug: 'foundation-readiness',
    title: 'Readiness & Self-Clarity',
    phase: 'Foundation',
    description: 'For singles building self-awareness',
  },
  {
    slug: 'couples-communication',
    title: 'Connected Partnership',
    phase: 'Practice',
    description: 'For couples improving communication',
  },
  {
    slug: 'couples-reconnect',
    title: 'Reconnection Program',
    phase: 'Practice',
    description: 'For couples in difficulty or rebuilding trust',
  },
  {
    slug: 'intimacy-deepening',
    title: 'Intimacy & Depth',
    phase: 'Practice',
    description: 'For deepening connection and closeness',
  },
  {
    slug: 'daily-wellness-habit',
    title: 'Daily Wellness Habit',
    phase: 'Foundation',
    description: 'For building sustainable daily practice',
  },
];

const courses = [
  {
    slug: 'communication-basics',
    title: 'Communication Basics',
    description: 'Learn the foundations of clear, empathetic communication that builds trust instead of walls.',
    area: 'Communication',
    coachSlug: 'arnold',
    coverEmoji: '💬',
    orderIndex: 1,
  },
  {
    slug: 'emotional-healing-101',
    title: 'Emotional Healing 101',
    description: 'A gentle, structured path through emotional pain toward clarity, self-compassion, and renewal.',
    area: 'EmotionalHealing',
    coachSlug: 'julie',
    coverEmoji: '🌿',
    orderIndex: 2,
  },
  {
    slug: 'intimacy-foundations',
    title: 'Intimacy Foundations',
    description: 'Discover the emotional and physical dimensions of intimacy — and how to cultivate them intentionally.',
    area: 'Intimacy',
    coachSlug: 'julie',
    coverEmoji: '🕯️',
    orderIndex: 3,
  },
  {
    slug: 'daily-wellness-core',
    title: 'Daily Wellness Core',
    description: 'Build the daily emotional health habits that make everything else in your relationships easier.',
    area: 'DailyWellness',
    coachSlug: 'arnold',
    coverEmoji: '☀️',
    orderIndex: 4,
  },
];

// Each lesson: { slug, title, durationMin, orderIndex, coachSlug, courseSlug, filePath }
const lessons = [
  // Communication Basics
  {
    slug: 'communication-basics-01',
    title: 'Why We Misunderstand Each Other',
    durationMin: 5,
    orderIndex: 1,
    coachSlug: 'arnold',
    courseSlug: 'communication-basics',
    filePath: 'courses/communication-basics/01-why-we-misunderstand.md',
  },
  {
    slug: 'communication-basics-02',
    title: 'Active Listening Is Not Waiting to Talk',
    durationMin: 6,
    orderIndex: 2,
    coachSlug: 'arnold',
    courseSlug: 'communication-basics',
    filePath: 'courses/communication-basics/02-active-listening.md',
  },
  {
    slug: 'communication-basics-03',
    title: 'The Art of the Soft Startup',
    durationMin: 5,
    orderIndex: 3,
    coachSlug: 'arnold',
    courseSlug: 'communication-basics',
    filePath: 'courses/communication-basics/03-soft-startup.md',
  },
  {
    slug: 'communication-basics-04',
    title: 'Repair: How to Stop a Fight Getting Worse',
    durationMin: 6,
    orderIndex: 4,
    coachSlug: 'arnold',
    courseSlug: 'communication-basics',
    filePath: 'courses/communication-basics/04-repair.md',
  },
  {
    slug: 'communication-basics-05',
    title: 'Expressing Needs Without Ultimatums',
    durationMin: 7,
    orderIndex: 5,
    coachSlug: 'arnold',
    courseSlug: 'communication-basics',
    filePath: 'courses/communication-basics/05-expressing-needs.md',
  },
  // Emotional Healing 101
  {
    slug: 'emotional-healing-01',
    title: "Why Healing Isn't Linear",
    durationMin: 5,
    orderIndex: 1,
    coachSlug: 'julie',
    courseSlug: 'emotional-healing-101',
    filePath: 'courses/emotional-healing-101/01-healing-not-linear.md',
  },
  {
    slug: 'emotional-healing-02',
    title: 'The Body Keeps the Score',
    durationMin: 6,
    orderIndex: 2,
    coachSlug: 'julie',
    courseSlug: 'emotional-healing-101',
    filePath: 'courses/emotional-healing-101/02-body-keeps-score.md',
  },
  {
    slug: 'emotional-healing-03',
    title: "Self-Compassion: The Foundation You Can't Skip",
    durationMin: 6,
    orderIndex: 3,
    coachSlug: 'julie',
    courseSlug: 'emotional-healing-101',
    filePath: 'courses/emotional-healing-101/03-self-compassion.md',
  },
  {
    slug: 'emotional-healing-04',
    title: 'Identifying Your Patterns',
    durationMin: 7,
    orderIndex: 4,
    coachSlug: 'julie',
    courseSlug: 'emotional-healing-101',
    filePath: 'courses/emotional-healing-101/04-identifying-patterns.md',
  },
  {
    slug: 'emotional-healing-05',
    title: 'Moving Forward Without Forgetting',
    durationMin: 7,
    orderIndex: 5,
    coachSlug: 'julie',
    courseSlug: 'emotional-healing-101',
    filePath: 'courses/emotional-healing-101/05-moving-forward.md',
  },
  // Intimacy Foundations
  {
    slug: 'intimacy-foundations-01',
    title: 'What Intimacy Actually Is',
    durationMin: 5,
    orderIndex: 1,
    coachSlug: 'julie',
    courseSlug: 'intimacy-foundations',
    filePath: 'courses/intimacy-foundations/01-what-intimacy-is.md',
  },
  {
    slug: 'intimacy-foundations-02',
    title: 'Vulnerability Is Not Weakness',
    durationMin: 6,
    orderIndex: 2,
    coachSlug: 'julie',
    courseSlug: 'intimacy-foundations',
    filePath: 'courses/intimacy-foundations/02-vulnerability.md',
  },
  {
    slug: 'intimacy-foundations-03',
    title: 'Appreciation as a Practice',
    durationMin: 5,
    orderIndex: 3,
    coachSlug: 'julie',
    courseSlug: 'intimacy-foundations',
    filePath: 'courses/intimacy-foundations/03-appreciation.md',
  },
  {
    slug: 'intimacy-foundations-04',
    title: 'The Role of Play and Novelty',
    durationMin: 6,
    orderIndex: 4,
    coachSlug: 'julie',
    courseSlug: 'intimacy-foundations',
    filePath: 'courses/intimacy-foundations/04-play-and-novelty.md',
  },
  {
    slug: 'intimacy-foundations-05',
    title: 'Deepening the Practice: Daily Rituals of Connection',
    durationMin: 7,
    orderIndex: 5,
    coachSlug: 'julie',
    courseSlug: 'intimacy-foundations',
    filePath: 'courses/intimacy-foundations/05-daily-rituals.md',
  },
  // Daily Wellness Core
  {
    slug: 'daily-wellness-01',
    title: 'Why Emotional Health Is a Daily Practice',
    durationMin: 5,
    orderIndex: 1,
    coachSlug: 'arnold',
    courseSlug: 'daily-wellness-core',
    filePath: 'courses/daily-wellness-core/01-daily-practice.md',
  },
  {
    slug: 'daily-wellness-02',
    title: 'The Morning Set: Starting Your Day Intentionally',
    durationMin: 6,
    orderIndex: 2,
    coachSlug: 'arnold',
    courseSlug: 'daily-wellness-core',
    filePath: 'courses/daily-wellness-core/02-morning-set.md',
  },
  {
    slug: 'daily-wellness-03',
    title: 'Mood Awareness: Naming What You Feel',
    durationMin: 6,
    orderIndex: 3,
    coachSlug: 'arnold',
    courseSlug: 'daily-wellness-core',
    filePath: 'courses/daily-wellness-core/03-mood-awareness.md',
  },
  {
    slug: 'daily-wellness-04',
    title: 'Managing Stress Before It Manages You',
    durationMin: 7,
    orderIndex: 4,
    coachSlug: 'arnold',
    courseSlug: 'daily-wellness-core',
    filePath: 'courses/daily-wellness-core/04-managing-stress.md',
  },
  {
    slug: 'daily-wellness-05',
    title: 'Rest, Recovery, and the Relationship You Have With Yourself',
    durationMin: 7,
    orderIndex: 5,
    coachSlug: 'arnold',
    courseSlug: 'daily-wellness-core',
    filePath: 'courses/daily-wellness-core/05-rest-and-recovery.md',
  },
];

// ProgramCourse mappings: programSlug → [{ courseSlug, orderIndex }]
const programCourseMap: Record<string, { courseSlug: string; orderIndex: number }[]> = {
  'foundation-healing': [
    { courseSlug: 'emotional-healing-101', orderIndex: 1 },
    { courseSlug: 'daily-wellness-core', orderIndex: 2 },
    { courseSlug: 'communication-basics', orderIndex: 3 },
  ],
  'foundation-readiness': [
    { courseSlug: 'daily-wellness-core', orderIndex: 1 },
    { courseSlug: 'communication-basics', orderIndex: 2 },
    { courseSlug: 'emotional-healing-101', orderIndex: 3 },
  ],
  'couples-communication': [
    { courseSlug: 'communication-basics', orderIndex: 1 },
    { courseSlug: 'intimacy-foundations', orderIndex: 2 },
    { courseSlug: 'daily-wellness-core', orderIndex: 3 },
  ],
  'couples-reconnect': [
    { courseSlug: 'emotional-healing-101', orderIndex: 1 },
    { courseSlug: 'communication-basics', orderIndex: 2 },
    { courseSlug: 'intimacy-foundations', orderIndex: 3 },
  ],
  'intimacy-deepening': [
    { courseSlug: 'intimacy-foundations', orderIndex: 1 },
    { courseSlug: 'emotional-healing-101', orderIndex: 2 },
    { courseSlug: 'daily-wellness-core', orderIndex: 3 },
  ],
  'daily-wellness-habit': [
    { courseSlug: 'daily-wellness-core', orderIndex: 1 },
    { courseSlug: 'emotional-healing-101', orderIndex: 2 },
    { courseSlug: 'communication-basics', orderIndex: 3 },
  ],
};

// ─────────────────────────────────────────────────────────────────
// Main seed
// ─────────────────────────────────────────────────────────────────

async function main() {
  console.log('Seeding...');

  // 1. Programs
  for (const p of programs) {
    await prisma.program.upsert({
      where: { slug: p.slug },
      update: { title: p.title, phase: p.phase, description: p.description },
      create: { slug: p.slug, title: p.title, phase: p.phase, description: p.description },
    });
  }
  console.log(`  Programs: ${programs.length}`);

  // 2. Courses (upsert without coachSlug — not in schema)
  for (const c of courses) {
    await (prisma.course as any).upsert({
      where: { slug: c.slug },
      update: {
        title: c.title,
        description: c.description,
        area: c.area,
        coverEmoji: c.coverEmoji,
        orderIndex: c.orderIndex,
      },
      create: {
        slug: c.slug,
        title: c.title,
        description: c.description,
        area: c.area,
        coverEmoji: c.coverEmoji,
        orderIndex: c.orderIndex,
      },
    });
  }
  console.log(`  Courses: ${courses.length}`);

  // Build courseSlug → courseId map
  const courseRecords = await prisma.course.findMany({ select: { id: true, slug: true } });
  const courseIdBySlug: Record<string, number> = {};
  for (const cr of courseRecords) {
    courseIdBySlug[cr.slug] = cr.id;
  }

  // 3. Lessons
  for (const l of lessons) {
    const body = readLesson(l.filePath);
    const courseId = courseIdBySlug[l.courseSlug];
    await prisma.lesson.upsert({
      where: { slug: l.slug },
      update: {
        title: l.title,
        bodyMarkdown: body,
        coachSlug: l.coachSlug,
        orderIndex: l.orderIndex,
        durationMin: l.durationMin,
        courseId,
      },
      create: {
        slug: l.slug,
        title: l.title,
        bodyMarkdown: body,
        coachSlug: l.coachSlug,
        orderIndex: l.orderIndex,
        durationMin: l.durationMin,
        courseId,
      },
    });
  }
  console.log(`  Lessons: ${lessons.length}`);

  // 4. ProgramCourse joins
  const programRecords = await prisma.program.findMany({ select: { id: true, slug: true } });
  const programIdBySlug: Record<string, number> = {};
  for (const pr of programRecords) {
    programIdBySlug[pr.slug] = pr.id;
  }

  let pcCount = 0;
  for (const [programSlug, courseList] of Object.entries(programCourseMap)) {
    const programId = programIdBySlug[programSlug];
    for (const { courseSlug, orderIndex } of courseList) {
      const courseId = courseIdBySlug[courseSlug];
      await prisma.programCourse.upsert({
        where: { programId_courseId: { programId, courseId } },
        update: { orderIndex },
        create: { programId, courseId, orderIndex },
      });
      pcCount++;
    }
  }
  console.log(`  ProgramCourse joins: ${pcCount}`);

  // 5. Missions
  const missions = readMissions();
  for (const m of missions) {
    await prisma.mission.upsert({
      where: { slug: m.slug },
      update: {
        title: m.title,
        body: m.body,
        area: m.area,
        phase: m.phase,
        tags: m.tags,
        courseId: null,
      },
      create: {
        slug: m.slug,
        title: m.title,
        body: m.body,
        area: m.area,
        phase: m.phase,
        tags: m.tags,
        courseId: null,
      },
    });
  }
  console.log(`  Missions: ${missions.length}`);

  console.log(
    `\nSeeded ${programs.length} programs, ${courses.length} courses, ${lessons.length} lessons, ${missions.length} missions`
  );
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
