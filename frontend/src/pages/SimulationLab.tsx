import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { CheckCircle2, Play, XCircle } from 'lucide-react'
import { api } from '../api'
import { Badge, Button, Card, ErrorBanner, Field, PageHeader, StatusBadge, Table, inputClass } from '../components/ui'
import { money } from '../format'

interface Attempt {
  index: number
  amount: string
  outcome: string
  reference: string | null
  reason: string | null
  replayed: boolean
  latencyMs: number
}

interface WithdrawalResult {
  accountId: string
  accountNumber: string
  startingBalance: string
  attempts: Attempt[]
  posted: number
  rejected: number
  errors: number
  finalBalance: string
  ledgerDerivedBalance: string
  ledgerHealthy: boolean
}

interface DuplicateResult {
  requests: number
  executed: number
  replayed: number
  errors: number
  distinctTransactionIds: number
  journalEntriesForTransaction: number
  amount: string
  sourceFinalBalance: string
  destinationFinalBalance: string
  idempotencyKey: string
  sample: Attempt[]
}

interface RollbackResult {
  injectedFailure: string
  requestFailed: boolean
  errorMessage: string | null
  sourceBalanceBefore: string
  sourceBalanceAfter: string
  destinationBalanceBefore: string
  destinationBalanceAfter: string
  sourceEntriesBefore: number
  sourceEntriesAfter: number
  destinationEntriesAfter: number
  ledgerHealthy: boolean
  followUpTransferStatus: string
}

interface EventStatus {
  id: string
  eventType: string
  attempts: number
  published: boolean
  lastError: string | null
}

interface EventResult {
  transactionReference: string
  transactionStatus: string
  timeline: { step: string; events: EventStatus[] }[]
  eventuallyPublished: boolean
  duplicateDeliveriesIgnored: number
}

