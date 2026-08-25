import assert from 'node:assert/strict'
import test from 'node:test'
import { getSlotUiStatus } from '../src/lib/slotStatus.ts'

const base = { released: true, full: false, remain: 10, validUntil: '2026-08-25 12:00:00' }

test('slot UI status follows ended → unreleased → full → available priority', () => {
  assert.equal(getSlotUiStatus(base, '2026-08-25 11:00:00'), 'available')
  assert.equal(getSlotUiStatus({ ...base, remain: 0 }, '2026-08-25 11:00:00'), 'full')
  assert.equal(getSlotUiStatus({ ...base, released: false }, '2026-08-25 11:00:00'), 'unreleased')
  assert.equal(getSlotUiStatus({ ...base, released: false }, '2026-08-25 13:00:00'), 'ended')
})
