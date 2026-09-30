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
      fallbackProvider: 'local',
      goalDraftAudit: [],
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

  recordGoalDraftAudit(entry = {}) {
    const allowedReasons = new Set(['provider_unavailable', 'provider_output_invalid', 'request_cancelled', 'local_provider_selected']);
    const providerId = String(entry.providerId || 'local').trim();
    const audit = {
      providerId: /^[a-z0-9._-]{1,64}$/i.test(providerId) ? providerId : 'unknown',
      elapsedMs: Math.max(0, Math.min(120_000, Math.round(Number(entry.elapsedMs) || 0))),
      fallbackReason: allowedReasons.has(entry.fallbackReason) ? entry.fallbackReason : null,
      resultStatus: entry.resultStatus === 'failed' ? 'failed' : 'ready',
    };
    this.provider.goalDraftAudit.push(audit);
    if (this.provider.goalDraftAudit.length > 100) this.provider.goalDraftAudit.splice(0, this.provider.goalDraftAudit.length - 100);
    return { ...audit };
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
      provider: {
        ...this.provider,
        goalDraftAudit: this.provider.goalDraftAudit.map(item => ({ ...item })),
      },
      performance: { ...this.performance }
    };
  }
}

module.exports = {
  DiagnosticsCollector
};
