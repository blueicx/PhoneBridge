const crypto = require('node:crypto');

const SENSITIVE_KEY_PATTERN = /(token|secret|password|authorization|cookie|apikey|api_key)/i;
const SENSITIVE_VALUE_PATTERN = /(bearer\s+[a-z0-9._-]+|sk-[a-z0-9_-]+|AIzaSy[a-z0-9_-]+)/i;

function redactSecret(value) {
  if (value == null) return value;
  const str = String(value);
  if (!str) return str;
  if (str.length <= 8) return '[REDACTED]';
  if (str.startsWith('sk-')) {
    const tail = str.slice(-4);
    return `sk-***${tail}`;
  }
  return `${str.slice(0, 3)}***${str.slice(-4)}`;
}

function isRedactedPlaceholder(value) {
  if (!value) return false;
  const str = String(value).trim();
  return str === '[REDACTED]' || str.includes('***');
}

function sanitizeHeaders(headers = {}) {
  const result = {};
  for (const [k, v] of Object.entries(headers || {})) {
    if (SENSITIVE_KEY_PATTERN.test(k) || SENSITIVE_VALUE_PATTERN.test(String(v))) {
      result[k] = '[REDACTED]';
    } else {
      result[k] = v;
    }
  }
  return result;
}

function sanitizeProviderConfig(config = {}) {
  if (!config || typeof config !== 'object') return {};
  const sanitized = { ...config };
  if (sanitized.apiKey) sanitized.apiKey = redactSecret(sanitized.apiKey);
  if (sanitized.headers) sanitized.headers = sanitizeHeaders(sanitized.headers);
  return sanitized;
}

class LocalRuleFallbackAdapter {
  constructor() {
    this.id = 'local';
    this.name = '本地离线规则引擎';
    this.type = 'local';
  }

  async chat({ prompt = '', messages = [] } = {}) {
    const text = (prompt || messages.at(-1)?.text || '').trim();
    return {
      reply: this.generateReply(text),
      model: 'local-rules-v1'
    };
  }

  generateReply(text) {
    const lower = text.toLowerCase();
    if (/^(你好|hi|hello|在吗|早上好|晚上好)/.test(lower)) {
      return '你好！我是本地离线伴侣规则引擎。当前处于无外部网络离线模式，但我仍能协助你查看设备状态和任务。';
    }
    if (/电量|电池|battery/.test(lower)) {
      return '本地提示：请检查设备遥测卡片了解当前电池健康与电量。';
    }
    if (/任务|task/.test(lower)) {
      return '本地提示：当前任务中枢正常运行中，可直接在任务面板进行查看与管理。';
    }
    if (/温度|temp/.test(lower)) {
      return '本地提示：当前设备温度状态请参见感官与健康面板。';
    }
    return `[本地离线回退] 外部 AI 服务不可用或已降级。已安全接收你的指令：「${text.slice(0, 50)}」。`;
  }

  async probe() {
    return { ok: true, latencyMs: 1, model: 'local-rules-v1', status: 'healthy' };
  }
}

function requestSignal(timeoutMs) {
  return typeof AbortSignal?.timeout === 'function' ? AbortSignal.timeout(Math.max(1000, Number(timeoutMs) || 15000)) : undefined;
}

class OpenAiCompatibleAdapter {
  constructor({ fetchImpl = globalThis.fetch } = {}) {
    this.fetchImpl = fetchImpl;
    this.id = 'openai';
  }

  async chat({ prompt = '', messages = [], config = {}, timeoutMs = 15000 } = {}) {
    if (!config.apiKey || isRedactedPlaceholder(config.apiKey)) throw new Error('openai api key missing');
    const context = messages.length
      ? messages.map(item => ({ role: item.role || 'user', content: item.text || item.content || '' }))
      : [{ role: 'user', content: prompt }];
    const response = await this.fetchImpl(`${String(config.endpoint || '').replace(/\/$/, '')}/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${config.apiKey}`, ...(config.headers || {}) },
      body: JSON.stringify({ model: config.model, messages: context, stream: false }),
      signal: requestSignal(timeoutMs)
    });
    if (!response.ok) throw new Error(`openai provider HTTP ${response.status}`);
    const data = await response.json();
    const reply = data.choices?.[0]?.message?.content || data.choices?.[0]?.text || '';
    if (!String(reply).trim()) throw new Error('openai provider returned empty response');
    return { reply: String(reply), model: data.model || config.model };
  }

  async probe(config = {}) {
    if (!config.apiKey || isRedactedPlaceholder(config.apiKey)) return { latencyMs: 0, model: config.model, status: 'unconfigured' };
    const started = Date.now();
    const response = await this.fetchImpl(`${String(config.endpoint || '').replace(/\/$/, '')}/models`, {
      method: 'GET',
      headers: { authorization: `Bearer ${config.apiKey}`, ...(config.headers || {}) },
      signal: requestSignal(5000)
    });
    if (!response.ok) throw new Error(`openai provider HTTP ${response.status}`);
    return { latencyMs: Date.now() - started, model: config.model, status: 'healthy' };
  }
}

