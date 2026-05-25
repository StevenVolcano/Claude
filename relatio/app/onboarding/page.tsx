"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { QUIZ_QUESTIONS } from "@/lib/onboarding/questions"
import { Progress } from "@/components/ui/progress"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

export default function OnboardingPage() {
  const router = useRouter()
  const [step, setStep] = useState(0)
  const [answers, setAnswers] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)

  const total = QUIZ_QUESTIONS.length
  const question = QUIZ_QUESTIONS[step]
  const currentAnswer = answers[question.id]
  const progressPct = ((step) / total) * 100

  async function handleNext() {
    if (step < total - 1) {
      setStep((s) => s + 1)
      return
    }
    // Final step — submit
    setLoading(true)
    const payload = Object.entries(answers).map(([questionId, answerId]) => ({
      questionId,
      answerId,
    }))
    try {
      await fetch("/api/onboarding", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ answers: payload }),
      })
      // Brief "Building your plan..." loading state
      await new Promise((r) => setTimeout(r, 1500))
      router.push("/dashboard")
    } catch {
      setLoading(false)
    }
  }

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-zinc-950">
        <div className="text-center space-y-4">
          <div className="w-8 h-8 border-2 border-amber-500 border-t-transparent rounded-full animate-spin mx-auto" />
          <p className="text-zinc-300 text-lg">Building your plan...</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-zinc-950 px-4">
      <div className="w-full max-w-lg space-y-8">
        {/* Progress */}
        <div className="space-y-2">
          <div className="flex justify-between text-xs text-zinc-500">
            <span>Step {step + 1} of {total}</span>
            <span>{Math.round(((step + 1) / total) * 100)}%</span>
          </div>
          <Progress value={progressPct} className="h-1.5" />
        </div>

        {/* Question */}
        <div className="space-y-6">
          <h1 className="text-xl md:text-2xl font-semibold text-zinc-100 text-center leading-snug">
            {question.text}
          </h1>

          {/* Options */}
          <div className="space-y-3">
            {question.options.map((option) => (
              <button
                key={option.id}
                onClick={() =>
                  setAnswers((prev) => ({ ...prev, [question.id]: option.id }))
                }
                className={cn(
                  "w-full text-left px-4 py-3.5 rounded-lg border text-sm font-medium transition-colors",
                  currentAnswer === option.id
                    ? "border-amber-500 bg-amber-500/10 text-zinc-100"
                    : "border-zinc-700 bg-zinc-900 text-zinc-300 hover:border-zinc-600 hover:bg-zinc-800"
                )}
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>

        {/* Navigation */}
        <div className="flex gap-3">
          {step > 0 && (
            <Button
              variant="outline"
              onClick={() => setStep((s) => s - 1)}
              className="flex-1"
            >
              Back
            </Button>
          )}
          <Button
            onClick={handleNext}
            disabled={!currentAnswer}
            className={cn("flex-1", step === 0 && "w-full")}
          >
            {step === total - 1 ? "Complete" : "Next"}
          </Button>
        </div>
      </div>
    </div>
  )
}
