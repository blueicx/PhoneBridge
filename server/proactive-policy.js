'use strict';

const TIME_ZONE = 'Asia/Shanghai';
const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;

function number(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function dayKey(timestamp, timeZone = TIME_ZONE) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(timestamp));
}

function hourOfDay(timestamp, timeZone = TIME_ZONE) {
  return Number(new Intl.DateTimeFormat('en-US', {
    timeZone,
    hour: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(timestamp)));
}

function quietHours(hour, start, end) {
  return start > end ? hour >= start || hour < end : hour >= start && hour < end;
}

class ProactivePolicy {
  constructor({ now = () => Date.now(), persistence = null, timeZone = TIME_ZONE } = {}) {
    this.now = now;
    this.persistence = persistence;
    this.timeZone = timeZone;
    this.state = {
      version: 1,
      paused: false,
      focusActive: false,
      quietStart: 23,
      quietEnd: 7,
      maxPerHour: 1,
      maxPerDay: 6,
      dedupeMinutes: 15,
      mutedUntil: 0,
      history: [],
      byKey: {},
      lastSuppression: null,
    };
    this._load();
    this._prune(this.now());
  }

  _load() {
    const saved = this.persistence?.load?.('proactive-policy', null);
    if (!saved || typeof saved !== 'object') return;
    this.state = {
      ...this.state,
      ...saved,
      history: Array.isArray(saved.history) ? saved.history.map(Number).filter(Number.isFinite) : [],
      byKey: saved.byKey && typeof saved.byKey === 'object' ? Object.fromEntries(
        Object.entries(saved.byKey).map(([key, value]) => [String(key), number(value, 0)]).filter(([, value]) => value > 0)
      ) : {},
    };
    this._normalizeSettings();
  }

  _normalizeSettings() {
    this.state.paused = Boolean(this.state.paused);
    this.state.focusActive = Boolean(this.state.focusActive);
    this.state.quietStart = Math.max(0, Math.min(23, Math.round(number(this.state.quietStart, 23))));
    this.state.quietEnd = Math.max(0, Math.min(23, Math.round(number(this.state.quietEnd, 7))));
    this.state.maxPerHour = Math.max(0, Math.min(1, Math.round(number(this.state.maxPerHour, 1))));
    this.state.maxPerDay = Math.max(0, Math.min(6, Math.round(number(this.state.maxPerDay, 6))));
    this.state.dedupeMinutes = Math.max(0, Math.min(1440, Math.round(number(this.state.dedupeMinutes, 15))));
    this.state.mutedUntil = Math.max(0, Math.round(number(this.state.mutedUntil, 0)));
  }

  _prune(timestamp) {
    const cutoff = timestamp - 7 * DAY_MS;
    this.state.history = this.state.history.filter(value => value >= cutoff && value <= timestamp + DAY_MS);
    for (const [key, value] of Object.entries(this.state.byKey)) {
      if (value < cutoff) delete this.state.byKey[key];
    }
  }

  _save() {
    this.persistence?.save?.('proactive-policy', {
      ...this.state,
      history: this.state.history.slice(-200),
      byKey: { ...this.state.byKey },
    });
  }

  _decision(reason, message, timestamp, extra = {}) {
    return {
      allowed: reason === 'allowed',
      reason,
      message,
      at: timestamp,
      ...extra,
    };
  }

  explain(key = '', timestamp = this.now()) {
    const at = number(timestamp, this.now());
    const normalizedKey = String(key || '').slice(0, 160);
    this._prune(at);
    if (this.state.paused) return this._decision('paused', '主动提醒已暂停。', at);
    if (this.state.mutedUntil > at) return this._decision('muted', `主动提醒已静音至 ${new Date(this.state.mutedUntil).toLocaleString('zh-CN') }。`, at, { mutedUntil: this.state.mutedUntil });
    if (this.state.focusActive) return this._decision('focus_mode', '专注状态已开启，普通主动提醒暂时不打扰。', at);
    if (quietHours(hourOfDay(at, this.timeZone), this.state.quietStart, this.state.quietEnd)) {
      return this._decision('quiet_hours', `当前处于静默时段 ${this.state.quietStart}:00–${this.state.quietEnd}:00。`, at);
    }
    if (this.state.maxPerDay === 0) return this._decision('daily_limit', '每日主动提醒额度已关闭。', at);
    const today = dayKey(at, this.timeZone);
    const sentToday = this.state.history.filter(value => dayKey(value, this.timeZone) === today).length;
    if (sentToday >= this.state.maxPerDay) return this._decision('daily_limit', `今日主动提醒已达到 ${this.state.maxPerDay} 次上限。`, at, { sentToday });
    const recent = this.state.history.filter(value => at - value < HOUR_MS && at >= value);
    if (this.state.maxPerHour === 0 || recent.length >= this.state.maxPerHour) {
      return this._decision('hourly_limit', `每小时最多提醒 ${this.state.maxPerHour} 次，请稍后再试。`, at, { sentThisHour: recent.length });
    }
    if (normalizedKey) {
      const last = this.state.byKey[normalizedKey] || 0;
      if (last > 0 && at - last < this.state.dedupeMinutes * 60 * 1000) {
        return this._decision('duplicate', `相同提醒在 ${this.state.dedupeMinutes} 分钟内不重复发送。`, at, { lastSentAt: last });
      }
    }
    return this._decision('allowed', '当前允许发送主动提醒。', at, { sentToday, sentThisHour: recent.length });
  }

  attempt(key = '', timestamp = this.now()) {
    const decision = this.explain(key, timestamp);
    if (!decision.allowed) {
      this.state.lastSuppression = { reason: decision.reason, message: decision.message, at: decision.at };
      this._save();
      return { ...decision, snapshot: this.snapshot(decision.at) };
    }
    const normalizedKey = String(key || '').slice(0, 160);
    this.state.history.push(decision.at);
    if (normalizedKey) this.state.byKey[normalizedKey] = decision.at;
    this.state.lastSuppression = null;
    this._prune(decision.at);
    this._save();
    return { ...decision, snapshot: this.snapshot(decision.at) };
  }

  update(patch = {}) {
    for (const key of ['paused', 'focusActive']) if (patch[key] !== undefined) this.state[key] = Boolean(patch[key]);
    for (const key of ['quietStart', 'quietEnd', 'maxPerHour', 'maxPerDay', 'dedupeMinutes']) {
      if (patch[key] !== undefined) this.state[key] = patch[key];
    }
    if (patch.mutedUntil !== undefined) this.state.mutedUntil = Math.max(0, Math.round(number(patch.mutedUntil, 0)));
    this._normalizeSettings();
    this._save();
    return this.snapshot();
  }

  mute(durationMs = 60 * 60 * 1000) {
    const duration = Math.max(60_000, Math.min(7 * DAY_MS, Math.round(number(durationMs, 60 * 60 * 1000))));
    this.state.mutedUntil = Math.max(this.state.mutedUntil, this.now() + duration);
    this._save();
    return this.snapshot();
  }

  snapshot(timestamp = this.now()) {
    const at = number(timestamp, this.now());
    this._prune(at);
    const today = dayKey(at, this.timeZone);
    const recent = this.state.history.filter(value => at - value < HOUR_MS && at >= value);
    return {
      version: this.state.version,
      paused: this.state.paused,
      focusActive: this.state.focusActive,
      quietStart: this.state.quietStart,
      quietEnd: this.state.quietEnd,
      maxPerHour: this.state.maxPerHour,
      maxPerDay: this.state.maxPerDay,
      dedupeMinutes: this.state.dedupeMinutes,
      mutedUntil: this.state.mutedUntil,
      sentToday: this.state.history.filter(value => dayKey(value, this.timeZone) === today).length,
      sentThisHour: recent.length,
      remainingToday: Math.max(0, this.state.maxPerDay - this.state.history.filter(value => dayKey(value, this.timeZone) === today).length),
      lastSuppression: this.state.lastSuppression,
    };
  }
}

module.exports = { ProactivePolicy, dayKey, quietHours };
