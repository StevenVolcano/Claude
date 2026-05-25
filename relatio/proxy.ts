import { NextRequest, NextResponse } from "next/server";
import { jwtVerify } from "jose";

const SECRET = new TextEncoder().encode(
  process.env.SESSION_SECRET || "relatio-dev-secret-change-in-prod"
);

const PUBLIC_PATHS = ["/pin", "/api/auth/verify-pin", "/api/auth/logout"];

export async function proxy(request: NextRequest) {
  if (process.env.REQUIRE_PIN !== "true") return NextResponse.next();

  const { pathname } = request.nextUrl;
  if (PUBLIC_PATHS.some((p) => pathname.startsWith(p))) return NextResponse.next();
  if (pathname.startsWith("/_next") || pathname.startsWith("/favicon")) return NextResponse.next();

  const token = request.cookies.get("session")?.value;
  if (token) {
    try {
      await jwtVerify(token, SECRET);
      return NextResponse.next();
    } catch {}
  }

  return NextResponse.redirect(new URL("/pin", request.url));
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
