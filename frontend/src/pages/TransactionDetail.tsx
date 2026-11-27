import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, newIdempotencyKey } from '../api'
import { useAuth } from '../auth'
import { Badge, Button, Card, ErrorBanner, PageHeader, StatusBadge, Table, inputClass } from '../components/ui'
import { dateTime, money, shortId } from '../format'
import { useLoad } from '../useLoad'

export function TransactionDetail() {
  const { id = '' } = useParams()
  const { hasRole, me } = useAuth()
  const detail = useLoad(() => api.transaction(id), [id])
  const [reason, setReason] = useState('')
  const [key] = useState(newIdempotencyKey)
  const [error, setError] = useState<unknown>(null)

  async function reverse() {
    setError(null)
    try {
      await api.reverse(key, id, reason)
      detail.reload()
    } catch (e) {
      setError(e)
    }
  }

  const d = detail.data
  const t = d?.transaction
  const debits = t?.entries.filter((e) => e.direction === 'DEBIT') ?? []
  const credits = t?.entries.filter((e) => e.direction === 'CREDIT') ?? []
  const sum = (xs: { amount: string }[]) => xs.reduce((acc, e) => acc + Math.round(Number(e.amount) * 100), 0)

  return (
    <>
      <PageHeader title={t ? `${t.reference} · ${t.type}` : 'Transaction'} actions={t && <StatusBadge status={t.status} />} />
      <ErrorBanner error={detail.error} />
      {t && d && (
        <div className="grid gap-4 lg:grid-cols-3">
          <Card title="Details" className="lg:col-span-1">
            <dl className="space-y-2 text-sm">
              {[
                ['Amount', <span key="amount" className="num">{money(t.amount, t.currency)}</span>],
                ['From', t.sourceAccountId ? <Link key="from" className="font-mono text-teal-300" to={`/accounts/${t.sourceAccountId}`}>{shortId(t.sourceAccountId)}</Link> : '—'],
                ['To', t.destinationAccountId ? <Link key="to" className="font-mono text-teal-300" to={`/accounts/${t.destinationAccountId}`}>{shortId(t.destinationAccountId)}</Link> : '—'],
                ['Description', t.description ?? '—'],
                ['Initiated by', t.initiatedBy],
                ['Created', dateTime(t.createdAt)],
                ['Posted', dateTime(t.postedAt)],
                ['Correlation ID', <span key="corr" className="font-mono text-xs">{t.correlationId ?? '—'}</span>],
              ].map(([label, value]) => (
                <div key={String(label)} className="flex justify-between gap-4">
                  <dt className="text-slate-400">{label}</dt>
                  <dd className="text-right">{value}</dd>
                </div>
              ))}
            </dl>
            {t.rejectionCode && (
              <div className="mt-3 rounded-md border border-rose-500/40 bg-rose-500/10 p-2 text-sm text-rose-200">
                <div className="font-mono text-xs">{t.rejectionCode}</div>
                {t.rejectionReason}
              </div>
            )}
            {t.reversesTransactionId && (
              <p className="mt-3 text-sm">
                Reverses{' '}
                <Link className="font-mono text-teal-300" to={`/transactions/${t.reversesTransactionId}`}>
                  {shortId(t.reversesTransactionId)}
                </Link>
              </p>
            )}
            {d.reversedByTransactionId && (
              <p className="mt-3 text-sm">
                Reversed by{' '}
                <Link className="font-mono text-teal-300" to={`/transactions/${d.reversedByTransactionId}`}>
                  {d.reversedByReference}
                </Link>
              </p>
            )}
          </Card>
          <Card
            title="Journal entries"
            className="lg:col-span-2"
            actions={t.entries.length > 0 && (sum(debits) === sum(credits) ? <Badge tone="green">Balanced</Badge> : <Badge tone="red">Unbalanced</Badge>)}
          >
            <Table head={['Entry', 'Account', 'Debit', 'Credit']} empty={t.entries.length === 0}>
              {t.entries.map((e) => (
                <tr key={e.id}>
                  <td className="num px-3 py-2 font-mono text-xs text-slate-400">#{e.id}</td>
                  <td className="px-3 py-2 font-mono text-xs">
                    <Link className="text-teal-300 hover:underline" to={`/accounts/${e.accountId}`}>
                      {shortId(e.accountId)}
                    </Link>
                  </td>
                  <td className="num px-3 py-2">{e.direction === 'DEBIT' ? money(e.amount) : ''}</td>
                  <td className="num px-3 py-2">{e.direction === 'CREDIT' ? money(e.amount) : ''}</td>
                </tr>
              ))}
              {t.entries.length > 0 && (
                <tr className="font-semibold">
                  <td className="px-3 py-2" colSpan={2}>
                    Total
                  </td>
                  <td className="num px-3 py-2">{money((sum(debits) / 100).toFixed(2))}</td>
                  <td className="num px-3 py-2">{money((sum(credits) / 100).toFixed(2))}</td>
                </tr>
              )}
            </Table>
            {t.entries.length === 0 && <p className="text-sm text-slate-500">Rejected transactions have no ledger effect.</p>}
          </Card>
          {hasRole('APPROVER') && t.status === 'POSTED' && t.type !== 'REVERSAL' && (
            <Card title="Reverse transaction" className="lg:col-span-3">
              {t.initiatedBy === me?.username ? (
                <p className="text-sm text-slate-400">Separation of duties: you initiated this transaction, so another approver must reverse it.</p>
              ) : (
                <div className="flex flex-wrap items-center gap-2">
                  <input className={`${inputClass} max-w-md`} placeholder="Reason (required, audited)" value={reason} onChange={(e) => setReason(e.target.value)} aria-label="Reversal reason" />
                  <Button variant="danger" disabled={!reason.trim()} onClick={reverse}>
                    Post reversal
                  </Button>
                </div>
              )}
              <div className="mt-3">
                <ErrorBanner error={error} />
              </div>
            </Card>
          )}
        </div>
      )}
    </>
  )
}