class GeminiCompatibleAdapter {
  constructor({ fetchImpl = globalThis.fetch } = {}) {
    this.fetchImpl = fetchImpl;
    this.id = 'gemini';
  }

  async chat({ prompt = '', config = {}, timeoutMs = 15000 } = {}) {
    if (!config.apiKey || isRedactedPlaceholder(config.apiKey)) throw new Error('gemini api key missing');
    const endpoint = `${String(config.endpoint || '').replace(/\/$/, '')}/models/${encodeURIComponent(config.model)}:generateContent?key=${encodeURIComponent(config.apiKey)}`;
    const response = await this.fetchImpl(endpoint, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...(config.headers || {}) },
      body: JSON.stringify({ contents: [{ role: 'user', parts: [{ text: prompt }] }] }),
      signal: requestSignal(timeoutMs)
    });
    if (!response.ok) throw new Error(`gemini provider HTTP ${response.status}`);
    const data = await response.json();
    const reply = data.candidates?.[0]?.content?.parts?.map(part => part.text || '').join('') || '';
    if (!String(reply).trim()) throw new Error('gemini provider returned empty response');
    return { reply: String(reply), model: config.model };
  }

  async probe(config = {}) {
    if (!config.apiKey || isRedactedPlaceholder(config.apiKey)) return { latencyMs: 0, model: config.model, status: 'unconfigured' };
    return { latencyMs: 0, model: config.model, status: 'configured' };
  }
}

class AiProviderManager {
  constructor({
    activeProviderId = 'codex',
    timeoutMs = 15000,
    configs = {},
    adapters = {},
    fetchImpl = globalThis.fetch,
    enableNetworkAdapters = true,
    now = () => Date.now()
  } = {}) {
    this.activeProviderId = activeProviderId;
    this.fallbackToLocal = true;
    this.timeoutMs = timeoutMs;
    this.localRulesEnabled = true;
    this.degradationCount = 0;
    this.lastLatencyMs = 0;
    this.lastProbedAt = null;
    this.now = now;
    this.fetchImpl = fetchImpl;

    // Real raw configs
    this.configs = {
      codex: {
        id: 'codex',
        name: 'Codex 命令行桥接',
        type: 'codex',
        model: 'codex-chat',
        status: 'ready'
      },
      openai: {
        id: 'openai',
        name: 'OpenAI-Compatible',
        type: 'openai',
        endpoint: 'https://api.openai.com/v1',
        model: 'gpt-4o-mini',
        apiKey: '',
        headers: {},
        status: 'configured'
      },
      gemini: {
        id: 'gemini',
        name: 'Gemini-Compatible',
        type: 'gemini',
        endpoint: 'https://generativelanguage.googleapis.com/v1beta',
        model: 'gemini-1.5-flash',
        apiKey: '',
        headers: {},
        status: 'configured'
      },
      local: {
        id: 'local',
        name: '本地离线规则',
        type: 'local',
        model: 'local-rules-v1',
        status: 'ready'
      }
    };

    // Apply any initial configs
    for (const [id, cfg] of Object.entries(configs)) {
      if (this.configs[id]) {
        this.configs[id] = { ...this.configs[id], ...cfg };
      } else {
        this.configs[id] = { id, ...cfg };
      }
    }

    // Custom or mock adapters
    this.adapters = {
      local: new LocalRuleFallbackAdapter(),
      ...(enableNetworkAdapters ? {
        openai: new OpenAiCompatibleAdapter({ fetchImpl }),
        gemini: new GeminiCompatibleAdapter({ fetchImpl })
      } : {}),
      ...adapters
    };
  }

  getProviders() {
    return Object.values(this.configs).map(c => {
      const sanitized = sanitizeProviderConfig(c);
      return {
        ...sanitized,
        isActive: sanitized.id === this.activeProviderId
      };
    });
  }

  getRawProviderConfig(id) {
    return this.configs[id] ? { ...this.configs[id] } : null;
  }

  getSettings() {
    return {
      activeProviderId: this.activeProviderId,
      fallbackToLocal: this.fallbackToLocal,
      timeoutMs: this.timeoutMs,
      localRulesEnabled: this.localRulesEnabled,
      degradationCount: this.degradationCount,
      providers: this.getProviders()
    };
  }

