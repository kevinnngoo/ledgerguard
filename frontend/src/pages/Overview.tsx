import { Link } from 'react-router-dom'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api } from '../api'
import { Card, ErrorBanner, PageHeader, Stat, StatusBadge, Table } from '../components/ui'
import { dateTime, money } from '../format'
import { useLoad } from '../useLoad'

export function Overview() {
  const overview = useLoad(() => api.overview(), [])
  const integrity = useLoad(() => api.integrity(), [])
  const o = overview.data
  const i = integrity.data

  return (
    <>
      <PageHeader title="Operations overview" description="All figures are calculated from the database when this page loads. Every record here is simulated." />
      <ErrorBanner error={overview.error ?? integrity.error} />
      {o && (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
            <Stat label="Customer accounts" value={o.customerAccounts} hint={`${o.simulationAccounts} simulation accounts`} />
            <Stat label="Posted value" value={money(o.postedValue)} hint="Deposits, withdrawals, transfers" />
            <Stat label="Posted" value={o.postedTransactions} hint={`${o.reversedTransactions} reversed`} />
            <Stat label="Rejected" value={o.rejectedTransactions} hint="Business rejections" />
            <Stat
              label="Ledger integrity"
              value={i ? (i.healthy ? <span className="text-emerald-300">Healthy</span> : <span className="text-rose-300">Issues</span>) : '…'}
              hint={i ? `${i.journalEntries} entries verified` : undefined}
            />
          </div>

          <div className="mt-4 grid gap-4 xl:grid-cols-3">
            <Card title="Transactions per day (last 14 days)" className="xl:col-span-2">
              <div className="h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={o.last14Days.map((d) => ({ ...d, day: d.day.slice(5) }))}>
                    <CartesianGrid stroke="#1e293b" vertical={false} />
                    <XAxis dataKey="day" stroke="#64748b" fontSize={12} tickLine={false} />
                    <YAxis stroke="#64748b" fontSize={12} allowDecimals={false} tickLine={false} axisLine={false} />
                    <Tooltip contentStyle={{ background: '#0f172a', border: '1px solid #1e293b', fontSize: 12 }} cursor={{ fill: '#1e293b55' }} />
                    <Legend wrapperStyle={{ fontSize: 12 }} />
                    <Bar dataKey="posted" name="Posted" stackId="a" fill="#2dd4bf" />
                    <Bar dataKey="rejected" name="Rejected" stackId="a" fill="#fb7185" />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </Card>
            <Card title="System">
              <dl className="space-y-3 text-sm">
                <div className="flex justify-between">
                  <dt className="text-slate-400">Pending outbox events</dt>
                  <dd className="num">{o.pendingOutboxEvents}</dd>
                </div>
                <div className="flex justify-between">
                  <dt className="text-slate-400">Total debits</dt>
                  <dd className="num">{i ? money(i.totalDebits) : '…'}</dd>
                </div>
                <div className="flex justify-between">
                  <dt className="text-slate-400">Total credits</dt>
                  <dd className="num">{i ? money(i.totalCredits) : '…'}</dd>
                </div>
                <div className="flex justify-between">
                  <dt className="text-slate-400">Integrity checked</dt>
                  <dd>{i ? dateTime(i.checkedAt) : '…'}</dd>
                </div>
              </dl>
              <Link to="/ledger" className="mt-4 inline-block text-sm text-teal-300 hover:underline">
                Open integrity report →
              </Link>
            </Card>
          </div>

          <Card title="Recent transactions" className="mt-4">
            <Table head={['Reference', 'Type', 'Amount', 'Status', 'Initiated by', 'Created']} empty={o.recentTransactions.length === 0}>
              {o.recentTransactions.map((t) => (
                <tr key={t.id}>
                  <td className="px-3 py-2 font-mono text-xs">
                    <Link to={`/transactions/${t.id}`} className="text-teal-300 hover:underline">
                      {t.reference}
                    </Link>
                  </td>
                  <td className="px-3 py-2">{t.type}</td>
                  <td className="num px-3 py-2">{money(t.amount)}</td>
                  <td className="px-3 py-2">
                    <StatusBadge status={t.status} />
                  </td>
                  <td className="px-3 py-2 text-slate-400">{t.initiatedBy}</td>
                  <td className="px-3 py-2 text-slate-400">{dateTime(t.createdAt)}</td>
                </tr>
              ))}
            </Table>
          </Card>
        </>
      )}
    </>
  )
}
