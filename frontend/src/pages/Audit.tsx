import { useState } from 'react'
import { api } from '../api'
import { Card, ErrorBanner, PageHeader, Pager, Table, inputClass } from '../components/ui'
import { dateTime } from '../format'
import { useLoad } from '../useLoad'

export function Audit() {
  const [page, setPage] = useState(0)
  const [type, setType] = useState('')
  const list = useLoad(() => api.audit(page, type), [page, type])

  return (
    <>
      <PageHeader
        title="Audit trail"
        description="Append-only through the application: a database trigger rejects updates and deletes. This is not tamper-proof storage; a database superuser could still alter rows."
      />
      <Card>
        <select className={`${inputClass} mb-3 max-w-[12rem]`} value={type} onChange={(e) => { setPage(0); setType(e.target.value) }} aria-label="Resource type">
          <option value="">All resources</option>
          <option>Account</option>
          <option>Transaction</option>
          <option>Ledger</option>
        </select>
        <ErrorBanner error={list.error} />
        {list.data && (
          <>
            <Table head={['When', 'Actor', 'Action', 'Resource', 'Details', 'Correlation']} empty={list.data.items.length === 0}>
              {list.data.items.map((e) => (
                <tr key={e.id}>
                  <td className="whitespace-nowrap px-3 py-2 text-slate-400">{dateTime(e.createdAt)}</td>
                  <td className="px-3 py-2">{e.actor}</td>
                  <td className="px-3 py-2 font-mono text-xs">{e.action}</td>
                  <td className="px-3 py-2 text-xs text-slate-400">
                    {e.resourceType} {e.resourceId?.slice(0, 8)}
                  </td>
                  <td className="max-w-md truncate px-3 py-2 font-mono text-xs text-slate-400" title={e.details ?? ''}>
                    {e.details}
                  </td>
                  <td className="px-3 py-2 font-mono text-[11px] text-slate-500">{e.correlationId?.slice(0, 8)}</td>
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
