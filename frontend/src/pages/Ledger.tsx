import { useState } from 'react'
import { Link } from 'react-router-dom'
import { CheckCircle2, XCircle } from 'lucide-react'
import { api } from '../api'
import { Button, Card, ErrorBanner, PageHeader, Pager, Table } from '../components/ui'
import { dateTime, money, shortId } from '../format'
import { useLoad } from '../useLoad'

export function Ledger() {
  const [page, setPage] = useState(0)
  const entries = useLoad(() => api.ledgerEntries(page), [page])
  const integrity = useLoad(() => api.integrity(), [])
  const r = integrity.data

  const checks = r
    ? [
        ['Every journal transaction balances', r.unbalancedTransactions.length],
        ['Posted transactions all have entries', r.postedWithoutEntries.length],
        ['Balance projections match the ledger', r.projectionMismatches.length],
        ['No unauthorized negative balances', r.unauthorizedNegativeBalances.length],
        ['No duplicate idempotent effects', r.duplicateIdempotentEffects.length],
      ]
    : []

  return (
    <>
      <PageHeader title="Ledger & integrity" description="The journal is the authoritative record. Integrity checks recompute everything from it inside one consistent database snapshot." />
      <Card title="Integrity report" actions={<Button variant="secondary" onClick={integrity.reload}>Re-run checks</Button>}>
        <ErrorBanner error={integrity.error} />
        {r && (
          <div className="grid gap-4 md:grid-cols-2">
            <ul className="space-y-2 text-sm">
              {checks.map(([label, failures]) => (
                <li key={String(label)} className="flex items-center gap-2">
                  {failures === 0 ? <CheckCircle2 className="h-4 w-4 text-emerald-400" aria-label="pass" /> : <XCircle className="h-4 w-4 text-rose-400" aria-label="fail" />}
                  {label}
                  {failures !== 0 && <span className="text-rose-300">({failures})</span>}
                </li>
              ))}
            </ul>
            <dl className="num space-y-1 text-sm">
              <div className="flex justify-between"><dt className="text-slate-400">Journal transactions</dt><dd>{r.journalTransactions}</dd></div>
              <div className="flex justify-between"><dt className="text-slate-400">Journal entries</dt><dd>{r.journalEntries}</dd></div>
              <div className="flex justify-between"><dt className="text-slate-400">Total debits</dt><dd>{money(r.totalDebits)}</dd></div>
              <div className="flex justify-between"><dt className="text-slate-400">Total credits</dt><dd>{money(r.totalCredits)}</dd></div>
              <div className="flex justify-between"><dt className="text-slate-400">Checked</dt><dd>{dateTime(r.checkedAt)}</dd></div>
            </dl>
          </div>
        )}
      </Card>
      <Card title="Ledger explorer" className="mt-4">
        <ErrorBanner error={entries.error} />
        {entries.data && (
          <>
            <Table head={['Entry', 'Transaction', 'Account', 'Debit', 'Credit', 'Posted']} empty={entries.data.items.length === 0}>
              {entries.data.items.map((e) => (
                <tr key={e.id}>
                  <td className="num px-3 py-2 font-mono text-xs text-slate-400">#{e.id}</td>
                  <td className="px-3 py-2 font-mono text-xs">
                    <Link className="text-teal-300 hover:underline" to={`/transactions/${e.transactionId}`}>{shortId(e.transactionId)}</Link>
                  </td>
                  <td className="px-3 py-2 font-mono text-xs">
                    <Link className="text-teal-300 hover:underline" to={`/accounts/${e.accountId}`}>{shortId(e.accountId)}</Link>
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
