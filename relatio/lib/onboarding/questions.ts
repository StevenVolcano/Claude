export interface QuizOption {
  id: string;
  label: string;
}

export interface QuizQuestion {
  id: string;
  text: string;
  options: QuizOption[];
}

export const QUIZ_QUESTIONS: QuizQuestion[] = [
  {
    id: "relationship_status",
    text: "Which best describes your relationship right now?",
    options: [
      { id: "single_healing", label: "I'm single and focused on personal healing" },
      { id: "single_ready", label: "I'm single and ready to attract a healthy relationship" },
      { id: "coupled_growing", label: "I'm in a relationship and we're actively growing together" },
      { id: "coupled_struggling", label: "I'm in a relationship and we're going through difficulty" },
      { id: "post_breakup", label: "I recently ended a relationship" },
    ],
  },
  {
    id: "primary_goal",
    text: "What is your main goal right now?",
    options: [
      { id: "improve_communication", label: "Improve how I communicate with a partner" },
      { id: "emotional_recovery", label: "Heal from emotional pain or past wounds" },
      { id: "deepen_intimacy", label: "Deepen intimacy and connection" },
      { id: "daily_wellness", label: "Build daily emotional health habits" },
      { id: "rebuild_trust", label: "Rebuild trust after a rupture" },
    ],
  },
  {
    id: "biggest_challenge",
    text: "What's your biggest challenge in relationships?",
    options: [
      { id: "conflict", label: "Handling conflict without it escalating" },
      { id: "emotional_walls", label: "Letting people in — I keep walls up" },
      { id: "losing_self", label: "I lose myself in relationships" },
      { id: "expressing_needs", label: "Asking for what I need without guilt" },
      { id: "past_trauma", label: "Patterns from my past keep showing up" },
    ],
  },
  {
    id: "readiness",
    text: "How ready do you feel to do this inner work?",
    options: [
      { id: "very_ready", label: "Very ready — I've been preparing for this" },
      { id: "mostly_ready", label: "Mostly ready — a little nervous but committed" },
      { id: "unsure", label: "Unsure — I need gentle guidance first" },
      { id: "crisis", label: "I'm in a rough place and need immediate support" },
    ],
  },
  {
    id: "practice_time",
    text: "How much time can you realistically spend on this each day?",
    options: [
      { id: "5_min", label: "5 minutes" },
      { id: "10_15_min", label: "10–15 minutes" },
      { id: "20_30_min", label: "20–30 minutes" },
      { id: "more", label: "As much as it takes" },
    ],
  },
  {
    id: "learning_style",
    text: "How do you prefer to learn and grow?",
    options: [
      { id: "reading", label: "Reading and reflecting" },
      { id: "exercises", label: "Hands-on exercises and practices" },
      { id: "journaling", label: "Journaling and self-discovery" },
      { id: "blend", label: "A blend of all of the above" },
    ],
  },
  {
    id: "coach_preference",
    text: "Which coach style feels right for you?",
    options: [
      { id: "arnold", label: "Direct, grounding, masculine energy (Arnold)" },
      { id: "julie", label: "Warm, empathetic, nurturing energy (Julie)" },
      { id: "no_preference", label: "No preference — surprise me" },
    ],
  },
];
