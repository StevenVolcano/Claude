import { baseUrl } from "@/lib/utils"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Separator } from "@/components/ui/separator"
import { ResetProgramButton } from "./ResetProgramButton"

async function getProfile() {
  const res = await fetch(`${baseUrl()}/api/onboarding`, {
    cache: "no-store",
  })
  if (!res.ok) return null
  const data = await res.json()
  return data.profile
}

export default async function SettingsPage() {
  const profile = await getProfile()

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-zinc-100">Settings</h1>
        <p className="text-sm text-zinc-500 mt-1">Manage your program and preferences</p>
      </div>

      {/* Program section */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base text-zinc-100">Program</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {profile ? (
            <>
              <div>
                <p className="text-xs text-zinc-500 uppercase tracking-wider mb-1">Current program</p>
                <p className="font-medium text-zinc-100">{profile.programTitle}</p>
              </div>
              <Separator />
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm text-zinc-300 font-medium">Reset my program</p>
                  <p className="text-xs text-zinc-500 mt-0.5">
                    Retake the quiz and get a new program recommendation
                  </p>
                </div>
                <ResetProgramButton />
              </div>
            </>
          ) : (
            <p className="text-sm text-zinc-400">No program set. Complete onboarding to get started.</p>
          )}
        </CardContent>
      </Card>

      {/* About section */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base text-zinc-100">About</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-sm text-zinc-400">App</span>
            <span className="text-sm text-zinc-200 font-medium">Relatio</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-sm text-zinc-400">Version</span>
            <span className="text-sm text-zinc-200">1.0.0</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-sm text-zinc-400">License</span>
            <span className="text-sm text-zinc-200">Personal use only</span>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
