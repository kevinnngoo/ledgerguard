import { NavLink, Outlet } from 'react-router-dom'
import { Activity, BookOpen, FlaskConical, LayoutDashboard, LogOut, ScrollText, ShieldCheck, Wallet } from 'lucide-react'
import { useAuth } from '../auth'

// Only implemented areas are listed. Reconciliation, exceptions, risk rules and settings are on
// the roadmap and deliberately absent rather than shown as non-functional links.
const nav = [
  { to: '/', label: 'Overview', icon: LayoutDashboard, role: 'VIEWER' },
  { to: '/accounts', label: 'Accounts', icon: Wallet, role: 'VIEWER' },
  { to: '/transactions', label: 'Transactions', icon: Activity, role: 'VIEWER' },
  { to: '/ledger', label: 'Ledger & integrity', icon: BookOpen, role: 'VIEWER' },
  { to: '/simulation', label: 'Simulation Lab', icon: FlaskConical, role: 'OPERATOR' },
  { to: '/audit', label: 'Audit trail', icon: ScrollText, role: 'AUDITOR' },
]

export function Layout() {
  const { me, logout, hasRole } = useAuth()
  return (
    <div className="flex min-h-full">
      <aside className="flex w-60 shrink-0 flex-col border-r border-ink-700 bg-ink-950">
        <div className="flex items-center gap-2 px-5 py-5">
          <ShieldCheck className="h-6 w-6 text-teal-400" aria-hidden />
          <div>
            <div className="text-sm font-semibold text-slate-50">LedgerGuard</div>
            <div className="text-[11px] text-slate-500">Financial integrity by design</div>
          </div>
        </div>
        <nav className="flex-1 space-y-0.5 px-3" aria-label="Primary">
          {nav
            .filter((n) => hasRole(n.role) || (n.role === 'AUDITOR' && hasRole('ADMIN')))
            .map(({ to, label, icon: Icon }) => (
              <NavLink
                key={to}
                to={to}
                end={to === '/'}
                className={({ isActive }) =>
                  `flex items-center gap-2.5 rounded-md px-2.5 py-2 text-sm ${isActive ? 'bg-teal-500/10 text-teal-300' : 'text-slate-400 hover:bg-ink-800 hover:text-slate-200'}`
                }
              >
                <Icon className="h-4 w-4" aria-hidden />
                {label}
              </NavLink>
            ))}
        </nav>
        <div className="border-t border-ink-700 px-4 py-3 text-xs">
          <div className="mb-1 flex items-center gap-1.5 text-amber-300/90">
            <span className="h-1.5 w-1.5 rounded-full bg-amber-400" aria-hidden /> Simulated data only
          </div>
          <div className="text-slate-300">{me?.username}</div>
          <div className="text-slate-500">{me?.roles.join(' · ')}</div>
          <button onClick={logout} className="mt-2 inline-flex items-center gap-1 text-slate-400 hover:text-slate-200">
            <LogOut className="h-3.5 w-3.5" aria-hidden /> Sign out
          </button>
        </div>
      </aside>
      <main className="min-w-0 flex-1 px-8 py-7">
        <Outlet />
      </main>
    </div>
  )
}
