export const MOOD_STATES = ["Energized", "Calm", "Anxious", "Low", "Frustrated", "Grateful"] as const;
export type MoodState = typeof MOOD_STATES[number];

export const INFLUENCE_TAGS = ["Partner", "Work", "Self", "Family", "Other"] as const;
export type InfluenceTag = typeof INFLUENCE_TAGS[number];

export const COURSE_AREAS = ["Communication", "EmotionalHealing", "Intimacy", "DailyWellness"] as const;
export type CourseArea = typeof COURSE_AREAS[number];

export const PROGRAM_PHASES = ["Foundation", "Practice", "Integration"] as const;
export type ProgramPhase = typeof PROGRAM_PHASES[number];

export interface Answer {
  questionId: string;
  answerId: string;
}
