import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, type AccountStatus } from '../api'
import { useAuth } from '../auth'
import { Badge, Button, Card, ErrorBanner, PageHeader, Pager, StatusBadge, Table, inputClass } from '../components/ui'
import { dateTime, money, shortId } from '../format'
import { useLoad } from '../useLoad'

export function AccountDetail() {
  const { id = '' } = useParams()
  const { hasRole } = useAuth()
  const [page, setPage] = useState(0)
  const account = useLoad(() => api.account(id), [id])
  const balance = useLoad(() => api.accountBalance(id), [id])
  const entries = useLoad(() => api.accountEntries(id, page), [id, page])
  const [reason, setReason] = useState('')
  const [statusError, setStatusError] = useState<unknown>(null)

  async function change(status: AccountStatus) {
    setStatusError(null)
    try {
      await api.changeStatus(id, status, reason || 'Operator action')
      setReason('')
      account.reload()
    } catch (e) {
      setStatusError(e)
    }
  }

  const a = account.data
  return (
    <>
      <PageHeader
        title={a ? `${a.name}` : 'Account'}
        description={a ? `${a.accountNumber} · ${a.type} (${a.normalBalance.toLowerCase()}-normal) · ${a.currency}` : undefined}
        actions={a && <StatusBadge status={a.status} />}
      />
      <ErrorBanner error={account.error ?? balance.error} />
      <div className="grid gap-4 lg:grid-cols-3">
        <Card title="Balance verification">
          {balance.data && (
            <dl className="space-y-2 text-sm">
              <div className="flex justify-between">
                <dt className="text-slate-400">Projected balance</dt>
                <dd className="num font-semibold">{money(balance.data.projectedBalance)}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-slate-400">Recomputed from ledger</dt>
                <dd className="num">{money(balance.data.ledgerBalance)}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-slate-400">Consistent</dt>
                <dd>{balance.data.consistent ? <Badge tone="green">yes</Badge> : <Badge tone="red">NO</Badge>}</dd>
              </div>
              <p className="pt-2 text-xs text-slate-500">Both values are read from one database snapshot. The ledger is authoritative; the projection exists for fast reads.</p>
            </dl>
          )}
        </Card>
        {hasRole('ADMIN') && a && !a.systemAccount && (
          <Card title="Lifecycle (admin)" className="lg:col-span-2">
            <div className="flex flex-wrap items-center gap-2">
              <input className={`${inputClass} max-w-sm`} placeholder="Reason (recorded in audit trail)" value={reason} onChange={(e) => setReason(e.target.value)} aria-label="Reason" />
              {a.status === 'ACTIVE' && <Button variant="secondary" onClick={() => change('FROZEN')}>Freeze</Button>}
              {a.status === 'FROZEN' && <Button variant="secondary" onClick={() => change('ACTIVE')}>Unfreeze</Button>}
              {a.status !== 'CLOSED' && <Button variant="danger" onClick={() => change('CLOSED')}>Close</Button>}
            </div>
            <p className="mt-2 text-xs text-slate-500">Closing requires a zero balance. Closed is terminal.</p>
            <div className="mt-3">
              <ErrorBanner error={statusError} />
            </div>
          </Card>
        )}
      </div>
      <Card title="Journal entries" className="mt-4">
        <ErrorBanner error={entries.error} />
        {entries.data && (
          <>
            <Table head={['Entry', 'Transaction', 'Debit', 'Credit', 'Posted']} empty={entries.data.items.length === 0}>
              {entries.data.items.map((e) => (
                <tr key={e.id}>
                  <td className="num px-3 py-2 font-mono text-xs text-slate-400">#{e.id}</td>
                  <td className="px-3 py-2 font-mono text-xs">
                    <Link className="text-teal-300 hover:underline" to={`/transactions/${e.transactionId}`}>
                      {shortId(e.transactionId)}
                    </Link>
                  </td>
                  <td className="num px-3 py-2">{e.direction === 'DEBIT' ? money(e.amount) : ''}</td>
                  <td className="num px-3 py-2">{e.direction === 'CREDIT' ? money(e.amount) : ''}</td>
                  <td className="px-3 py-2 text-slate-400">{dateTime(e.postedAt)}</td>
                </tr>
              ))}
            </Table>
            <Pager page={page} totalPages={entries.data.totalPages} onChange={setPage} />
          </>
        )}
      </Card>
    </>
  )
}
