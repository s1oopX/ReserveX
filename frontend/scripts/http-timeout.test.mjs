import assert from 'node:assert/strict'
import test from 'node:test'
import { build } from 'esbuild'

test('ordinary API requests report an explicit timeout error', async () => {
  const output = await build({
    entryPoints: ['src/api/http.ts'],
    bundle: true,
    format: 'esm',
    platform: 'browser',
    target: 'es2020',
    write: false,
  })

  globalThis.sessionStorage = {
    getItem: () => null,
    setItem: () => {},
    removeItem: () => {},
  }
  let clearedTimeout
  globalThis.window = {
    dispatchEvent: () => {},
    location: { href: '' },
    setTimeout: callback => {
      callback()
      return 7
    },
    clearTimeout: id => { clearedTimeout = id },
  }
  globalThis.fetch = async (_url, init) => {
    assert.equal(init.signal.aborted, true)
    throw new DOMException('aborted', 'AbortError')
  }

  const source = Buffer.from(output.outputFiles[0].text).toString('base64')
  const { ApiError, http } = await import(`data:text/javascript;base64,${source}`)

  await assert.rejects(http.get('/slots'), error => {
    assert.equal(error instanceof ApiError, true)
    assert.equal(error.code, 'REQUEST_TIMEOUT')
    assert.equal(error.message, '请求超时,请稍后重试')
    return true
  })
  assert.equal(clearedTimeout, 7)
})
