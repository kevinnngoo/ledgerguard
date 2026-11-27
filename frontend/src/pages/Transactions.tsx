import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, newIdempotencyKey, type ApiResult, type TransactionResponse } from '../api'
import { useAuth } from '../auth'
import { Badge, Button, Card, ErrorBanner, Field, PageHeader, Pager, StatusBadge, Table, inputClass } from '../components/ui'
import { dateTime, money } from '../format'
import { useLoad } from '../useLoad'

export function Transactions() {
  const { hasRole } = useAuth()
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState('')
  const [type, setType] = useState('')
  const [reference, setReference] = useState('')
  const list = useLoad(() => api.transactions({ page, status, type, reference, size: 25 }), [page, status, type, reference])

  return (
    <>
      <PageHeader title="Transactions" description="Rejected transactions are recorded with their reason and have no journal entries. Reversals are new transactions; the original is never edited." />
      {hasRole('OPERATOR') && <SubmitTransaction onDone={list.reload} />}
      <Card>
        <div className="mb-3 flex flex-wrap gap-3">
          <input className={`${inputClass} max-w-[12rem]`} placeholder="Reference (TX-…)" value={reference} onChange={(e) => { setPage(0); setReference(e.target.value) }} aria-label="Reference" />
          <select className={`${inputClass} max-w-[10rem]`} value={status} onChange={(e) => { setPage(0); setStatus(e.target.value) }} aria-label="Status">
            <option value="">All statuses</option>
            <option>POSTED</option>
            <option>REJECTED</option>
            <option>REVERSED</option>
          </select>
          <select className={`${inputClass} max-w-[10rem]`} value={type} onChange={(e) => { setPage(0); setType(e.target.value) }} aria-label="Type">
            <option value="">All types</option>
            <option>DEPOSIT</option>
            <option>WITHDRAWAL</option>
            <option>TRANSFER</option>
            <option>REVERSAL</option>
          </select>
        </div>
        <ErrorBanner error={list.error} />
        {list.data && (
          <>
            <Table head={['Reference', 'Type', 'Amount', 'Status', 'Reason', 'By', 'Created']} empty={list.data.items.length === 0}>
              {list.data.items.map((t) => (
                <tr key={t.id}>
                  <td className="px-3 py-2 font-mono text-xs">
                    <Link className="text-teal-300 hover:underline" to={`/transactions/${t.id}`}>
                      {t.reference}
                    </Link>
                  </td>
                  <td className="px-3 py-2">{t.type}</td>
                  <td className="num px-3 py-2">{money(t.amount)}</td>
                  <td className="px-3 py-2">
                    <StatusBadge status={t.status} />
                  </td>
                  <td className="max-w-xs truncate px-3 py-2 text-xs text-slate-400" title={t.rejectionReason ?? ''}>
                    {t.rejectionCode ?? ''}
                  </td>
                  <td className="px-3 py-2 text-slate-400">{t.initiatedBy}</td>
                  <td className="px-3 py-2 text-slate-400">{dateTime(t.createdAt)}</td>
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

type Kind = 'transfer' | 'deposit' | 'withdrawal'

function SubmitTransaction({ onDone }: { onDone: () => void }) {
  const [kind, setKind] = useState<Kind>('transfer')
  const [source, setSource] = useState('')
  const [destination, setDestination] = useState('')
  const [amount, setAmount] = useState('')
  const [description, setDescription] = useState('')
  // One key per logical submission. It is kept after a network failure so "Retry" is safe.
  const [key, setKey] = useState(newIdempotencyKey)
  const [result, setResult] = useState<ApiResult<TransactionResponse> | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  const accounts = useLoad(() => api.accounts({ size: 100 }), [])

  const valid = /^\d+(\.\d{1,2})?$/.test(amount) && Number(amount) > 0
  const customerAccounts = accounts.data?.items.filter((a) => !a.systemAccount && a.status !== 'CLOSED') ?? []

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const desc = description || undefined
      const r =
        kind === 'transfer'
          ? await api.transfer(key, { sourceAccountId: source, destinationAccountId: destination, amount, description: desc })
          : kind === 'deposit'
            ? await api.deposit(key, { accountId: destination, amount, description: desc })
            : await api.withdraw(key, { accountId: source, amount, description: desc })
      setResult(r)
      setKey(newIdempotencyKey())
      onDone()
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  const select = (value: string, onChange: (v: string) => void, label: string) => (
    <Field label={label}>
      <select className={inputClass} value={value} onChange={(e) => onChange(e.target.value)} required>
        <option value="">Select account</option>
        {customerAccounts.map((a) => (
          <option key={a.id} value={a.id}>
            {a.accountNumber} · {a.name} ({money(a.balance)})
          </option>
        ))}
      </select>
    </Field>
  )

  return (
    <Card title="Submit a simulated transaction" className="mb-4">
      <form onSubmit={submit} className="grid gap-3 md:grid-cols-6">
        <Field label="Type">
          <select className={inputClass} value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
            <option value="transfer">Transfer</option>
            <option value="deposit">Deposit</option>
            <option value="withdrawal">Withdrawal</option>
          </select>
        </Field>
        {kind !== 'deposit' && <div className="md:col-span-2">{select(source, setSource, 'From account')}</div>}
        {kind !== 'withdrawal' && <div className="md:col-span-2">{select(destination, setDestination, 'To account')}</div>}
        <Field label="Amount (USD)" hint="Max 2 decimal places, never rounded">
          <input className={inputClass} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="250.00" required />
        </Field>
        <div className="md:col-span-4">
          <Field label="Description (optional)">
            <input className={inputClass} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={255} />
          </Field>
        </div>
        <div className="flex items-end gap-2 md:col-span-2">
          <Button type="submit" disabled={busy || !valid}>
            {error ? 'Retry with same key' : 'Submit'}
          </Button>
          <span className="truncate font-mono text-[11px] text-slate-500" title="Idempotency-Key">
            key {key.slice(0, 8)}…
          </span>
        </div>
      </form>
      <div className="mt-3 space-y-2">
        <ErrorBanner error={error} />
        {result && (
          <div className="rounded-md border border-ink-600 bg-ink-900 px-3 py-2 text-sm">
            <StatusBadge status={result.data.status} />{' '}
            <Link className="font-mono text-teal-300 hover:underline" to={`/transactions/${result.data.id}`}>
              {result.data.reference}
            </Link>{' '}
            {money(result.data.amount)} {result.replayed && <Badge tone="amber">replayed</Badge>}
            {result.data.rejectionReason && <div className="mt-1 text-rose-300">{result.data.rejectionReason}</div>}
          </div>
        )}
      </div>
    </Card>
  )
}
