import test from 'node:test'
import assert from 'node:assert/strict'
import { getReservationResultState } from '../src/lib/reservationResultState.ts'

test('reservation result actions follow the persisted reservation status', () => {
  assert.equal(getReservationResultState('CONFIRMED').canShowQr, true)
  assert.equal(getReservationResultState('PENDING').pending, true)
  assert.equal(getReservationResultState('REVIEW_REQUIRED').statusLabel, '人工处理中')
  assert.equal(getReservationResultState('VERIFIED').title, '预约已核销')

  for (const status of ['CANCELLED', 'EXPIRED', 'FAILED']) {
    assert.equal(getReservationResultState(status).terminalFailure, true)
    assert.equal(getReservationResultState(status).canShowQr, false)
  }
})
