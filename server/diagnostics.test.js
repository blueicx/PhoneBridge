const test = require('node:test');
const assert = require('node:assert/strict');
const { DiagnosticsCollector } = require('./diagnostics');

test('DiagnosticsCollector initializes with startup duration and default performance strategy', () => {
  const startTime = Date.now() - 50;
  const collector = new DiagnosticsCollector({ startTime });

  const snapshot = collector.snapshot();
  assert.equal(snapshot.ok, true);
  assert.ok(snapshot.startupDurationMs >= 50, 'startup duration should be tracked');
  assert.equal(snapshot.performance.fpsTarget, 30, 'must retain 30fps target');
  assert.equal(snapshot.performance.throttlingStrategy, 'background_reduced', 'must specify background throttling strategy');
});

test('DiagnosticsCollector tracks telemetry, sync latency, and event backlog', () => {
  const collector = new DiagnosticsCollector();

  collector.updateTelemetry({
    battery: 85,
    memory: 40,
    temperature: 32.0,
    cpu: 18,
    network_rx: 2048,
    network_tx: 1024
  });

  collector.recordSyncLatency(14.5);
  collector.setEventBacklog(3);

  const snapshot = collector.snapshot();
  assert.equal(snapshot.telemetry.battery, 85);
  assert.equal(snapshot.telemetry.temperature, 32.0);
  assert.equal(snapshot.syncLatencyMs, 14.5);
  assert.equal(snapshot.eventBacklog, 3);
});

test('DiagnosticsCollector tracks provider latency and degradation counts', () => {
  const collector = new DiagnosticsCollector();

  collector.recordProviderLatency('openai', 120);
  collector.recordDegradation();
  collector.recordDegradation();

  const snapshot = collector.snapshot();
  assert.equal(snapshot.provider.lastLatencyMs, 120);
  assert.equal(snapshot.provider.activeProviderId, 'openai');
  assert.equal(snapshot.provider.degradationCount, 2);
});

test('DiagnosticsCollector retains only bounded, content-free goal draft provider audits', () => {
  const collector = new DiagnosticsCollector();
  collector.recordGoalDraftAudit({
    providerId: 'openai',
    elapsedMs: 27,
    fallbackReason: 'provider_output_invalid',
    resultStatus: 'ready',
    goalText: 'private goal',
    secret: 'sk-private',
  });

  const entry = collector.snapshot().provider.goalDraftAudit[0];
  assert.deepEqual(entry, {
    providerId: 'openai', elapsedMs: 27, fallbackReason: 'provider_output_invalid', resultStatus: 'ready',
  });
  assert.equal(JSON.stringify(collector.snapshot()).includes('private goal'), false);
  assert.equal(JSON.stringify(collector.snapshot()).includes('sk-private'), false);

  for (let index = 0; index < 105; index += 1) {
    collector.recordGoalDraftAudit({ providerId: 'local', elapsedMs: index, resultStatus: 'ready' });
  }
  const audits = collector.snapshot().provider.goalDraftAudit;
  assert.equal(audits.length, 100);
  assert.equal(audits[0].elapsedMs, 5);
  assert.equal(audits.every(item => Object.keys(item).sort().join(',') === 'elapsedMs,fallbackReason,providerId,resultStatus'), true);
});

test('DiagnosticsCollector toggles foreground/background performance policy', () => {
  const collector = new DiagnosticsCollector();

  assert.equal(collector.snapshot().performance.foreground, true);

  collector.setForeground(false);
  const bgSnapshot = collector.snapshot();
  assert.equal(bgSnapshot.performance.foreground, false);
  assert.equal(bgSnapshot.performance.throttled, true);

  collector.setForeground(true);
  assert.equal(collector.snapshot().performance.throttled, false);
});
