'use strict';

const { MOTE_PROFILES } = require('./mote-profiles');
const { CLUE_TYPES } = require('./mote-growth');
const { ITEMS } = require('./reality-engine');

const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 100;
const MAX_CURSOR_LENGTH = 512;
const REGION_PATTERN = /^(?:camera|cell:-?\d+:-?\d+)$/;
const V2_EVENT_ID_PATTERN = /^reality(?:-lens)?:v2:\d{4}-\d{2}-\d{2}:(camera|cell:-?\d+:-?\d+):(location|object|light):[A-Za-z0-9._~-]{1,48}(?::[A-Za-z0-9._~-]{1,48})?$/;
const LEGACY_EVENT_ID_PATTERN = /^reality(?:-lens)?:((?:cell:-?\d+:-?\d+)|camera):(location|object|light)(?::[A-Za-z0-9._~-]{1,48})?$/;
const OBSERVATIONS = Object.freeze({
  location: '一起确认周围的方位线索。',
  object: '一起留意可观察的物体线索。',
  light: '一起观察光线的细微变化。',
});
const PROFILE_BY_ID = new Map(MOTE_PROFILES.map(profile => [profile.id, profile]));
const REALITY_ITEM_IDS = new Set(ITEMS.map(item => item.id));

function invalidInput(code, message) {
  const error = new Error(message);
  error.code = code;
  return error;
}

function listReceipts(store, name) {
  if (!store || typeof store.listReceipts !== 'function') {
    throw new TypeError(`${name} receipt accessor is required`);
  }
  const receipts = store.listReceipts();
  return Array.isArray(receipts) ? receipts : [];
}

function parseLimit(value) {
  if (value == null) return DEFAULT_LIMIT;
  const text = String(value);
  if (!/^[1-9]\d{0,2}$/.test(text)) throw invalidInput('invalid_limit', 'limit must be an integer from 1 to 100');
  const limit = Number(text);
  if (limit > MAX_LIMIT) throw invalidInput('invalid_limit', 'limit must be an integer from 1 to 100');
  return limit;
}

function parseEventId(value) {
  const eventId = String(value || '');
  if (!eventId || eventId.length > 180) return null;
  const match = V2_EVENT_ID_PATTERN.exec(eventId) || LEGACY_EVENT_ID_PATTERN.exec(eventId);
  return match ? { region: match[1], clueType: match[2] } : null;
}

function readCursor(value, entries) {
  if (value == null) return null;
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_CURSOR_LENGTH || !/^[A-Za-z0-9_-]+$/.test(value)) {
    throw invalidInput('invalid_cursor', 'cursor is invalid');
  }

  let decoded;
  try {
    const json = Buffer.from(value, 'base64url').toString('utf8');
    if (Buffer.from(json, 'utf8').toString('base64url') !== value) throw new Error('non-canonical cursor');
    decoded = JSON.parse(json);
  } catch (_) {
    throw invalidInput('invalid_cursor', 'cursor is invalid');
  }
  if (!decoded || typeof decoded !== 'object' || Array.isArray(decoded)
    || Object.keys(decoded).sort().join(',') !== 'eventId,occurredAt'
    || !Number.isSafeInteger(decoded.occurredAt) || decoded.occurredAt < 0
    || typeof decoded.eventId !== 'string' || !parseEventId(decoded.eventId)) {
    throw invalidInput('invalid_cursor', 'cursor is invalid');
  }

  const index = entries.findIndex(entry => entry.eventId === decoded.eventId && entry.occurredAt === decoded.occurredAt);
  if (index < 0) throw invalidInput('invalid_cursor', 'cursor is no longer available');
  return index;
}

function receiptShape(receipt, source) {
  if (!receipt || typeof receipt !== 'object') return null;
  const eventId = String(receipt.eventId || '');
  const region = String(receipt.region || '');
  const clueType = String(receipt.clueType || '').toLowerCase();
  const timeValue = source === 'reality' ? (receipt.resolvedAt ?? receipt.occurredAt) : (receipt.createdAt ?? receipt.occurredAt);
  const eventParts = parseEventId(eventId);
  if (!eventParts || !REGION_PATTERN.test(region) || !CLUE_TYPES.includes(clueType)
    || eventParts.region !== region || eventParts.clueType !== clueType
    || typeof timeValue !== 'number' || !Number.isSafeInteger(timeValue) || timeValue < 0) return null;
  const occurredAt = timeValue;
  return { eventId, region, clueType, occurredAt };
}

