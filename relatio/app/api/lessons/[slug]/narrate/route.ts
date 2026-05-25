import { NextRequest } from "next/server";
import Anthropic from "@anthropic-ai/sdk";
import { prisma } from "@/lib/prisma";
import { getCoach, resolveCoachFromProfile } from "@/lib/coaches";

export async function POST(
  _req: NextRequest,
  { params }: { params: Promise<{ slug: string }> }
) {
  const { slug } = await params;

  const lesson = await prisma.lesson.findUnique({ where: { slug } });
  if (!lesson) {
    return new Response(JSON.stringify({ error: "Not found" }), { status: 404 });
  }

  const profile = await prisma.onboardingProfile.findUnique({ where: { id: 1 } });
  const coachSlug =
    lesson.coachSlug === "auto"
      ? resolveCoachFromProfile(profile?.answers ?? null)
      : lesson.coachSlug;

  const coach = getCoach(coachSlug);
  const anthropic = new Anthropic();

  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    async start(controller) {
      try {
        const response = await anthropic.messages.stream({
          model: "claude-sonnet-4-6",
          max_tokens: 600,
          system: coach.systemPrompt,
          messages: [
            {
              role: "user",
              content: `Please narrate the following lesson in your voice:\n\n---\n${lesson.bodyMarkdown}\n---`,
            },
          ],
        });

        for await (const chunk of response) {
          if (
            chunk.type === "content_block_delta" &&
            chunk.delta.type === "text_delta"
          ) {
            const data = JSON.stringify({ type: "delta", text: chunk.delta.text });
            controller.enqueue(encoder.encode(`data: ${data}\n\n`));
          }
        }

        controller.enqueue(
          encoder.encode(`data: ${JSON.stringify({ type: "done" })}\n\n`)
        );
      } catch (err) {
        const msg = err instanceof Error ? err.message : "Unknown error";
        controller.enqueue(
          encoder.encode(`data: ${JSON.stringify({ type: "error", message: msg })}\n\n`)
        );
      } finally {
        controller.close();
      }
    },
  });

  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    },
  });
}
