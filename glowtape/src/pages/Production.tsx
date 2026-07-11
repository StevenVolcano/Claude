import { createContext, useContext, useEffect, useState } from 'react'
import { Link, NavLink, Navigate, Route, Routes, useParams } from 'react-router-dom'
import { pb } from '../lib/pb.ts'
import { useAuth } from '../lib/auth.tsx'
import type { MemberRecord, ProductionRecord } from '../lib/types.ts'
import ScheduleTab from './ScheduleTab.tsx'
import MessagesTab from './MessagesTab.tsx'
import PeopleTab from './PeopleTab.tsx'
import AdminTab from './AdminTab.tsx'

export interface ProductionContextValue {
  production: ProductionRecord
  members: MemberRecord[]
  myMember: MemberRecord | null
  isManager: boolean
  reload: () => Promise<void>
}

const ProductionContext = createContext<ProductionContextValue | null>(null)

export function useProduction(): ProductionContextValue {
  const ctx = useContext(ProductionContext)
  if (!ctx) throw new Error('useProduction outside provider')
  return ctx
}

export default function Production() {
  const { id } = useParams()
  const { user } = useAuth()
  const [production, setProduction] = useState<ProductionRecord | null>(null)
  const [members, setMembers] = useState<MemberRecord[]>([])
  const [failed, setFailed] = useState(false)

  async function reload() {
    if (!id) return
    const [p, m] = await Promise.all([
      pb.collection('productions').getOne<ProductionRecord>(id),
      pb.collection('members').getFullList<MemberRecord>({
        filter: pb.filter('production = {:id}', { id }),
        expand: 'user',
        sort: 'created',
      }),
    ])
    setProduction(p)
    setMembers(m)
  }

  useEffect(() => {
    reload().catch(() => setFailed(true))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  if (failed) {
    return (
      <main className="page">
        <p className="error">Couldn't open this production.</p>
        <Link to="/">Back to your productions</Link>
      </main>
    )
  }
  if (!production) {
    return (
      <main className="page">
        <p>Loading…</p>
      </main>
    )
  }

  const myMember = members.find((m) => m.user === user?.id) ?? null
  const isManager = !!user && production.managers.includes(user.id)

  return (
    <ProductionContext.Provider value={{ production, members, myMember, isManager, reload }}>
      <main className="page production">
        <header className="topbar">
          <Link to="/" className="link">
            ← All productions
          </Link>
          <span className="brand-small">Glow Tape</span>
        </header>
        <h1>{production.title}</h1>

        <nav className="tabs" aria-label="Production sections">
          <NavLink to="schedule">Schedule</NavLink>
          <NavLink to="messages">Messages</NavLink>
          <NavLink to="people">People</NavLink>
          {isManager && <NavLink to="admin">Manage</NavLink>}
        </nav>

        <Routes>
          <Route path="schedule" element={<ScheduleTab />} />
          <Route path="messages" element={<MessagesTab />} />
          <Route path="people" element={<PeopleTab />} />
          {isManager && <Route path="admin" element={<AdminTab />} />}
          <Route path="*" element={<Navigate to="schedule" replace />} />
        </Routes>
      </main>
    </ProductionContext.Provider>
  )
}
