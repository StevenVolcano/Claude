import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { createSessionToken } from "@/lib/auth";

const schema = z.object({ pin: z.string() });

export async function POST(req: NextRequest) {
  const body = await req.json().catch(() => ({}));
  const parsed = schema.safeParse(body);
  if (!parsed.success) return NextResponse.json({ error: "Invalid request" }, { status: 400 });

  if (parsed.data.pin !== process.env.PIN) {
    return NextResponse.json({ error: "Invalid PIN" }, { status: 401 });
  }

  const token = await createSessionToken();
  const res = NextResponse.json({ ok: true });
  res.cookies.set("session", token, { httpOnly: true, maxAge: 60 * 60 * 24 * 30, path: "/" });
  return res;
}
