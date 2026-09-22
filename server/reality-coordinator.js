'use strict';

const { MoteGrowthStore } = require('./mote-growth');

function createRealityCoordinator({
  moteStore,
  moteGrowthStore,
  realityEngine,
  broadcastMoteState = () => {},
  now = () => Date.now(),
} = {}) {
  if (!moteStore || !moteGrowthStore || !realityEngine) throw new TypeError('reality stores are required');

  function syncBoosts() {
    realityEngine.setBoosts(moteGrowthStore.snapshot().boosts);
  }

  function applyMoteClue(payload = {}) {
    validateGeneratedRealityEvent(payload);
    const growthEligible = Boolean(payload?.region)
      || String(payload?.eventId || '').startsWith('reality:')
      || String(payload?.eventId || '').startsWith('reality-lens:');
    if (growthEligible) MoteGrowthStore.validatePayload({ ...payload, region: payload.region || 'camera' });
    const result = moteStore.collectClue({ eventId: payload.eventId, clueType: payload.clueType });
    result.growth = !growthEligible
      ? { duplicate: result.duplicate, reward: { xp: 0, dailyCompleted: false, boost: null }, state: moteGrowthStore.snapshot() }
      : result.duplicate
        ? {
          duplicate: true,
          businessStatus: 'duplicate',
          reason: 'event_already_processed',
          reward: { xp: 0, dailyCompleted: false, boost: null },
          state: moteGrowthStore.snapshot(),
        }
        : moteGrowthStore.recordClue({
          eventId: payload.eventId,
          clueType: payload.clueType,
          region: payload.region || 'camera',
          activityAt: payload.activityAt,
          offline: payload.offline === true,
        });
    if (!result.duplicate && result.growth?.businessStatus !== 'rejected') broadcastMoteState();
    result.businessStatus = result.growth?.businessStatus || (result.duplicate ? 'duplicate' : 'accepted');
    result.reason = result.growth?.reason || (result.duplicate ? 'event_already_processed' : 'clue_collected');
    result.resultRevision = result.growth?.revision || 0;
    syncBoosts();
    return result;
  }

  function validateGeneratedRealityEvent(payload = {}) {
    const eventId = String(payload.eventId || '');
    if (!eventId.startsWith('reality:v2:')) return;
    if (typeof realityEngine.eventsFor !== 'function') throw new Error('reality event validation unavailable');
    const activityAt = payload.offline === true ? Number(payload.activityAt) : Number(now());
    if (!Number.isFinite(activityAt)) throw new Error('invalid activity time');
    const event = realityEngine.eventsFor(payload.region, activityAt).find(item => item.id === eventId);
    if (!event || Number(event.expiresAt) <= activityAt) throw new Error('reality event is expired or invalid');
    if (String(payload.clueType || '').toLowerCase() !== String(event.clueType).toLowerCase()) {
      throw new Error('clue type does not match event');
    }
  }

  function buildProgress() {
    const reality = realityEngine.snapshot();
    const growth = moteGrowthStore.snapshot();
    return {
      revision: Math.max(Number(growth.revision) || 0, Number(reality.updatedAt) || 0),
      growth,
      inventory: reality.inventory,
      loadout: reality.loadout,
      habitat: reality.habitat,
      daily: growth.daily,
      dailyByDate: growth.dailyByDate,
      boosts: growth.boosts,
      level: growth.level,
      xp: growth.xp,
      region: reality.region || growth.region || null,
    };
  }

  return { applyMoteClue, buildProgress, syncBoosts };
}

module.exports = { createRealityCoordinator };