function Check({ ok, children }: { ok: boolean; children: ReactNode }) {
  return (
    <li className="flex items-start gap-2 text-sm">
      {ok ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-400" aria-label="pass" /> : <XCircle className="mt-0.5 h-4 w-4 shrink-0 text-rose-400" aria-label="fail" />}
      <span>{children}</span>
    </li>
  )
}

function useRunner<T>() {
  const [result, setResult] = useState<T | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  async function run(fn: () => Promise<T>) {
    setBusy(true)
    setError(null)
    try {
      setResult(await fn())
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }
  return { result, error, busy, run }
}

export function SimulationLab() {
  return (
    <>
      <PageHeader
        title="Simulation Lab"
        description="Each run creates fresh, isolated simulation accounts and executes real concurrent requests against PostgreSQL. Results show what actually happened; nothing is scripted and no request is assumed to win."
      />
      <div className="grid gap-4 2xl:grid-cols-2">
        <ConcurrentWithdrawals />
        <DuplicateSubmissions />
        <Rollback />
        <EventRecovery />
      </div>
    </>
  )
}

function ConcurrentWithdrawals() {
  const [start, setStart] = useState('1000.00')
  const [amounts, setAmounts] = useState('800.00, 600.00')
  const r = useRunner<WithdrawalResult>()
  const list = amounts.split(',').map((s) => s.trim()).filter(Boolean)
  const res = r.result

  return (
    <Card title="1 · Concurrent withdrawals (double-spend)">
      <p className="mb-3 text-sm text-slate-400">All withdrawals are released through one gate at the same instant. Row locks (SELECT … FOR UPDATE) serialize them, so funds are checked against the latest committed balance.</p>
      <div className="flex flex-wrap items-end gap-3">
        <Field label="Starting balance">
          <input className={`${inputClass} w-32!`} value={start} onChange={(e) => setStart(e.target.value)} />
        </Field>
        <div className="min-w-[14rem] flex-1">
          <Field label="Withdrawal amounts (comma separated, up to 200)">
            <input className={inputClass} value={amounts} onChange={(e) => setAmounts(e.target.value)} />
          </Field>
        </div>
        <Button disabled={r.busy} onClick={() => r.run(() => api.simulation<WithdrawalResult>('concurrent-withdrawals', { startingBalance: start, withdrawalAmounts: list }))}>
          <Play className="h-4 w-4" aria-hidden /> {r.busy ? 'Running…' : 'Run'}
        </Button>
      </div>
      <div className="mt-3">
        <ErrorBanner error={r.error} />
      </div>
      {res && (
        <div className="mt-4 space-y-3">
          <ul className="space-y-1">
            <Check ok={Number(res.finalBalance) >= 0}>Final balance {money(res.finalBalance)} is not negative</Check>
            <Check ok={res.finalBalance === res.ledgerDerivedBalance}>Projection equals ledger-derived balance ({money(res.ledgerDerivedBalance)})</Check>
            <Check ok={res.ledgerHealthy}>Whole-ledger integrity check passes</Check>
            <Check ok={res.errors === 0}>
              {res.posted} posted, {res.rejected} rejected, {res.errors} technical errors
            </Check>
          </ul>
          <AttemptTable attempts={res.attempts} />
          <p className="text-xs text-slate-500">
            Account{' '}
            <Link className="font-mono text-teal-300" to={`/accounts/${res.accountId}`}>
              {res.accountNumber}
            </Link>
          </p>
        </div>
      )}
    </Card>
  )
}

function DuplicateSubmissions() {
  const [amount, setAmount] = useState('250.00')
  const [count, setCount] = useState(50)
  const r = useRunner<DuplicateResult>()
  const res = r.result

  return (
    <Card title="2 · Duplicate submissions (idempotency)">
      <p className="mb-3 text-sm text-slate-400">One transfer is submitted N times concurrently with the same Idempotency-Key, like a client retrying after a timeout. The key is claimed with a database unique constraint in the same transaction as the transfer.</p>
      <div className="flex flex-wrap items-end gap-3">
        <Field label="Transfer amount">
          <input className={`${inputClass} w-32!`} value={amount} onChange={(e) => setAmount(e.target.value)} />
        </Field>
        <Field label="Concurrent requests">
          <input type="number" min={2} max={200} className={`${inputClass} w-28!`} value={count} onChange={(e) => setCount(Number(e.target.value))} />
        </Field>
        <Button disabled={r.busy} onClick={() => r.run(() => api.simulation<DuplicateResult>('duplicate-submissions', { amount, concurrentRequests: count }))}>
          <Play className="h-4 w-4" aria-hidden /> {r.busy ? 'Running…' : 'Run'}
        </Button>
      </div>
      <div className="mt-3">
        <ErrorBanner error={r.error} />
      </div>
      {res && (
        <div className="mt-4 space-y-3">
          <ul className="space-y-1">
            <Check ok={res.executed === 1}>
              {res.requests} requests → executed {res.executed}, replayed {res.replayed}, errors {res.errors}
            </Check>
            <Check ok={res.distinctTransactionIds === 1}>All responses refer to {res.distinctTransactionIds} transaction</Check>
            <Check ok={res.journalEntriesForTransaction === 2}>{res.journalEntriesForTransaction} journal entries (one debit, one credit)</Check>
            <Check ok={res.destinationFinalBalance === res.amount}>Destination received {money(res.destinationFinalBalance)} exactly once</Check>
          </ul>
          <AttemptTable attempts={res.sample} showReplay />
          <p className="font-mono text-xs text-slate-500">Idempotency-Key {res.idempotencyKey}</p>
        </div>
      )}
    </Card>
  )
}

function Rollback() {
  const [amount, setAmount] = useState('300.00')
  const r = useRunner<RollbackResult>()
  const res = r.result

  return (
    <Card title="3 · Failure mid-posting (atomicity)">
      <p className="mb-3 text-sm text-slate-400">A transfer is forced to fail after its first journal entry is written. The whole database transaction rolls back: no partial entries, no balance change. Then the same transfer is retried and succeeds.</p>
      <div className="flex flex-wrap items-end gap-3">
        <Field label="Transfer amount">
          <input className={`${inputClass} w-32!`} value={amount} onChange={(e) => setAmount(e.target.value)} />
        </Field>
        <Button disabled={r.busy} onClick={() => r.run(() => api.simulation<RollbackResult>('rollback', { amount }))}>
          <Play className="h-4 w-4" aria-hidden /> {r.busy ? 'Running…' : 'Run'}
        </Button>
      </div>
      <div className="mt-3">
        <ErrorBanner error={r.error} />
      </div>
      {res && (
        <ul className="mt-4 space-y-1">
          <Check ok={res.requestFailed}>Injected failure: {res.errorMessage}</Check>
          <Check ok={res.sourceBalanceBefore === res.sourceBalanceAfter}>
            Source balance unchanged: {money(res.sourceBalanceBefore)} → {money(res.sourceBalanceAfter)}
          </Check>
          <Check ok={res.destinationBalanceAfter === res.destinationBalanceBefore}>
            Destination balance unchanged: {money(res.destinationBalanceAfter)}
          </Check>
          <Check ok={res.sourceEntriesBefore === res.sourceEntriesAfter && res.destinationEntriesAfter === 0}>
            No partial journal entries committed ({res.sourceEntriesAfter} source, {res.destinationEntriesAfter} destination)
          </Check>
          <Check ok={res.ledgerHealthy}>Whole-ledger integrity check passes</Check>
          <Check ok={res.followUpTransferStatus === 'POSTED'}>System still operational: follow-up transfer {res.followUpTransferStatus}</Check>
        </ul>
      )}
    </Card>
  )
}

function EventRecovery() {
  const [amount, setAmount] = useState('100.00')
  const r = useRunner<EventResult>()
  const res = r.result

  return (
    <Card title="4 · Broker outage (transactional outbox)">
      <p className="mb-3 text-sm text-slate-400">The simulated message broker is taken down, then a deposit is posted. The financial commit succeeds anyway; its event waits in the outbox table and is delivered once the broker recovers.</p>
      <div className="flex flex-wrap items-end gap-3">
        <Field label="Deposit amount">
          <input className={`${inputClass} w-32!`} value={amount} onChange={(e) => setAmount(e.target.value)} />
        </Field>
        <Button disabled={r.busy} onClick={() => r.run(() => api.simulation<EventResult>('event-recovery', { amount }))}>
          <Play className="h-4 w-4" aria-hidden /> {r.busy ? 'Running…' : 'Run'}
        </Button>
      </div>
      <div className="mt-3">
        <ErrorBanner error={r.error} />
      </div>
      {res && (
        <div className="mt-4 space-y-3">
          <ul className="space-y-1">
            <Check ok={res.transactionStatus === 'POSTED'}>Deposit {res.transactionReference} committed while the broker was down</Check>
            <Check ok={res.eventuallyPublished}>Event eventually published after recovery</Check>
          </ul>
          <Table head={['Step', 'Event', 'Attempts', 'Published', 'Last error']}>
            {res.timeline.flatMap((s) =>
              s.events.map((e) => (
                <tr key={s.step + e.id}>
                  <td className="px-3 py-2 text-xs">{s.step}</td>
                  <td className="px-3 py-2 font-mono text-xs">{e.eventType}</td>
                  <td className="num px-3 py-2">{e.attempts}</td>
                  <td className="px-3 py-2">{e.published ? <Badge tone="green">yes</Badge> : <Badge tone="amber">pending</Badge>}</td>
                  <td className="px-3 py-2 text-xs text-slate-400">{e.lastError ?? ''}</td>
                </tr>
              )),
            )}
          </Table>
        </div>
      )}
    </Card>
  )
}

function AttemptTable({ attempts, showReplay = false }: { attempts: Attempt[]; showReplay?: boolean }) {
  return (
    <Table head={['#', 'Amount', 'Outcome', 'Reference', showReplay ? 'Replayed' : 'Reason', 'Latency']}>
      {attempts.map((a) => (
        <tr key={a.index}>
          <td className="num px-3 py-1.5 text-slate-400">{a.index}</td>
          <td className="num px-3 py-1.5">{money(a.amount)}</td>
          <td className="px-3 py-1.5">{a.outcome === 'ERROR' ? <Badge tone="red">ERROR</Badge> : <StatusBadge status={a.outcome} />}</td>
          <td className="px-3 py-1.5 font-mono text-xs">{a.reference ?? '—'}</td>
          <td className="px-3 py-1.5 text-xs text-slate-400">{showReplay ? (a.replayed ? 'yes' : 'no (executed)') : (a.reason ?? '')}</td>
          <td className="num px-3 py-1.5 text-xs text-slate-400">{a.latencyMs} ms</td>
        </tr>
      ))}
    </Table>
  )
}

