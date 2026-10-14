const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {
  AiProviderManager,
  sanitizeProviderConfig,
  redactSecret
} = require('./ai-provider');
const { RuntimePersistence } = require('./runtime-persistence');

const fakeOpenAiKey = ['sk', '-1234567890abcdef'].join('');
const fakeGeminiKey = ['AIza', 'SySecretGeminiKey123'].join('');

test('redacts sensitive keys in provider configuration and logs', () => {
  assert.equal(redactSecret(fakeOpenAiKey), 'sk-***cdef');
  assert.equal(redactSecret('short'), '[REDACTED]');
  assert.equal(redactSecret(''), '');
  assert.equal(redactSecret(null), null);

  const config = {
    id: 'openai',
    name: 'OpenAI API',
    apiKey: ['sk', '-abcdef1234567890'].join(''),
    headers: { Authorization: `Bearer ${['sk', '-abcdef1234567890'].join('')}` },
    baseUrl: 'https://api.openai.com/v1',
    model: 'gpt-4o-mini'
  };

  const sanitized = sanitizeProviderConfig(config);
  assert.notEqual(sanitized.apiKey, ['sk', '-abcdef1234567890'].join(''));
  assert.ok(sanitized.apiKey.includes('***'));
  assert.ok(sanitized.headers.Authorization.includes('[REDACTED]'));
  assert.equal(sanitized.baseUrl, 'https://api.openai.com/v1');
});

test('AiProviderManager registers default providers and redacts in getProviders()', () => {
  const manager = new AiProviderManager({
    configs: {
      openai: { apiKey: ['sk', '-1234567890123456'].join(''), model: 'gpt-4o-mini' },
      gemini: { apiKey: fakeGeminiKey, model: 'gemini-1.5-flash' }
    }
  });

  const providers = manager.getProviders();
  const ids = providers.map(p => p.id);
  assert.ok(ids.includes('codex'));
  assert.ok(ids.includes('openai'));
  assert.ok(ids.includes('gemini'));
  assert.ok(ids.includes('local'));

  const openai = providers.find(p => p.id === 'openai');
  assert.ok(openai.apiKey.includes('***'));
  assert.notEqual(openai.apiKey, ['sk', '-1234567890123456'].join(''));
});

test('AiProviderManager persists safe settings without provider secrets', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'phonebridge-provider-'));
  const persistence = new RuntimePersistence({ dir });
  const first = new AiProviderManager({ persistence, configs: { openai: { apiKey: 'sk-runtime-secret', model: 'gpt-test' } } });
  first.updateSettings({ activeProviderId: 'local', timeoutMs: 5000 });
  const saved = JSON.parse(fs.readFileSync(path.join(dir, 'provider-settings.json'), 'utf8'));
  assert.doesNotMatch(JSON.stringify(saved), /sk-runtime-secret/);
  const restored = new AiProviderManager({ persistence });
  assert.equal(restored.getSettings().activeProviderId, 'local');
  assert.equal(restored.getSettings().timeoutMs, 5000);
});

test('PATCH / settings preserves existing apiKey when receiving redacted placeholder', () => {
  const manager = new AiProviderManager({
    configs: {
      openai: { apiKey: ['sk', '-real-secret-123456'].join(''), model: 'gpt-4o' }
    }
  });

  manager.updateSettings({
    activeProviderId: 'openai',
    timeoutMs: 8000,
    providers: {
      openai: { apiKey: ['sk', '-***3456'].join(''), model: 'gpt-4o-mini' }
    }
  });

  const settings = manager.getSettings();
  assert.equal(settings.activeProviderId, 'openai');
  assert.equal(settings.timeoutMs, 8000);

  // Internal real config still has real secret
  const rawConfig = manager.getRawProviderConfig('openai');
  assert.equal(rawConfig.apiKey, ['sk', '-real-secret-123456'].join(''));
  assert.equal(rawConfig.model, 'gpt-4o-mini');
});

