import { useProduction } from './Production.tsx'
import { MANAGER_ROLES, ROLE_LABELS } from '../lib/types.ts'

export default function PeopleTab() {
  const { members } = useProduction()

  const sorted = [...members].sort((a, b) => {
    const aTeam = MANAGER_ROLES.includes(a.role) ? 0 : 1
    const bTeam = MANAGER_ROLES.includes(b.role) ? 0 : 1
    if (aTeam !== bTeam) return aTeam - bTeam
    return (a.expand?.user?.name || '').localeCompare(b.expand?.user?.name || '')
  })

  return (
    <section>
      <div className="row space-between">
        <h2>Contact sheet</h2>
        <button className="no-print" onClick={() => window.print()}>
          🖨 Print
        </button>
      </div>
      <table className="contact-sheet">
        <thead>
          <tr>
            <th>Name</th>
            <th>Role</th>
            <th>Position / Character</th>
            <th>Email</th>
            <th>Phone</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((m) => (
            <tr key={m.id}>
              <td>{m.expand?.user?.name}</td>
              <td>{ROLE_LABELS[m.role]}</td>
              <td>{m.position}</td>
              <td>{m.expand?.user?.email}</td>
              <td>{m.expand?.user?.phone}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
