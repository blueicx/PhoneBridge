'use strict';

const CLUE_TYPES = Object.freeze(['location', 'object', 'light']);
const DAY_MS = 24 * 60 * 60 * 1000;
const MAX_PROCESSED_EVENTS = 2048;
const MAX_DAILY_DATES = 14;
const OFFLINE_MAX_AGE_DAYS = 7;
const TIME_ZONE = 'Asia/Shanghai';

function clone(value) { return value == null ? value : JSON.parse(JSON.stringify(value)); }

function dayKey(at) {
  const date = new Date(Number(at));
  if (!Number.isFinite(date.getTime())) throw new Error('invalid activity time');
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date).reduce((result, part) => {
    if (part.type !== 'literal') result[part.type] = part.value;
    return result;
  }, {});
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function normalizeRegion(value) {
  const region = String(value || '').trim();
  if (region === 'camera') return region;
  if (!/^cell:-?\d+:-?\d+$/.test(region)) throw new Error('coarse region is required');
  return region;
}

function eventParts(eventId) {
  const value = String(eventId || '');
  const dated = value.match(/^reality(?:-lens)?:v2:(\d{4}-\d{2}-\d{2}):((?:cell:-?\d+:-?\d+)|camera):([a-z]+)(?::.*)?$/i);
  if (dated) return { version: 2, activityDate: dated[1], region: dated[2], clueType: dated[3].toLowerCase() };
  const legacy = value.match(/^reality(?:-lens)?:((?:cell:-?\d+:-?\d+)|camera):/i);
  return legacy ? { version: 1, activityDate: null, region: legacy[1] } : null;
}

function eventRegion(eventId) {
  return eventParts(eventId)?.region || null;
}

function eventActivityDate(eventId) {
  return eventParts(eventId)?.activityDate || null;
}

function validateEventId(value) {
  const eventId = String(value || '').trim();
  if (!eventId || eventId.length > 180) throw new Error('eventId is required');
  if (/[-+]?\d+\.\d+/.test(eventId)) throw new Error('precise location is not allowed');
  if (!eventId.startsWith('reality:') && !eventId.startsWith('reality-lens:')) throw new Error('invalid reality event');
  if (!eventParts(eventId)) throw new Error('invalid reality event');
  return eventId;
}

function buildClueEventId({ activityAt, region, clueType, nonce = 'event' } = {}) {
  const normalizedRegion = normalizeRegion(region);
  const type = String(clueType || '').trim().toLowerCase();
  if (!CLUE_TYPES.includes(type)) throw new Error('invalid clueType');
  const safeNonce = String(nonce || 'event').trim().replace(/[^a-zA-Z0-9._~-]/g, '_').slice(0, 48) || 'event';
  return `reality-lens:v2:${dayKey(activityAt)}:${normalizedRegion}:${type}:${safeNonce}`;
}

function emptyDaily(date) {
  return { date, clues: { location: 0, object: 0, light: 0 }, completed: false };
}

function initialState(at) {
  const date = dayKey(at);
  return {
    version: 2,
    xp: 0,
    level: 1,
    region: null,
    daily: emptyDaily(date),
    dailyByDate: { [date]: emptyDaily(date) },
    boosts: [],
    seenEventIds: [],
    processedEvents: [],
    revision: 0,
    updatedAt: Number(at),
  };
}

function normalizeDaily(value, fallbackDate) {
  const date = String(value?.date || fallbackDate);
  return {
    date,
    clues: Object.fromEntries(CLUE_TYPES.map(type => [type, Number(value?.clues?.[type]) ? 1 : 0])),
    completed: Boolean(value?.completed),
  };
}

class MoteGrowthStore {
  constructor({ now = () => Date.now(), persistence = null } = {}) {
    this.now = now;
    this.persistence = persistence;
    this.state = initialState(this.now());
    this._load();
  }

