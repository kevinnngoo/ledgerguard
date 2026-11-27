import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { Badge, Button, Card, ErrorBanner, Field, PageHeader, Pager, StatusBadge, Table, inputClass } from '../components/ui'
import { money } from '../format'
import { useLoad } from '../useLoad'

export function Accounts() {
  const { hasRole } = useAuth()
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState('')
  const [search, setSearch] = useState('')
  const [includeSimulation, setIncludeSimulation] = useState(false)
  const list = useLoad(() => api.accounts({ page, status, search, includeSimulation, size: 20 }), [page, status, search, includeSimulation])

  return (
    <>
      <PageHeader title="Accounts" description="Customer cash accounts are liabilities on the institution's books: a credit increases them. Deposits and withdrawals post against the system clearing account." />
      {hasRole('OPERATOR') && <CreateAccount onCreated={list.reload} />}
      <Card>
        <div className="mb-3 flex flex-wrap gap-3">
          <input className={`${inputClass} max-w-xs`} placeholder="Search name or number" value={search} onChange={(e) => { setPage(0); setSearch(e.target.value) }} aria-label="Search accounts" />
          <select className={`${inputClass} max-w-[10rem]`} value={status} onChange={(e) => { setPage(0); setStatus(e.target.value) }} aria-label="Filter by status">
            <option value="">All statuses</option>
            <option>ACTIVE</option>
            <option>FROZEN</option>
            <option>CLOSED</option>
          </select>
          <label className="flex items-center gap-2 text-sm text-slate-400">
            <input type="checkbox" checked={includeSimulation} onChange={(e) => { setPage(0); setIncludeSimulation(e.target.checked) }} /> Include simulation accounts
          </label>
        </div>
        <ErrorBanner error={list.error} />
        {list.data && (
          <>
            <Table head={['Number', 'Name', 'Type', 'Status', 'Balance']} empty={list.data.items.length === 0}>
              {list.data.items.map((a) => (
                <tr key={a.id}>
                  <td className="px-3 py-2 font-mono text-xs">
                    <Link to={`/accounts/${a.id}`} className="text-teal-300 hover:underline">
                      {a.accountNumber}
                    </Link>
                  </td>
                  <td className="px-3 py-2">
                    {a.name} {a.systemAccount && <Badge tone="teal">system</Badge>} {a.simulation && <Badge>simulation</Badge>}
                  </td>
                  <td className="px-3 py-2 text-slate-400">
                    {a.type} <span className="text-xs">({a.normalBalance.toLowerCase()}-normal)</span>
                  </td>
                  <td className="px-3 py-2">
                    <StatusBadge status={a.status} />
                  </td>
                  <td className="num px-3 py-2 text-right">{money(a.balance, a.currency)}</td>
                </tr>
              ))}
            </Table>
            <Pager page={page} totalPages={list.data.totalPages} onChange={setPage} />
          </>
        )}
      </Card>
    </>
  )
}

function CreateAccount({ onCreated }: { onCreated: () => void }) {
  const [name, setName] = useState('')
  const [owner, setOwner] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.createAccount(name, owner)
      setName('')
      setOwner('')
      onCreated()
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title="New simulated account" className="mb-4">
      <form onSubmit={submit} className="flex flex-wrap items-end gap-3">
        <div className="min-w-[14rem] flex-1">
          <Field label="Account name">
            <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} required maxLength={120} />
          </Field>
        </div>
        <div className="min-w-[12rem]">
          <Field label="Owner reference (optional)">
            <input className={inputClass} value={owner} onChange={(e) => setOwner(e.target.value)} maxLength={64} />
          </Field>
        </div>
        <Button type="submit" disabled={busy || !name.trim()}>
          Create account
        </Button>
      </form>
      <div className="mt-3">
        <ErrorBanner error={error} />
      </div>
    </Card>
  )
}
