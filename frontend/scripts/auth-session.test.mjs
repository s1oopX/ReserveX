import assert from 'node:assert/strict'
import test from 'node:test'
import { loadAuthSession, saveAuthSession } from '../src/api/authSession.ts'

class MemoryStorage {
  constructor(entries = []) {
    this.values = new Map(entries)
  }

  getItem(key) {
    return this.values.get(key) ?? null
  }

  setItem(key, value) {
    this.values.set(key, value)
  }

  removeItem(key) {
    this.values.delete(key)
  }
}

const oldSession = { accessToken: 'access-1', role: 'USER' }
const newSession = { accessToken: 'access-2', role: 'USER' }

test('migrates legacy credentials without retaining the refresh token', () => {
  const storage = new MemoryStorage([
    ['reservex.access', oldSession.accessToken],
    ['reservex.refresh', 'refresh-1'],
    ['reservex.role', oldSession.role],
  ])
  assert.deepEqual(loadAuthSession(storage), oldSession)
  assert.deepEqual(JSON.parse(storage.getItem('reservex.auth')), oldSession)
  assert.equal(storage.getItem('reservex.access'), null)
})

test('a failed replacement leaves the complete old session intact', () => {
  const storage = new MemoryStorage([['reservex.auth', JSON.stringify(oldSession)]])
  storage.setItem = () => { throw new Error('storage write failed') }
  assert.throws(() => saveAuthSession(storage, newSession))
  assert.deepEqual(loadAuthSession(storage), oldSession)
})