test('explicit provider priority: failure falls back ONLY to local, secondary online provider is NOT called', async () => {
  let secondaryCalled = false;

  const manager = new AiProviderManager({
    activeProviderId: 'openai',
    configs: {
      openai: { apiKey: ['sk', '-fail'].join(''), model: 'test' },
      gemini: { apiKey: ['sk', '-gemini'].join(''), model: 'test' }
    },
    adapters: {
      openai: {
        chat: async () => {
          throw new Error('Network timeout');
        }
      },
      gemini: {
        chat: async () => {
          secondaryCalled = true;
          return { reply: 'from gemini' };
        }
      },
      local: {
        chat: async (prompt) => {
          return { reply: `本地离线规则兜底：${prompt}` };
        }
      }
    }
  });

  const result = await manager.chat({ prompt: '你好' });

  assert.equal(secondaryCalled, false, 'secondary online provider must NOT be automatically called');
  assert.equal(result.degraded, true);
  assert.equal(result.fallbackProvider, 'local');
  assert.ok(result.reply.includes('本地离线规则兜底'));
  assert.equal(manager.getDegradationCount(), 1);
});

test('configured network provider without an adapter is treated as primary failure and degrades locally', async () => {
  const manager = new AiProviderManager({
    activeProviderId: 'openai',
    configs: { openai: { apiKey: ['sk', '-configured'].join(''), model: 'test' } },
    enableNetworkAdapters: false,
    adapters: {
      local: { chat: async () => ({ reply: 'offline', model: 'local-test' }) }
    }
  });

  const result = await manager.chat({ prompt: '测试未接入 provider' });

  assert.equal(result.degraded, true);
  assert.equal(result.providerId, 'openai');
  assert.equal(result.fallbackProvider, 'local');
  assert.equal(result.reply, 'offline');
  assert.equal(manager.getDegradationCount(), 1);
});

test('OpenAI-compatible provider sends the selected model and returns its response', async () => {
  let request = null;
  const manager = new AiProviderManager({
    activeProviderId: 'openai',
    configs: {
      openai: { endpoint: 'https://provider.test/v1', apiKey: ['sk', '-test-secret'].join(''), model: 'unit-model' }
    },
    fetchImpl: async (url, init) => {
      request = { url, init };
      return createSseResponse([
        'data: {"choices":[{"delta":{"content":"来自兼容 provider"}}]}\n\n',
        'data: [DONE]\n\n'
      ]);
    }
  });

  const result = await manager.chat({ prompt: '你好' });

  assert.equal(result.degraded, false);
  assert.equal(result.providerId, 'openai');
  assert.equal(result.reply, '来自兼容 provider');
  assert.equal(request.url, 'https://provider.test/v1/chat/completions');
  assert.equal(JSON.parse(request.init.body).model, 'unit-model');
});

test('probeProvider reports status and latency without exposing keys', async () => {
  const manager = new AiProviderManager();

  const probeLocal = await manager.probeProvider('local');
  assert.equal(probeLocal.ok, true);
  assert.equal(probeLocal.providerId, 'local');
  assert.ok(typeof probeLocal.latencyMs === 'number');

  const probeUnknown = await manager.probeProvider('non_existent');
  assert.equal(probeUnknown.ok, false);
  assert.ok(probeUnknown.error);
});

test('provider settings expose capabilities and enforce the daily output budget', async () => {
  const manager = new AiProviderManager({
    activeProviderId: 'local',
    dailyOutputTokenBudget: 2,
    adapters: { local: { chat: async () => ({ reply: '123456789' }), capabilities: () => ['text', 'offline'] } }
  });
  assert.deepEqual(manager.getProviders().find(item => item.id === 'local').capabilities, ['text', 'offline', 'cancel']);
  await assert.rejects(() => manager.chat({ prompt: 'budget' }), /budget exhausted/);
  assert.equal(manager.getSettings().budgetRemaining, 2);
});

test('chat forwards the stable requestId to adapters together with its abort signal', async () => {
  let received;
  const manager = new AiProviderManager({
    activeProviderId: 'custom',
    configs: { custom: { id: 'custom', model: 'test', capabilities: ['text'] } },
    adapters: {
      custom: { chat: async request => { received = request; return { reply: 'ok' }; } },
      local: { chat: async () => ({ reply: 'fallback' }) }
    }
  });
  await manager.chat({ requestId: 'chat_stable_1', prompt: 'hello' });
  assert.equal(received.requestId, 'chat_stable_1');
  assert.equal(received.signal instanceof AbortSignal, true);
});

