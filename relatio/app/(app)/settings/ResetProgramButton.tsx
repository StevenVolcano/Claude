"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { Button } from "@/components/ui/button"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog"
import { toast } from "sonner"

export function ResetProgramButton() {
  const router = useRouter()
  const [loading, setLoading] = useState(false)

  async function handleReset() {
    setLoading(true)
    try {
      const res = await fetch("/api/onboarding", { method: "DELETE" })
      if (res.ok) {
        toast.success("Program reset.")
        router.push("/onboarding")
      } else {
        toast.error("Could not reset program.")
      }
    } catch {
      toast.error("Something went wrong.")
    } finally {
      setLoading(false)
    }
  }

  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button variant="destructive" size="sm" disabled={loading}>
          Reset my program
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Reset your program?</AlertDialogTitle>
          <AlertDialogDescription>
            This will clear your onboarding answers and program assignment. You&apos;ll be taken
            back through the onboarding quiz. Your journal entries and lesson progress will not be
            deleted.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={handleReset} disabled={loading}>
            {loading ? "Resetting..." : "Yes, reset"}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
