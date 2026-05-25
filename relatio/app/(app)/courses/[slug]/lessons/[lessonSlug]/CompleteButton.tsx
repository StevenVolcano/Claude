"use client"

import { useState } from "react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { Check } from "lucide-react"

interface CompleteButtonProps {
  lessonSlug: string
  completed: boolean
}

export function CompleteButton({ lessonSlug, completed: initialCompleted }: CompleteButtonProps) {
  const [completed, setCompleted] = useState(initialCompleted)
  const [loading, setLoading] = useState(false)

  async function toggle() {
    setLoading(true)
    try {
      if (completed) {
        const res = await fetch(`/api/lessons/${lessonSlug}/complete`, { method: "DELETE" })
        if (res.ok) {
          setCompleted(false)
          toast.success("Marked incomplete.")
        } else {
          toast.error("Could not update lesson status.")
        }
      } else {
        const res = await fetch(`/api/lessons/${lessonSlug}/complete`, { method: "POST" })
        if (res.ok) {
          setCompleted(true)
          toast.success("Lesson complete!")
        } else {
          toast.error("Could not mark lesson complete.")
        }
      }
    } catch {
      toast.error("Something went wrong.")
    } finally {
      setLoading(false)
    }
  }

  return (
    <Button
      variant={completed ? "secondary" : "default"}
      onClick={toggle}
      disabled={loading}
      className="gap-2"
    >
      {completed ? (
        <>
          <Check className="h-4 w-4" />
          Completed
        </>
      ) : (
        "Mark complete"
      )}
    </Button>
  )
}