test('chat does not splice a local fallback after an adapter already emitted partial output', async () => {
  let fallbackCalled = false;
  const manager = new AiProviderManager({
    activeProviderId: 'custom',
    configs: { custom: { id: 'custom', model: 'test', capabilities: ['text', 'stream'] } },
    adapters: {
      custom: { chat: async ({ onDelta }) => { onDelta('片段'); throw new Error('stream interrupted'); } },
      local: { chat: async () => { fallbackCalled = true; return { reply: '本地完整回复' }; } }
    }
  });
  await assert.rejects(() => manager.chat({ requestId: 'partial-then-fail', prompt: '继续' }), /stream interrupted/);
  assert.equal(fallbackCalled, false);
});

test('stream falls back to deterministic chunks and cancellation aborts active adapters', async () => {
  let resolve;
  const manager = new AiProviderManager({
    activeProviderId: 'custom',
    configs: { custom: { id: 'custom', model: 'test', capabilities: ['text', 'stream', 'cancel'] } },
    adapters: {
      custom: {
        stream: async function* ({ signal }) {
          await new Promise(done => { resolve = done; signal.addEventListener('abort', done, { once: true }); });
          if (signal.aborted) throw new Error('aborted');
          yield 'never';
        }
      },
      local: { chat: async () => ({ reply: 'offline response' }) }
    }
  });
  const iterator = manager.stream({ requestId: 'cancel-me', prompt: 'x' });
  const pending = iterator.next();
  await new Promise(resolveTick => setImmediate(resolveTick));
  assert.equal(manager.cancel('cancel-me'), true);
  resolve?.();
  await assert.rejects(() => pending, /aborted|cancelled/);
});

test('stream failures fall back only to local rules and keep yielding bounded chunks', async () => {
  const manager = new AiProviderManager({
    activeProviderId: 'custom',
    configs: { custom: { id: 'custom', model: 'test', capabilities: ['text', 'stream'] } },
    adapters: {
      custom: { stream: async function* () { throw new Error('remote unavailable'); } },
      local: { chat: async () => ({ reply: '本地继续陪你。' }) }
    }
  });
  const deltas = [];
  for await (const delta of manager.stream({ requestId: 'stream-fallback', prompt: '继续' })) deltas.push(delta);
  assert.equal(deltas.join(''), '本地继续陪你。');
  assert.equal(deltas.every(delta => delta.length <= 24), true);
  assert.equal(manager.degradationCount, 1);
});

function createSseResponse(chunks, { onCancel = () => {} } = {}) {
  const encoder = new TextEncoder();
  return {
    ok: true,
    status: 200,
    headers: { get: name => name.toLowerCase() === 'content-type' ? 'text/event-stream' : null },
    body: new ReadableStream({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(chunk instanceof Uint8Array ? chunk : encoder.encode(chunk));
        controller.close();
      },
      cancel(reason) { onCancel(reason); }
    })
  };
}

test('OpenAI-compatible adapter reads actual SSE deltas across UTF-8 chunk boundaries', async () => {
  let request;
  const first = 'data: {"choices":[{"delta":{"content":"你"}}]}\n\n';
  const second = 'data: {"choices":[{"delta":{"content":"好"}}]}\n\ndata: [DONE]\n\n';
  const bytes = new TextEncoder().encode(first + second);
  const splitAt = new TextEncoder().encode(first).length + 29;
  const chunks = [bytes.slice(0, splitAt), bytes.slice(splitAt)];
  const manager = new AiProviderManager({
    activeProviderId: 'openai',
    configs: { openai: { endpoint: 'https://provider.test/v1', apiKey: fakeOpenAiKey, model: 'unit-stream' } },
    fetchImpl: async (url, init) => {
      request = { url, init };
      return createSseResponse(chunks);
    }
  });

  const deltas = [];
  for await (const delta of manager.stream({ requestId: 'openai-sse', prompt: '你好' })) deltas.push(delta);

  assert.equal(deltas.join(''), '你好');
  assert.equal(request.url, 'https://provider.test/v1/chat/completions');
  assert.equal(JSON.parse(request.init.body).stream, true);
  assert.equal(JSON.parse(request.init.body).model, 'unit-stream');
});

