'use strict';

const BRIDGE_PHASES = Object.freeze(['disconnected', 'connecting', 'online', 'retrying', 'auth_failed']);
const HEALTH_STATUSES = Object.freeze(['unknown', 'inactive', 'active', 'connecting', 'online', 'degraded', 'error', 'expired', 'paused']);

function safeStatus(value, fallback = 'unknown') {
  return HEALTH_STATUSES.includes(String(value)) ? String(value) : fallback;
}

function normalizeState(value = {}) {
  const source = value && typeof value === 'object' ? value : {};
  const bridge = BRIDGE_PHASES.includes(String(source.bridge)) ? String(source.bridge) : 'disconnected';
  const outboxPending = Math.max(0, Math.round(Number(source.outboxPending) || 0));
  return {
    bridge,
    node: safeStatus(source.node),
    camera: safeStatus(source.camera, 'inactive'),
    microphone: safeStatus(source.microphone, 'inactive'),
    model: safeStatus(source.model),
    authorization: safeStatus(source.authorization),
    tasks: safeStatus(source.tasks),
    automation: safeStatus(source.automation),
    outbox: safeStatus(source.outbox),
    outboxPending,
    reconnectAttempt: Math.max(0, Math.round(Number(source.reconnectAttempt) || 0)),
    nextRetryAt: source.nextRetryAt || null,
    targetUrl: source.targetUrl ? String(source.targetUrl).slice(0, 512) : null,
    lastError: source.lastError ? String(source.lastError).slice(0, 500) : null,
    lastAckAt: source.lastAckAt || null,
    updatedAt: source.updatedAt || new Date().toISOString(),
  };
}

function overallStatus(state) {
  const normalized = normalizeState(state);
  if (normalized.bridge === 'auth_failed' || normalized.authorization === 'expired') return 'expired';
  if (normalized.bridge === 'connecting' || normalized.bridge === 'retrying') return 'connecting';
  if (normalized.bridge === 'online' && [normalized.camera, normalized.microphone, normalized.outbox].includes('error')) return 'error';
  if (normalized.bridge === 'online' && (normalized.outboxPending > 0 || normalized.node === 'degraded')) return 'degraded';
  if (normalized.bridge === 'online') return 'online';
  return 'inactive';
}

class DeviceHealthStore {
  constructor(initial = {}, now = () => new Date().toISOString()) {
    this.now = now;
    this.state = normalizeState({ ...initial, updatedAt: initial.updatedAt || now() });
  }

  update(patch = {}) {
    const next = normalizeState({ ...this.state, ...patch, updatedAt: patch.updatedAt || this.now() });
    const changed = JSON.stringify(next) !== JSON.stringify(this.state);
    this.state = next;
    return { changed, state: this.snapshot() };
  }

  snapshot() {
    const state = { ...this.state };
    return { ...state, overall: overallStatus(state) };
  }

  event() {
    return { type: 'device.state', state: this.snapshot() };
  }
}

module.exports = { BRIDGE_PHASES, HEALTH_STATUSES, DeviceHealthStore, normalizeState, overallStatus };
