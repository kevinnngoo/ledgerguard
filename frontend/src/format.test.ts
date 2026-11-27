import { describe, expect, it } from 'vitest'
import { money } from './format'

describe('money', () => {
  it('formats decimal strings without floating point conversion', () => {
    expect(money('1000.00')).toBe('$1,000.00')
    expect(money('99999999999999999.99')).toBe('$99,999,999,999,999,999.99')
    expect(money('0.1')).toBe('$0.10')
    expect(money('-250.50')).toBe('-$250.50')
  })

  it('shows a dash for missing values', () => {
    expect(money(null)).toBe('—')
  })
})
