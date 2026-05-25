import { Answer } from "@/lib/types";

function getAnswer(answers: Answer[], questionId: string): string | undefined {
  return answers.find((a) => a.questionId === questionId)?.answerId;
}

export function mapAnswersToProgram(answers: Answer[]): string {
  const status = getAnswer(answers, "relationship_status");
  const goal = getAnswer(answers, "primary_goal");
  const challenge = getAnswer(answers, "biggest_challenge");
  const readiness = getAnswer(answers, "readiness");
  const time = getAnswer(answers, "practice_time");

  // Rule 1: Healing track
  if (
    status === "post_breakup" ||
    status === "single_healing" ||
    goal === "emotional_recovery" ||
    challenge === "past_trauma"
  ) {
    return "foundation-healing";
  }

  // Rule 2: Single readiness (covers single_ready + any goal including deepen_intimacy)
  if (status === "single_ready") {
    return "foundation-readiness";
  }

  // Rule 3: Couples reconnect
  if (status === "coupled_struggling" || goal === "rebuild_trust") {
    return "couples-reconnect";
  }

  // Rule 4: Intimacy deepening
  if (status === "coupled_growing" && goal === "deepen_intimacy") {
    return "intimacy-deepening";
  }

  // Rule 5: Couples communication
  if (status === "coupled_growing" && goal === "improve_communication") {
    return "couples-communication";
  }

  // Rule 6: Daily wellness
  if (goal === "daily_wellness" || readiness === "crisis" || time === "5_min") {
    return "daily-wellness-habit";
  }

  return "foundation-readiness";
}