test('Gemini-compatible adapter streams SSE and maps assistant history to model content', async () => {
  let request;
  const manager = new AiProviderManager({
    activeProviderId: 'gemini',
    configs: { gemini: { endpoint: 'https://provider.test/v1beta', apiKey: fakeGeminiKey, model: 'unit-gemini' } },
    fetchImpl: async (url, init) => {
      request = { url, init };
      return createSseResponse([
        'data: {"candidates":[{"content":{"parts":[{"text":"续上"}]}}]}\n\n',
        'data: [DONE]\n\n'
      ]);
    }
  });

  const deltas = [];
  for await (const delta of manager.stream({
    requestId: 'gemini-sse',
    prompt: '当前问题',
    messages: [
      { role: 'system', text: '保持温暖' },
      { role: 'user', text: '上一句' },
      { role: 'assistant', text: '上一答' },
      { role: 'user', text: '当前问题' }
    ]
  })) deltas.push(delta);

  const body = JSON.parse(request.init.body);
  assert.equal(deltas.join(''), '续上');
  assert.match(request.url, /:streamGenerateContent\?alt=sse&key=/);
  assert.deepEqual(body.systemInstruction.parts, [{ text: '保持温暖' }]);
  assert.deepEqual(body.contents.map(item => item.role), ['user', 'model', 'user']);
  assert.equal(body.contents[1].parts[0].text, '上一答');
});

test('cancelling a network stream aborts the fetch and releases its response reader', async () => {
  let requestSignal;
  let readerCancelled = false;
  const response = {
    ok: true,
    status: 200,
    headers: { get: () => 'text/event-stream' },
    body: new ReadableStream({ cancel() { readerCancelled = true; } })
  };
  const manager = new AiProviderManager({
    activeProviderId: 'openai',
    configs: { openai: { apiKey: fakeOpenAiKey, model: 'unit-stream' } },
    fetchImpl: async (_url, init) => { requestSignal = init.signal; return response; }
  });
  const iterator = manager.stream({ requestId: 'cancel-sse', prompt: '停下' });
  const pending = iterator.next();
  await new Promise(resolve => setImmediate(resolve));

  assert.equal(manager.cancel('cancel-sse'), true);
  await assert.rejects(pending, /cancel/i);
  assert.equal(requestSignal.aborted, true);
  assert.equal(readerCancelled, true);
});

test('chat consumes adapter stream deltas and reports each increment before completion', async () => {
  const seen = [];
  const manager = new AiProviderManager({
    activeProviderId: 'openai',
    configs: { openai: { apiKey: fakeOpenAiKey, model: 'unit-stream' } },
    fetchImpl: async () => createSseResponse([
      'data: {"choices":[{"delta":{"content":"逐"}}]}\n\n',
      'data: {"choices":[{"delta":{"content":"步"}}]}\n\n',
      'data: [DONE]\n\n'
    ])
  });

  const result = await manager.chat({ requestId: 'chat-over-sse', prompt: '继续', onDelta: delta => seen.push(delta) });

  assert.equal(result.reply, '逐步');
  assert.deepEqual(seen, ['逐', '步']);
});

test('direct provider streams charge the configured output budget on successful completion', async () => {
  const manager = new AiProviderManager({
    activeProviderId: 'custom',
    dailyOutputTokenBudget: 4,
    adapters: { custom: { stream: async function* () { yield '12345678'; } } }
  });

  const chunks = [];
  for await (const chunk of manager.stream({ requestId: 'budgeted-stream', prompt: 'budget' })) chunks.push(chunk);

  assert.equal(chunks.join(''), '12345678');
  assert.equal(manager.getSettings().dailyOutputTokens, 2);
});
