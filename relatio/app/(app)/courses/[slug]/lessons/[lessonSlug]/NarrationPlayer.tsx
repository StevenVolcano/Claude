"use client"

import { useState } from "react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"

interface NarrationPlayerProps {
  lessonSlug: string
  coachName: string
  cachedNarration: string | null
  onNarrationComplete?: (text: string) => void
}

export function NarrationPlayer({
  lessonSlug,
  coachName,
  cachedNarration: initialCached,
  onNarrationComplete,
}: NarrationPlayerProps) {
  const [narration, setNarration] = useState<string | null>(initialCached)
  const [streaming, setStreaming] = useState(false)
  const [caching, setCaching] = useState(false)

  async function startNarration() {
    setNarration("")
    setStreaming(true)
    let fullText = ""

    try {
      const res = await fetch(`/api/lessons/${lessonSlug}/narrate`, {
        method: "POST",
      })

      if (!res.ok || !res.body) {
        toast.error("Failed to start narration.")
        setStreaming(false)
        return
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ""

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split("\n")
        buffer = lines.pop() ?? ""

        for (const line of lines) {
          if (!line.startsWith("data: ")) continue
          try {
            const parsed = JSON.parse(line.slice(6))
            if (parsed.type === "delta") {
              fullText += parsed.text
              setNarration(fullText)
            } else if (parsed.type === "done") {
              setStreaming(false)
              onNarrationComplete?.(fullText)
            } else if (parsed.type === "error") {
              toast.error(parsed.message ?? "Narration error.")
              setStreaming(false)
            }
          } catch {
            // skip malformed lines
          }
        }
      }
    } catch {
      toast.error("Connection error during narration.")
    } finally {
      setStreaming(false)
    }
  }

  async function cacheNarration() {
    if (!narration) return
    setCaching(true)
    try {
      const res = await fetch(`/api/lessons/${lessonSlug}/complete`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ cacheNarration: narration }),
      })
      if (res.ok) {
        toast.success("Narration cached.")
      } else {
        toast.error("Could not cache narration.")
      }
    } catch {
      toast.error("Something went wrong.")
    } finally {
      setCaching(false)
    }
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-3 flex-wrap">
        <Button
          variant="outline"
          size="sm"
          onClick={startNarration}
          disabled={streaming}
        >
          {streaming ? "Narrating..." : `Hear from ${coachName}`}
        </Button>
        {narration && !streaming && (
          <Button
            variant="ghost"
            size="sm"
            onClick={startNarration}
          >
            Regenerate
          </Button>
        )}
      </div>

      {narration !== null && (
        <div className="rounded-lg border-l-4 border-amber-500 bg-zinc-900 p-4 space-y-3">
          <p className="text-sm text-zinc-300 leading-relaxed whitespace-pre-wrap">
            {narration}
            {streaming && (
              <span className="inline-block w-1.5 h-4 ml-1 bg-amber-500 animate-pulse rounded-sm" />
            )}
          </p>
          {!streaming && narration && !initialCached && (
            <Button
              variant="secondary"
              size="sm"
              onClick={cacheNarration}
              disabled={caching}
            >
              {caching ? "Saving..." : "Cache this narration"}
            </Button>
          )}
        </div>
      )}
    </div>
  )
}
