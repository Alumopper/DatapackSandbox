import { describe, expect, it } from 'vitest'
import { PlaygroundClient, PlaygroundClientError, toWebSocketEndpoint } from '../src/client'
import { MockWebSocket } from './setup'

describe('PlaygroundClient', () => {
  it('normalizes API URLs and resolves typed requests', async () => {
    expect(toWebSocketEndpoint('https://playground.example.com')).toBe('wss://playground.example.com/v1/playground')
    expect(toWebSocketEndpoint('wss://playground.example.com/v1/playground/')).toBe('wss://playground.example.com/v1/playground')
    MockWebSocket.responder = (socket, request) => {
      if (request.type === 'session.create') {
        socket.emit({ type: 'session.ready', requestId: request.id, sessionId: 'session-1' })
      }
      if (request.type === 'cell.complete') {
        socket.emit({
          type: 'cell.output',
          requestId: request.id,
          cellId: request.cellId,
          kind: 'completion',
          result: { suggestions: [{ value: 'minecraft:stone', start: 0, end: 2 }] },
        })
      }
    }
    const client = new PlaygroundClient('https://playground.example.com')
    await client.connect()
    const ready = await client.createSession({ version: '26.2', cells: [] }, { auto: true })
    expect(ready.sessionId).toBe('session-1')
    await expect(client.complete('cell', 'mi', 2)).resolves.toEqual([
      { value: 'minecraft:stone', start: 0, end: 2 },
    ])
    client.close()
  })

  it('rejects stable server errors', async () => {
    MockWebSocket.responder = (socket, request) => {
      socket.emit({
        type: 'cell.error',
        requestId: request.id,
        error: { code: 'PROFILE_NOT_ALLOWED', message: 'Profile denied', recoverable: true },
      })
    }
    const client = new PlaygroundClient('ws://localhost:8080/v1/playground')
    await client.connect()
    const failure = await client.createSession({ version: 'old', cells: [] }, {}).catch((error) => error)
    expect(failure).toBeInstanceOf(PlaygroundClientError)
    expect(failure.code).toBe('PROFILE_NOT_ALLOWED')
  })

  it('times out requests that never receive a terminal event', async () => {
    const client = new PlaygroundClient(
      'ws://localhost:8080/v1/playground',
      (url) => new WebSocket(url),
      10,
    )
    await client.connect()
    const failure = await client.createSession({ version: '26.2', cells: [] }, {}).catch((error) => error)
    expect(failure).toBeInstanceOf(PlaygroundClientError)
    expect(failure.code).toBe('REQUEST_TIMEOUT')
  })
})
