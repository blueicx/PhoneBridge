'use strict';

const CLUE_TYPES = Object.freeze(['location', 'object', 'light']);
const DAY_MS = 24 * 60 * 60 * 1000;

function clone(value) { return value == null ? value : JSON.parse(JSON.stringify(value)); }
function dayKey(at) { return new Date(Number(at)).toISOString().slice(0, 10); }

function normalizeRegion(value) {
  const region = String(value || '').trim();
  if (region === 'camera') return region;
  if (!/^cell:-?\d+:-?\d+$/.test(region)) throw new Error('coarse region is required');
  return region;
}

function eventRegion(eventId) {
  const match = String(eventId || '').match(/^reality(?:-lens)?:((?:cell:-?\d+:-?\d+)|camera):/);
  return match ? match[1] : null;
}

function validateEventId(value) {
  const eventId = String(value || '').trim();
  if (!eventId || eventId.length > 180) throw new Error('eventId is required');
  if (/[-+]?\d+\.\d+/.test(eventId)) throw new Error('precise location is not allowed');
  if (!eventId.startsWith('reality:') && !eventId.startsWith('reality-lens:')) throw new Error('invalid reality event');
  return eventId;
}

function initialState(at) {
  return {
    version: 1,
    xp: 0,
    level: 1,
    region: null,
    daily: { date: dayKey(at), clues: { location: 0, object: 0, light: 0 }, completed: false },
    boosts: [],
    seenEventIds: [],
    updatedAt: Number(at),
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
    this.state = {
      ...initialState(this.now()),
      ...saved,
      daily: {
        ...initialState(this.now()).daily,
        ...(saved.daily || {}),
        clues: { ...initialState(this.now()).daily.clues, ...(saved.daily?.clues || {}) },
      },
      boosts: Array.isArray(saved.boosts) ? saved.boosts.slice(-12) : [],
      seenEventIds: Array.isArray(saved.seenEventIds) ? saved.seenEventIds.slice(-512) : [],
    };
  }

  _save() { this.persistence?.save?.('mote-growth', this.state); }

  _rotateDay(at) {
    const today = dayKey(at);
    if (this.state.daily.date === today) return;
    this.state.daily = { date: today, clues: { location: 0, object: 0, light: 0 }, completed: false };
    this.state.boosts = this.state.boosts.filter(boost => Number(boost.expiresAt) > Number(at));
    this.state.updatedAt = Number(at);
    this._save();
  }

  static validatePayload({ eventId, clueType, region } = {}) {
    const id = validateEventId(eventId);
    const type = String(clueType || '').trim().toLowerCase();
    if (!CLUE_TYPES.includes(type)) throw new Error('invalid clueType');
    const normalizedRegion = normalizeRegion(region);
    const payloadRegion = eventRegion(id);
    if (!payloadRegion || payloadRegion !== normalizedRegion) {
      throw new Error('event region does not match payload region');
    }
    return { eventId: id, clueType: type, region: normalizedRegion };
  }

  recordClue(payload = {}) {
    const normalized = MoteGrowthStore.validatePayload(payload);
    const at = this.now();
    this._rotateDay(at);
    if (this.state.seenEventIds.includes(normalized.eventId)) {
      return { duplicate: true, reward: { xp: 0, dailyCompleted: false, boost: null }, state: this.snapshot() };
    }
    this.state.region = normalized.region;
    this.state.seenEventIds.push(normalized.eventId);
    this.state.seenEventIds = this.state.seenEventIds.slice(-512);
    this.state.daily.clues[normalized.clueType] = Math.min(1, Number(this.state.daily.clues[normalized.clueType] || 0) + 1);
    const baseXp = 2;
    this.state.xp += baseXp;
    let dailyCompleted = false;
    let boost = null;
    if (!this.state.daily.completed && CLUE_TYPES.every(type => this.state.daily.clues[type] >= 1)) {
      this.state.daily.completed = true;
      dailyCompleted = true;
      this.state.xp += 10;
      boost = { id: 'field-focus', multiplier: 1.1, expiresAt: at + 30 * 60 * 1000 };
      this.state.boosts = [...this.state.boosts.filter(item => item.id !== boost.id), boost].slice(-12);
    }
    this.state.level = Math.max(1, Math.floor(this.state.xp / 50) + 1);
    this.state.updatedAt = at;
    this._save();
    return { duplicate: false, reward: { xp: baseXp + (dailyCompleted ? 10 : 0), dailyCompleted, boost }, state: this.snapshot() };
  }

  snapshot() {
    const at = this.now();
    this._rotateDay(at);
    return clone({ ...this.state, boosts: this.state.boosts.filter(boost => Number(boost.expiresAt) > Number(at)) });
  }
}

module.exports = { MoteGrowthStore, CLUE_TYPES, normalizeRegion, validateEventId, DAY_MS };
