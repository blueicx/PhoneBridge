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
    const eventId = String(payload?.eventId || '');
    const generatedEvent = eventId.startsWith('reality:v2:');
    const priorRealityReceipt = generatedEvent ? realityEngine.getReceipt?.(eventId) || null : null;
    const replayPayload = priorRealityReceipt
      ? { ...payload, activityAt: priorRealityReceipt.resolvedAt, offline: false }
      : payload;
    validateGeneratedRealityEvent(payload, priorRealityReceipt);
    const growthEligible = Boolean(payload?.region)
      || eventId.startsWith('reality:')
      || eventId.startsWith('reality-lens:');
    if (growthEligible) MoteGrowthStore.validatePayload({ ...replayPayload, region: payload.region || 'camera' });
    const activityAt = replayPayload.offline === true ? Number(replayPayload.activityAt) : Number(now());
    const realityReward = generatedEvent
      ? realityEngine.resolve({
        eventId: payload.eventId,
        region: payload.region,
        clueType: payload.clueType,
        actions: payload.actions,
        at: activityAt,
      })
      : null;
    const moteState = typeof moteStore.getState === 'function' ? moteStore.getState() : null;
    const hasExplorationTarget = moteState == null || Boolean(moteState.exploration?.targetId);
    const moteResult = hasExplorationTarget
      ? moteStore.collectClue({ eventId: payload.eventId, clueType: payload.clueType })
      : { duplicate: false, unlockedId: null, state: moteState };
    const result = { ...moteResult, mote: moteResult };
    result.reality = realityReward;
    if (realityReward) {
      result.reward = realityReward.reward;
      result.event = realityReward.event;
      result.state = realityReward.state;
    }
    // Each ledger owns its own event-idempotency receipt. Always replay all
    // ledgers: a prior ledger may have committed before a later one failed.
    result.growth = !growthEligible
      ? { duplicate: result.duplicate, reward: { xp: 0, dailyCompleted: false, boost: null }, state: moteGrowthStore.snapshot() }
      : moteGrowthStore.recordClue({
          eventId: payload.eventId,
          clueType: payload.clueType,
          region: payload.region || 'camera',
          activityAt: replayPayload.activityAt,
          offline: replayPayload.offline === true,
        });
    const realityDuplicate = !realityReward || realityReward.duplicate === true;
    const moteDuplicate = !hasExplorationTarget || moteResult.duplicate === true;
    const growthDuplicate = !growthEligible || result.growth.duplicate === true || result.growth.businessStatus === 'duplicate';
    result.duplicate = realityDuplicate && moteDuplicate && growthDuplicate;
    const rejected = result.growth?.businessStatus === 'rejected';
    if (!rejected && !result.duplicate) broadcastMoteState();
    result.businessStatus = rejected ? 'rejected' : result.duplicate ? 'duplicate' : 'accepted';
    result.reason = result.growth?.reason || (result.duplicate ? 'event_already_processed' : 'clue_collected');
    result.resultRevision = result.growth?.revision || 0;
    syncBoosts();
    return result;
  }

  function validateGeneratedRealityEvent(payload = {}, priorReceipt = null) {
    const eventId = String(payload.eventId || '');
    if (!eventId.startsWith('reality:v2:')) return;
    if (priorReceipt) {
      if (String(priorReceipt.region || '') !== String(payload.region || '')
        || String(priorReceipt.clueType || '').toLowerCase() !== String(payload.clueType || '').toLowerCase()) {
        throw new Error('replay does not match the accepted reality receipt');
      }
      return;
    }
    if (typeof realityEngine.eventsFor !== 'function') throw new Error('reality event validation unavailable');
    const currentAt = Number(now());
    if (!Number.isFinite(currentAt)) throw new Error('invalid server time');
    const event = realityEngine.eventsFor(payload.region, currentAt).find(item => item.id === eventId);
    if (!event || Number(event.expiresAt) <= currentAt) throw new Error('reality event is expired or invalid');
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