  updateSettings(patch = {}) {
    if (patch.activeProviderId && this.configs[patch.activeProviderId]) {
      this.activeProviderId = String(patch.activeProviderId);
    }
    if (patch.timeoutMs !== undefined) {
      this.timeoutMs = Math.max(1000, Math.min(120000, Number(patch.timeoutMs) || 15000));
    }
    if (patch.fallbackToLocal !== undefined) {
      this.fallbackToLocal = Boolean(patch.fallbackToLocal);
    }
    if (patch.localRulesEnabled !== undefined) {
      this.localRulesEnabled = Boolean(patch.localRulesEnabled);
    }

    if (patch.providers && typeof patch.providers === 'object') {
      for (const [id, incoming] of Object.entries(patch.providers)) {
        if (!this.configs[id]) continue;
        const current = this.configs[id];
        const next = { ...current, ...incoming };

        // If incoming apiKey is a masked placeholder, preserve existing raw apiKey
        if (incoming.apiKey && isRedactedPlaceholder(incoming.apiKey)) {
          next.apiKey = current.apiKey;
        }
        this.configs[id] = next;
      }
    }

    return this.getSettings();
  }

  getDegradationCount() {
    return this.degradationCount;
  }

  async probeProvider(providerId) {
    const id = String(providerId || '');
    const config = this.configs[id];
    if (!config) {
      return { ok: false, providerId: id, error: `Provider ${id} not found` };
    }

    const start = this.now();
    try {
      if (this.adapters[id] && typeof this.adapters[id].probe === 'function') {
        const res = await this.adapters[id].probe(config);
        const latency = this.now() - start;
        return {
          ok: true,
          providerId: id,
          latencyMs: res.latencyMs || latency,
          model: res.model || config.model,
          status: 'healthy'
        };
      }

      if (id === 'local') {
        return { ok: true, providerId: id, latencyMs: 1, model: config.model, status: 'healthy' };
      }

      // Default mock/stub probe for registered network providers
      const latency = Math.max(5, this.now() - start);
      return {
        ok: true,
        providerId: id,
        latencyMs: latency,
        model: config.model,
        status: config.apiKey ? 'healthy' : 'unconfigured'
      };
    } catch (error) {
      return {
        ok: false,
        providerId: id,
        latencyMs: this.now() - start,
        error: error.message
      };
    }
  }

  async chat({ prompt = '', messages = [], memories = [], providerId = null, options = {} } = {}) {
    const targetProviderId = providerId || this.activeProviderId;
    const start = this.now();

    // 1. Try explicit target provider
    if (targetProviderId !== 'local') {
      try {
        const adapter = this.adapters[targetProviderId];
        if (adapter && typeof adapter.chat === 'function') {
          const res = await adapter.chat({
            prompt,
            messages,
            memories,
            options,
            timeoutMs: this.timeoutMs,
            config: this.configs[targetProviderId]
          });
          this.lastLatencyMs = this.now() - start;
          return {
            ok: true,
            id: res.id || `ai_${crypto.randomUUID()}`,
            providerId: targetProviderId,
            reply: res.reply || '',
            model: res.model || this.configs[targetProviderId]?.model,
            degraded: false
          };
        }
        throw new Error(`Provider ${targetProviderId} adapter unavailable`);
      } catch (error) {
        // Fallback rule:
        // "显式 provider 优先，失败只回退本地，备用联网 provider 不自动调用"
        if (!this.fallbackToLocal || !this.localRulesEnabled) throw error;
        this.degradationCount++;
        // Explicitly fallback ONLY to local
        const localAdapter = this.adapters.local || new LocalRuleFallbackAdapter();
        const fallbackRes = await localAdapter.chat({ prompt, messages });
        this.lastLatencyMs = this.now() - start;
        return {
          ok: true,
          id: fallbackRes.id || `ai_${crypto.randomUUID()}`,
          providerId: targetProviderId,
          fallbackProvider: 'local',
          reply: fallbackRes.reply,
          model: fallbackRes.model,
          degraded: true,
          error: error.message
        };
      }
    }

    // Direct local call
    const localAdapter = this.adapters.local || new LocalRuleFallbackAdapter();
    const fallbackRes = await localAdapter.chat({ prompt, messages });
    this.lastLatencyMs = this.now() - start;
    return {
      ok: true,
      id: fallbackRes.id || `ai_${crypto.randomUUID()}`,
      providerId: 'local',
      reply: fallbackRes.reply,
      model: fallbackRes.model,
      degraded: false
    };
  }
}

module.exports = {
  AiProviderManager,
  LocalRuleFallbackAdapter,
  sanitizeProviderConfig,
  redactSecret,
  isRedactedPlaceholder
};
