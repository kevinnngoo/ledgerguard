// Display helpers. Money is formatted from decimal strings, never via floating point.

export function money(amount: string | null | undefined, currency = 'USD'): string {
  if (amount === null || amount === undefined) return '—'
  // Amounts arrive as decimal strings; format without converting through floating point math.
  const negative = amount.startsWith('-')
  const [whole, fraction = '00'] = amount.replace('-', '').split('.')
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const symbol = currency === 'USD' ? '$' : `${currency} `
  return `${negative ? '-' : ''}${symbol}${grouped}.${fraction.padEnd(2, '0').slice(0, 2)}`
}

export function dateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'medium' })
}

export function shortId(id: string | null | undefined): string {
  return id ? id.slice(0, 8) : '—'
}
