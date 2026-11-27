import { useState, type FormEvent } from 'react'
import { ShieldCheck } from 'lucide-react'
import { useAuth } from '../auth'
import { Button, ErrorBanner, Field, inputClass } from '../components/ui'

const users = ['operator', 'approver', 'viewer', 'auditor', 'admin']

export function Login() {
  const { login } = useAuth()
  const [username, setUsername] = useState('operator')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(username, password)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-full items-center justify-center p-6">
      <form onSubmit={submit} className="w-full max-w-sm space-y-4 rounded-lg border border-ink-700 bg-ink-850 p-6">
        <div className="flex items-center gap-2">
          <ShieldCheck className="h-7 w-7 text-teal-400" aria-hidden />
          <div>
            <h1 className="text-lg font-semibold text-slate-50">LedgerGuard</h1>
            <p className="text-xs text-slate-400">Simulated ledger. No real money, no real accounts.</p>
          </div>
        </div>
        <Field label="Demo user">
          <select className={inputClass} value={username} onChange={(e) => setUsername(e.target.value)}>
            {users.map((u) => (
              <option key={u}>{u}</option>
            ))}
          </select>
        </Field>
        <Field label="Password" hint="The LEDGERGUARD_DEMO_PASSWORD value from your .env file">
          <input type="password" className={inputClass} value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        </Field>
        <ErrorBanner error={error} />
        <Button type="submit" className="w-full" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
    </div>
  )
}