  _load() {
    const saved = this.persistence?.load?.('mote-growth', null);
    if (!saved || typeof saved !== 'object') return;
    const defaults = initialState(this.now());
    const currentDate = dayKey(this.now());
    const dailyByDate = {};
    if (saved.dailyByDate && typeof saved.dailyByDate === 'object') {
      for (const [date, daily] of Object.entries(saved.dailyByDate)) {
        if (/^\d{4}-\d{2}-\d{2}$/.test(date)) dailyByDate[date] = normalizeDaily(daily, date);
      }
    }
    // Version 1 had only one daily bucket. Preserve it under its original date.
    if (Object.keys(dailyByDate).length === 0 && saved.daily) {
      const legacyDaily = normalizeDaily(saved.daily, currentDate);
      dailyByDate[legacyDaily.date] = legacyDaily;
    }
    if (!dailyByDate[currentDate]) dailyByDate[currentDate] = emptyDaily(currentDate);
    const processedEvents = Array.isArray(saved.processedEvents)
      ? saved.processedEvents.filter(item => item && item.eventId).slice(-MAX_PROCESSED_EVENTS).map(item => ({
        eventId: String(item.eventId),
        businessStatus: ['accepted', 'duplicate', 'rejected'].includes(item.businessStatus) ? item.businessStatus : 'duplicate',
        reason: String(item.reason || 'event_already_processed'),
        revision: Number(item.revision) || 0,
        activityDate: item.activityDate || eventActivityDate(item.eventId) || currentDate,
        region: item.region || eventRegion(item.eventId),
        clueType: item.clueType || null,
        reward: clone(item.reward || { xp: 0, dailyCompleted: false, boost: null }),
        createdAt: Number(item.createdAt) || Number(this.now()),
      }))
      : [];
    const migratedSeen = Array.isArray(saved.seenEventIds) ? saved.seenEventIds.map(String).slice(-MAX_PROCESSED_EVENTS) : [];
    const known = new Set(processedEvents.map(item => item.eventId));
    for (const eventId of migratedSeen) {
      if (known.has(eventId)) continue;
      processedEvents.push({
        eventId,
        businessStatus: 'duplicate',
        reason: 'legacy_event_already_rewarded',
        revision: 0,
        activityDate: eventActivityDate(eventId) || currentDate,
        region: eventRegion(eventId),
        clueType: null,
        reward: { xp: 0, dailyCompleted: false, boost: null },
        createdAt: Number(saved.updatedAt) || Number(this.now()),
      });
    }
    this.state = {
      ...defaults,
      ...saved,
      version: 2,
      dailyByDate,
      daily: normalizeDaily(dailyByDate[currentDate], currentDate),
      boosts: Array.isArray(saved.boosts) ? saved.boosts.slice(-12) : [],
      seenEventIds: migratedSeen.slice(-MAX_PROCESSED_EVENTS),
      processedEvents: processedEvents.slice(-MAX_PROCESSED_EVENTS),
      revision: Number(saved.revision) || processedEvents.reduce((max, item) => Math.max(max, Number(item.revision) || 0), 0),
    };
    this._pruneDaily(currentDate);
  }

  _save() { return this.persistence?.save?.('mote-growth', this.state); }

  _pruneDaily(currentDate) {
    const entries = Object.entries(this.state.dailyByDate || {}).sort(([left], [right]) => left.localeCompare(right));
    const retained = entries.slice(-MAX_DAILY_DATES);
    this.state.dailyByDate = Object.fromEntries(retained);
    this.state.daily = normalizeDaily(this.state.dailyByDate[currentDate] || emptyDaily(currentDate), currentDate);
  }

  _rotateDay(at) {
    const today = dayKey(at);
    if (!this.state.dailyByDate[today]) this.state.dailyByDate[today] = emptyDaily(today);
    this.state.daily = normalizeDaily(this.state.dailyByDate[today], today);
    this.state.boosts = this.state.boosts.filter(boost => Number(boost.expiresAt) > Number(at));
    this._pruneDaily(today);
    this.state.updatedAt = Number(at);
  }

  static validatePayload({ eventId, clueType, region, activityAt } = {}) {
    const id = validateEventId(eventId);
    const type = String(clueType || '').trim().toLowerCase();
    if (!CLUE_TYPES.includes(type)) throw new Error('invalid clueType');
    const normalizedRegion = normalizeRegion(region);
    const parts = eventParts(id);
    if (!parts || parts.region !== normalizedRegion) throw new Error('event region does not match payload region');
    if (parts.clueType && parts.clueType !== type) throw new Error('clue type does not match event');
    const timestamp = activityAt == null ? null : Number(activityAt);
    if (timestamp != null && !Number.isFinite(timestamp)) throw new Error('invalid activity time');
    if (parts.activityDate && timestamp != null && dayKey(timestamp) !== parts.activityDate) {
      throw new Error('event activity date does not match payload');
    }
    return {
      eventId: id,
      clueType: type,
      region: normalizedRegion,
      activityAt: timestamp,
      eventActivityDate: parts.activityDate,
    };
  }

  static buildClueEventId(options) {
    return buildClueEventId(options);
  }

  _receiptFor(eventId) {
    return this.state.processedEvents.find(item => item.eventId === eventId) || null;
  }

  getReceipt(eventId) {
    const receipt = this._receiptFor(String(eventId || ''));
    return receipt ? clone(receipt) : null;
  }

  _result(receipt, { state = this.snapshot() } = {}) {
    return {
      duplicate: receipt.businessStatus === 'duplicate',
      businessStatus: receipt.businessStatus,
      reason: receipt.reason,
      revision: receipt.revision,
      activityDate: receipt.activityDate,
      reward: clone(receipt.reward),
      receipt: clone(receipt),
      state,
    };
  }

