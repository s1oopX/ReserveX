export type SlotUiStatus = 'unreleased' | 'available' | 'full' | 'ended'

export function getSlotUiStatus(slot: { released: boolean; full: boolean; remain: number; validUntil: string }, now: string): SlotUiStatus {
  if (slot.validUntil && slot.validUntil < now) return 'ended'
  if (!slot.released) return 'unreleased'
  if (slot.full || slot.remain <= 0) return 'full'
  return 'available'
}
