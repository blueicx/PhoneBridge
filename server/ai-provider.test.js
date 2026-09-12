const test = require('node:test');
const assert = require('node:assert/strict');
const {
  AiProviderManager,
  sanitizeProviderConfig,
  redactSecret
} = require('./ai-provider');

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
      return {
        ok: true,
        json: async () => ({ choices: [{ message: { content: '来自兼容 provider' } }] })
      };
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
