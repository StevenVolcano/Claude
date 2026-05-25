import { baseUrl } from "@/lib/utils"
import { redirect } from "next/navigation"

export default async function Home() {
  const res = await fetch(`${baseUrl()}/api/onboarding`, {
    cache: "no-store",
  }).catch(() => null)

  if (res?.ok) {
    const data = await res.json()
    if (data.profile === null) {
      redirect("/onboarding")
    }
  }

  redirect("/dashboard")
}
