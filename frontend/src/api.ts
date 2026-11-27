// Typed client for the LedgerGuard REST API. Credentials live only in memory (never in storage).

export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED'
export type TxStatus = 'POSTED' | 'REJECTED' | 'REVERSED'
export type TxType = 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER' | 'REVERSAL'
export type Direction = 'DEBIT' | 'CREDIT'

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface Account {
  id: string
  accountNumber: string
  name: string
  type: string
  normalBalance: Direction
  currency: string
  status: AccountStatus
  systemAccount: boolean
  simulation: boolean
  ownerReference: string | null
  balance: string
  createdAt: string
  updatedAt: string
}

export interface BalanceView {
  accountId: string
  currency: string
  projectedBalance: string
  ledgerBalance: string
  consistent: boolean
  asOf: string
}

export interface JournalEntry {
  id: number
  transactionId: string
  accountId: string
  direction: Direction
  amount: string
  currency: string
  postedAt: string
  description: string | null
}

export interface TransactionRecord {
  id: string
  reference: string
  type: TxType
  status: TxStatus
  amount: string
  currency: string
  sourceAccountId: string | null
  destinationAccountId: string | null
  description: string | null
  rejectionCode: string | null
  rejectionReason: string | null
  reversesTransactionId: string | null
  initiatedBy: string
  correlationId: string | null
  createdAt: string
  postedAt: string | null
}

export interface TransactionResponse extends TransactionRecord {
  balanced: boolean
  entries: JournalEntry[]
}

export interface TransactionDetail {
  transaction: TransactionResponse
  reversedByTransactionId: string | null
  reversedByReference: string | null
}

export interface Overview {
  customerAccounts: number
  simulationAccounts: number
  postedTransactions: number
  rejectedTransactions: number
  reversedTransactions: number
  postedValue: string
  pendingOutboxEvents: number
  last14Days: { day: string; posted: number; rejected: number; postedValue: string }[]
  recentTransactions: TransactionRecord[]
}

export interface IntegrityReport {
  checkedAt: string
  healthy: boolean
  journalTransactions: number
  journalEntries: number
  accountsChecked: number
  totalDebits: string
  totalCredits: string
  unbalancedTransactions: string[]
  postedWithoutEntries: string[]
  projectionMismatches: { accountId: string; projectedBalance: string; ledgerBalance: string }[]
  unauthorizedNegativeBalances: string[]
  duplicateIdempotentEffects: string[]
}

export interface AuditEvent {
  id: number
  actor: string
  action: string
  resourceType: string
  resourceId: string | null
  correlationId: string | null
  details: string | null
  createdAt: string
}

export interface Me {
  username: string
  roles: string[]
}

export interface Problem {
  status: number
  code?: string
  detail?: string
  correlationId?: string
  fieldErrors?: Record<string, string>
}

export class ApiError extends Error {
  readonly problem: Problem

  constructor(problem: Problem) {
    super(problem.detail ?? `Request failed with status ${problem.status}`)
    this.problem = problem
  }
}

export interface ApiResult<T> {
  status: number
  data: T
  replayed: boolean
}

let authHeader: string | null = null

export function setCredentials(username: string, password: string) {
  authHeader = 'Basic ' + btoa(`${username}:${password}`)
}

export function clearCredentials() {
  authHeader = null
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

async function request<T>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<ApiResult<T>> {
  const response = await fetch(path, {
    method,
    headers: {
      Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(authHeader ? { Authorization: authHeader } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const text = await response.text()
  const parsed: unknown = text ? JSON.parse(text) : null
  const replayed = response.headers.get('Idempotent-Replayed') === 'true'
  // 422 from a transaction endpoint is a recorded business rejection with a transaction body.
  const isTransactionBody = response.status === 422 && parsed !== null && typeof parsed === 'object' && 'reference' in parsed
  if (!response.ok && !isTransactionBody) {
    const problem = (parsed ?? {}) as Problem
    throw new ApiError({ ...problem, status: response.status })
  }
  return { status: response.status, data: parsed as T, replayed }
}

const get = async <T,>(path: string) => (await request<T>('GET', path)).data

function query(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') search.set(k, String(v))
  })
  const s = search.toString()
  return s ? `?${s}` : ''
}

export const api = {
  me: () => get<Me>('/api/v1/me'),
  overview: () => get<Overview>('/api/v1/reports/overview'),

  accounts: (p: { page?: number; size?: number; status?: string; search?: string; includeSimulation?: boolean }) =>
    get<Page<Account>>('/api/v1/accounts' + query(p)),
  account: (id: string) => get<Account>(`/api/v1/accounts/${id}`),
  accountBalance: (id: string) => get<BalanceView>(`/api/v1/accounts/${id}/balance`),
  accountEntries: (id: string, page = 0) => get<Page<JournalEntry>>(`/api/v1/accounts/${id}/entries` + query({ page, size: 25 })),
  createAccount: async (name: string, ownerReference?: string) =>
    (await request<Account>('POST', '/api/v1/accounts', { name, ownerReference: ownerReference || null })).data,
  changeStatus: async (id: string, status: AccountStatus, reason: string) =>
    (await request<Account>('PATCH', `/api/v1/accounts/${id}/status`, { status, reason })).data,

  transactions: (p: { page?: number; size?: number; status?: string; type?: string; accountId?: string; reference?: string }) =>
    get<Page<TransactionRecord>>('/api/v1/transactions' + query(p)),
  transaction: (id: string) => get<TransactionDetail>(`/api/v1/transactions/${id}`),

  deposit: (key: string, body: { accountId: string; amount: string; description?: string }) =>
    request<TransactionResponse>('POST', '/api/v1/transactions/deposits', body, { 'Idempotency-Key': key }),
  withdraw: (key: string, body: { accountId: string; amount: string; description?: string }) =>
    request<TransactionResponse>('POST', '/api/v1/transactions/withdrawals', body, { 'Idempotency-Key': key }),
  transfer: (key: string, body: { sourceAccountId: string; destinationAccountId: string; amount: string; description?: string }) =>
    request<TransactionResponse>('POST', '/api/v1/transactions/transfers', body, { 'Idempotency-Key': key }),
  reverse: (key: string, id: string, reason: string) =>
    request<TransactionResponse>('POST', `/api/v1/transactions/${id}/reversals`, { reason }, { 'Idempotency-Key': key }),

  ledgerEntries: (page = 0, accountId?: string) => get<Page<JournalEntry>>('/api/v1/ledger/entries' + query({ page, size: 50, accountId })),
  integrity: () => get<IntegrityReport>('/api/v1/ledger/integrity'),

  audit: (page = 0, resourceType?: string) => get<Page<AuditEvent>>('/api/v1/audit-events' + query({ page, size: 50, resourceType })),

  simulation: async <T,>(name: string, body: unknown) => (await request<T>('POST', `/api/v1/simulations/${name}`, body)).data,
}
