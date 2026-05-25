"use client"

import { useState } from "react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { Check } from "lucide-react"

interface MissionCardProps {
  title: string
  body: string
  completed: boolean
}

export function MissionCard({ title, body, completed: initialCompleted }: MissionCardProps) {
  const [completed, setCompleted] = useState(initialCompleted)
  const [loading, setLoading] = useState(false)

  async function handleComplete() {
    setLoading(true)
    try {
      const res = await fetch("/api/missions/today/complete", { method: "POST" })
      if (res.ok) {
        setCompleted(true)
        toast.success("Mission completed!")
      } else {
        toast.error("Could not mark mission complete.")
      }
    } catch {
      toast.error("Something went wrong.")
    } finally {
      setLoading(false)
    }
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base text-zinc-100">Today&apos;s Mission</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div>
          <p className="font-medium text-zinc-100 mb-1">{title}</p>
          <p className="text-sm text-zinc-400 leading-relaxed">{body}</p>
        </div>
        {completed ? (
          <div className="flex items-center gap-2 text-sm text-emerald-400">
            <Check className="h-4 w-4" />
            <span>Completed today</span>
          </div>
        ) : (
          <Button
            size="sm"
            onClick={handleComplete}
            disabled={loading}
            className="w-full sm:w-auto"
          >
            {loading ? "Saving..." : "Mark complete"}
          </Button>
        )}
      </CardContent>
    </Card>
  )
}