function positiveInteger(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? Math.min(1_000_000, Math.trunc(number)) : 0;
}

function safeRewardItem(receipt) {
  const itemId = String(receipt?.reward?.itemId || '');
  const amount = positiveInteger(receipt?.reward?.amount);
  if (!REALITY_ITEM_IDS.has(itemId) || amount === 0) return [];
  return [{ id: itemId, amount }];
}

function safeBoost(receipt) {
  const boost = receipt?.reward?.boost;
  if (boost?.id !== 'field-focus') return null;
  const expiresAt = Number(boost.expiresAt);
  const multiplier = Number(boost.multiplier);
  if (!Number.isSafeInteger(expiresAt) || expiresAt < 0 || !Number.isFinite(multiplier) || multiplier < 1 || multiplier > 2) return null;
  return { id: 'field-focus', expiresAt, multiplier };
}

function activeProfile(moteProfiles) {
  const activeId = Array.isArray(moteProfiles)
    ? MOTE_PROFILES.find(profile => profile.initial)?.id
    : String(moteProfiles?.activeId || '');
  const supplied = Array.isArray(moteProfiles) ? moteProfiles : moteProfiles?.profiles;
  const suppliedIds = Array.isArray(supplied) ? new Set(supplied.map(profile => String(profile?.id || ''))) : null;
  if (activeId && suppliedIds?.has(activeId) && PROFILE_BY_ID.has(activeId)) return PROFILE_BY_ID.get(activeId);
  return PROFILE_BY_ID.get('mote');
}

function compareEntriesDescending(left, right) {
  if (left.occurredAt !== right.occurredAt) return right.occurredAt - left.occurredAt;
  if (left.eventId === right.eventId) return 0;
  return left.eventId < right.eventId ? 1 : -1;
}

function cursorFor(entry) {
  return Buffer.from(JSON.stringify({ occurredAt: entry.occurredAt, eventId: entry.eventId }), 'utf8').toString('base64url');
}

function buildRealityLog({ growthStore, realityEngine, moteProfiles, cursor = null, limit = undefined } = {}) {
  const parsedLimit = parseLimit(limit);
  const profile = activeProfile(moteProfiles);
  const byEvent = new Map();

  function merge(source, receipt) {
    const shape = receiptShape(receipt, source);
    if (!shape) return;
    if (source === 'growth' && (receipt.businessStatus !== 'accepted' || positiveInteger(receipt.reward?.xp) === 0)) return;
    if (source === 'reality' && !receipt.reward) return;

    let entry = byEvent.get(shape.eventId);
    if (!entry) {
      entry = { ...shape, growthReceipt: null, realityReceipt: null };
      byEvent.set(shape.eventId, entry);
    } else if (entry.region !== shape.region || entry.clueType !== shape.clueType) {
      return;
    }
    const key = source === 'growth' ? 'growthReceipt' : 'realityReceipt';
    if (entry[key]) return;
    entry[key] = receipt;
    entry.occurredAt = Math.max(entry.occurredAt, shape.occurredAt);
  }

  for (const receipt of listReceipts(growthStore, 'growthStore')) merge('growth', receipt);
  for (const receipt of listReceipts(realityEngine, 'realityEngine')) merge('reality', receipt);

  const entries = [...byEvent.values()].map(entry => {
    const reward = {
      xp: positiveInteger(entry.growthReceipt?.reward?.xp) + positiveInteger(entry.realityReceipt?.reward?.xp),
      items: safeRewardItem(entry.realityReceipt),
      boost: safeBoost(entry.growthReceipt),
    };
    return {
      eventId: entry.eventId,
      status: 'confirmed',
      occurredAt: entry.occurredAt,
      coarseRegion: entry.region,
      clueType: entry.clueType,
      moteId: profile.id,
      observation: `${profile.name}：${OBSERVATIONS[entry.clueType]}`,
      reward,
    };
  }).sort(compareEntriesDescending);

  const cursorIndex = readCursor(cursor, entries);
  const startIndex = cursorIndex == null ? 0 : cursorIndex + 1;
  const page = entries.slice(startIndex, startIndex + parsedLimit);
  const hasMore = startIndex + page.length < entries.length;
  return {
    entries: page,
    nextCursor: hasMore && page.length ? cursorFor(page[page.length - 1]) : null,
  };
}

module.exports = { buildRealityLog, DEFAULT_LIMIT, MAX_LIMIT };
