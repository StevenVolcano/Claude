export type MemberRole = 'director' | 'asst_director' | 'stage_manager' | 'performer' | 'crew'

export const ROLE_LABELS: Record<MemberRole, string> = {
  director: 'Director',
  asst_director: 'Assistant Director',
  stage_manager: 'Stage Manager',
  performer: 'Performer',
  crew: 'Crew',
}

export const MANAGER_ROLES: MemberRole[] = ['director', 'asst_director', 'stage_manager']

export interface UserRecord {
  id: string
  email: string
  name: string
  phone: string
  phoneVerified: boolean
  smsOptIn: boolean
}

export interface ProductionRecord {
  id: string
  org: string
  title: string
  status: 'planning' | 'rehearsal' | 'performance' | 'closed'
  joinCode: string
  managers: string[]
}

export interface MemberRecord {
  id: string
  production: string
  user: string
  role: MemberRole
  position: string
  expand?: { user?: UserRecord }
}

export interface EventRecord {
  id: string
  production: string
  title: string
  start: string
  end: string
  location: string
  notes: string
  called: string[]
  calledNote: string
}

export interface AckRecord {
  id: string
  event: string
  user: string
}

export interface ConflictRecord {
  id: string
  production: string
  user: string
  start: string
  end: string
  note: string
  expand?: { user?: UserRecord }
}

export interface ChannelRecord {
  id: string
  production: string
  name: string
  audience: 'all' | 'cast' | 'crew' | 'team'
}

export interface MessageRecord {
  id: string
  channel: string
  author: string
  text: string
  created: string
  expand?: { author?: UserRecord }
}

export interface AnnouncementRecord {
  id: string
  production: string
  author: string
  title: string
  body: string
  pinned: boolean
  created: string
  expand?: { author?: UserRecord }
}

export interface AnnouncementAckRecord {
  id: string
  announcement: string
  user: string
}

const dateFmt = new Intl.DateTimeFormat(undefined, {
  weekday: 'short',
  month: 'short',
  day: 'numeric',
})
const dayFmtUTC = new Intl.DateTimeFormat(undefined, {
  weekday: 'short',
  month: 'short',
  day: 'numeric',
  timeZone: 'UTC',
})
const timeFmt = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' })

// PocketBase returns "2026-07-15 19:00:00.000Z"; Safari refuses the space.
export function pbDate(value: string): Date {
  return new Date(value.replace(' ', 'T'))
}

export function formatWhen(start: string, end?: string): string {
  const s = pbDate(start)
  let out = `${dateFmt.format(s)}, ${timeFmt.format(s)}`
  if (end) {
    out += ` – ${timeFmt.format(pbDate(end))}`
  }
  return out
}

// For all-day values (conflicts): format in UTC so "2026-07-15 00:00:00Z"
// reads as July 15 everywhere, not July 14 in Pacific time.
export function formatDay(iso: string): string {
  return dayFmtUTC.format(pbDate(iso))
}
