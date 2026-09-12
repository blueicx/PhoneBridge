class DiagnosticsCollector {
  constructor({ startTime = Date.now(), now = () => Date.now() } = {}) {
    this.startTime = startTime;
    this.now = now;
    this.startupDurationMs = Math.max(0, this.now() - this.startTime);
    this.syncLatencyMs = 0;
    this.eventBacklog = 0;
    this.telemetry = {
      cpu: 0,
      memory: 0,
      battery: 100,
      temperature: 25.0,
      network_rx: 0,
      network_tx: 0,
      updatedAt: this.now()
    };
    this.provider = {
      activeProviderId: 'codex',
      lastLatencyMs: 0,
      degradationCount: 0,
      fallbackProvider: 'local'
    };
    this.performance = {
      fpsTarget: 30,
      throttlingStrategy: 'background_reduced',
      foreground: true,
      throttled: false
    };
  }

  updateTelemetry(telemetry = {}) {
    this.telemetry = {
      ...this.telemetry,
      ...telemetry,
      updatedAt: this.now()
    };
  }

  recordSyncLatency(ms) {
    this.syncLatencyMs = Math.max(0, Number(ms) || 0);
  }

  setEventBacklog(count) {
    this.eventBacklog = Math.max(0, Number(count) || 0);
  }

  recordProviderLatency(providerId, latencyMs) {
    this.provider.activeProviderId = String(providerId || this.provider.activeProviderId);
    this.provider.lastLatencyMs = Math.max(0, Number(latencyMs) || 0);
  }

  recordDegradation() {
    this.provider.degradationCount++;
  }

  setForeground(isForeground) {
    this.performance.foreground = Boolean(isForeground);
    this.performance.throttled = !this.performance.foreground;
  }

  snapshot() {
    const uptimeSeconds = Math.floor((this.now() - this.startTime) / 1000);
    return {
      ok: true,
      uptimeSeconds,
      startupDurationMs: Math.max(this.startupDurationMs, this.now() - this.startTime),
      syncLatencyMs: this.syncLatencyMs,
      eventBacklog: this.eventBacklog,
      telemetry: { ...this.telemetry },
      provider: { ...this.provider },
      performance: { ...this.performance }
    };
  }
}

module.exports = {
  DiagnosticsCollector
};
