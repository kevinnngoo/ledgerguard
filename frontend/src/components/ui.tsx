import { type ButtonHTMLAttributes, type ReactNode } from 'react'
import { AlertTriangle } from 'lucide-react'
import { ApiError } from '../api'

export function Card({ title, actions, children, className = '' }: { title?: string; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`rounded-lg border border-ink-700 bg-ink-850 ${className}`}>
      {(title || actions) && (
        <header className="flex items-center justify-between border-b border-ink-700 px-4 py-3">
          {title && <h2 className="text-sm font-semibold text-slate-100">{title}</h2>}
          {actions}
        </header>
      )}
      <div className="p-4">{children}</div>
    </section>
  )
}

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-xl font-semibold text-slate-50">{title}</h1>
        {description && <p className="mt-1 max-w-3xl text-sm text-slate-400">{description}</p>}
      </div>
      {actions}
    </div>
  )
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="rounded-lg border border-ink-700 bg-ink-850 px-4 py-3">
      <div className="text-xs font-medium uppercase tracking-wide text-slate-400">{label}</div>
      <div className="num mt-1 text-2xl font-semibold text-slate-50">{value}</div>
      {hint && <div className="mt-0.5 text-xs text-slate-500">{hint}</div>}
    </div>
  )
}

const tones: Record<string, string> = {
  green: 'bg-emerald-500/10 text-emerald-300 ring-emerald-500/30',
  red: 'bg-rose-500/10 text-rose-300 ring-rose-500/30',
  amber: 'bg-amber-500/10 text-amber-300 ring-amber-500/30',
  slate: 'bg-slate-500/10 text-slate-300 ring-slate-500/30',
  teal: 'bg-teal-500/10 text-teal-300 ring-teal-500/30',
}

export function Badge({ tone = 'slate', children }: { tone?: keyof typeof tones; children: ReactNode }) {
  return <span className={`inline-flex items-center rounded px-1.5 py-0.5 text-xs font-medium ring-1 ring-inset ${tones[tone]}`}>{children}</span>
}

export function StatusBadge({ status }: { status: string }) {
  const tone = ({ POSTED: 'green', ACTIVE: 'green', REJECTED: 'red', CLOSED: 'slate', REVERSED: 'amber', FROZEN: 'amber' } as Record<string, keyof typeof tones>)[status] ?? 'slate'
  return <Badge tone={tone}>{status}</Badge>
}




export function Button({ variant = 'primary', className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'danger' }) {
  const styles = {
    primary: 'bg-teal-500 text-ink-950 hover:bg-teal-400 disabled:bg-teal-500/40',
    secondary: 'border border-ink-600 bg-ink-800 text-slate-200 hover:bg-ink-700 disabled:opacity-50',
    danger: 'border border-rose-500/40 bg-rose-500/10 text-rose-200 hover:bg-rose-500/20 disabled:opacity-50',
  }[variant]
  return <button className={`inline-flex items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium transition disabled:cursor-not-allowed ${styles} ${className}`} {...props} />
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium text-slate-400">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs text-slate-500">{hint}</span>}
    </label>
  )
}

export const inputClass = 'w-full rounded-md border border-ink-600 bg-ink-900 px-2.5 py-1.5 text-sm text-slate-100 placeholder:text-slate-500 focus:border-teal-400 focus:outline-none'

export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null
  const problem = error instanceof ApiError ? error.problem : null
  const message = error instanceof Error ? error.message : String(error)
  return (
    <div role="alert" className="flex gap-2 rounded-md border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-sm text-rose-200">
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      <div>
        <div>{message}</div>
        {problem?.fieldErrors && (
          <ul className="mt-1 list-disc pl-4 text-xs">
            {Object.entries(problem.fieldErrors).map(([field, msg]) => (
              <li key={field}>
                {field}: {msg}
              </li>
            ))}
          </ul>
        )}
        {(problem?.code || problem?.correlationId) && (
          <div className="mt-1 font-mono text-xs text-rose-300/80">
            {problem.code} {problem.correlationId && `· correlation ${problem.correlationId}`}
          </div>
        )}
      </div>
    </div>
  )
}

export function Table({ head, children, empty }: { head: string[]; children: ReactNode; empty?: boolean }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead>
          <tr className="border-b border-ink-700 text-xs uppercase tracking-wide text-slate-400">
            {head.map((h) => (
              <th key={h} scope="col" className="px-3 py-2 font-medium">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-ink-700/60">{children}</tbody>
      </table>
      {empty && <p className="px-3 py-6 text-center text-sm text-slate-500">No records.</p>}
    </div>
  )
}

export function Pager({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (p: number) => void }) {
  if (totalPages <= 1) return null
  return (
    <div className="mt-3 flex items-center justify-end gap-2 text-sm text-slate-400">
      <Button variant="secondary" disabled={page === 0} onClick={() => onChange(page - 1)}>
        Previous
      </Button>
      <span className="num">
        Page {page + 1} of {totalPages}
      </span>
      <Button variant="secondary" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
        Next
      </Button>
    </div>
  )
}