  recordClue(payload = {}) {
    const normalized = MoteGrowthStore.validatePayload(payload);
    const now = Number(this.now());
    const activityAt = normalized.activityAt == null ? now : normalized.activityAt;
    const activityDate = normalized.eventActivityDate || dayKey(activityAt);
    const previousState = clone(this.state);
    const existing = this._receiptFor(normalized.eventId);
    if (existing) {
      const replay = {
        ...existing,
        businessStatus: 'duplicate',
        reason: 'event_already_processed',
        reward: { xp: 0, dailyCompleted: false, boost: null },
      };
      return this._result(replay);
    }

    this._rotateDay(now);
    const age = now - activityAt;
    if (payload.offline === true && (age < -DAY_MS || age > OFFLINE_MAX_AGE_DAYS * DAY_MS)) {
      const receipt = this._recordReceipt({
        eventId: normalized.eventId,
        businessStatus: 'rejected',
        reason: 'offline_event_expired',
        activityDate,
        region: normalized.region,
        clueType: normalized.clueType,
        reward: { xp: 0, dailyCompleted: false, boost: null },
        createdAt: now,
      });
      try { this._save(); } catch (error) { this.state = previousState; throw error; }
      return this._result(receipt);
    }

    if (!this.state.dailyByDate[activityDate]) this.state.dailyByDate[activityDate] = emptyDaily(activityDate);
    const daily = this.state.dailyByDate[activityDate];
    if (daily.clues[normalized.clueType] >= 1) {
      const receipt = this._recordReceipt({
        eventId: normalized.eventId,
        businessStatus: 'duplicate',
        reason: 'clue_type_already_collected_today',
        activityDate,
        region: normalized.region,
        clueType: normalized.clueType,
        reward: { xp: 0, dailyCompleted: false, boost: null },
        createdAt: now,
      });
      try { this._save(); } catch (error) { this.state = previousState; throw error; }
      return this._result(receipt);
    }

    this.state.region = normalized.region;
    this.state.seenEventIds.push(normalized.eventId);
    this.state.seenEventIds = this.state.seenEventIds.slice(-MAX_PROCESSED_EVENTS);
    daily.clues[normalized.clueType] = 1;
    const baseXp = 2;
    this.state.xp += baseXp;
    let dailyCompleted = false;
    let boost = null;
    if (!daily.completed && CLUE_TYPES.every(type => daily.clues[type] >= 1)) {
      daily.completed = true;
      dailyCompleted = true;
      this.state.xp += 10;
      boost = { id: 'field-focus', multiplier: 1.1, expiresAt: now + 30 * 60 * 1000, sourceDate: activityDate };
      this.state.boosts = [...this.state.boosts.filter(item => item.id !== boost.id), boost].slice(-12);
    }
    this.state.level = Math.max(1, Math.floor(this.state.xp / 50) + 1);
    this.state.daily = normalizeDaily(this.state.dailyByDate[dayKey(now)], dayKey(now));
    this.state.updatedAt = now;
    const receipt = this._recordReceipt({
      eventId: normalized.eventId,
      businessStatus: 'accepted',
      reason: 'reward_applied',
      activityDate,
      region: normalized.region,
      clueType: normalized.clueType,
      reward: { xp: baseXp + (dailyCompleted ? 10 : 0), dailyCompleted, boost },
      createdAt: now,
    });
    try {
      this._save();
    } catch (error) {
      this.state = previousState;
      throw error;
    }
    return this._result(receipt);
  }

  _recordReceipt({ eventId, businessStatus, reason, activityDate, region, clueType, reward, createdAt }) {
    const receipt = {
      eventId,
      businessStatus,
      reason,
      revision: ++this.state.revision,
      activityDate,
      region,
      clueType,
      reward: clone(reward),
      createdAt,
    };
    this.state.processedEvents.push(receipt);
    this.state.processedEvents = this.state.processedEvents.slice(-MAX_PROCESSED_EVENTS);
    return receipt;
  }

  snapshot() {
    const at = this.now();
    this._rotateDay(at);
    return clone({
      ...this.state,
      dailyByDate: this.state.dailyByDate,
      daily: this.state.daily,
      boosts: this.state.boosts.filter(boost => Number(boost.expiresAt) > Number(at)),
    });
  }
}

module.exports = {
  MoteGrowthStore,
  CLUE_TYPES,
  DAY_MS,
  OFFLINE_MAX_AGE_DAYS,
  TIME_ZONE,
  dayKey,
  normalizeRegion,
  validateEventId,
  eventRegion,
  eventActivityDate,
  buildClueEventId,
};
