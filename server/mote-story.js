'use strict';

const STORY_VERSION = 1;
const MAX_SEEN_EVENTS = 1024;

const MOTE_STORY_EVENTS = Object.freeze([
  { id: 'first-awakening', title: '星火初醒', description: '第一次把一个 Mote 带到舞台中央。', trigger: 'activation', reward: { xp: 5 } },
  { id: 'first-conversation', title: '互相听见', description: '与 Mote 完成第一次对话。', trigger: 'conversation', reward: { xp: 5 } },
  { id: 'first-task', title: '并肩起步', description: '第一次把任务交给 Mote 并顺利完成。', trigger: 'task_success', reward: { xp: 8 } },
  { id: 'first-exploration', title: '门外有风', description: '第一次开始现实探索。', trigger: 'exploration', reward: { xp: 5 } },
  { id: 'first-location-clue', title: '地点回声', description: '收集第一枚地点线索碎片。', trigger: 'clue_location', reward: { xp: 6 } },
  { id: 'first-object-clue', title: '物体注脚', description: '收集第一枚物体线索碎片。', trigger: 'clue_object', reward: { xp: 6 } },
  { id: 'first-light-clue', title: '追光而行', description: '收集第一枚光线线索碎片。', trigger: 'clue_light', reward: { xp: 6 } },
  { id: 'first-unlock', title: '新形态的名字', description: '完成一只探索 Mote 的解锁。', trigger: 'mote_unlock', reward: { xp: 12 } },
  { id: 'relationship-two', title: '熟悉的默契', description: '关系等级达到 2。', trigger: 'relationship_2', reward: { xp: 10 } },
  { id: 'relationship-three', title: '把心交给你', description: '关系等级达到 3。', trigger: 'relationship_3', reward: { xp: 15 } },
  { id: 'first-boost', title: '短暂的风', description: '第一次获得现实探索增益。', trigger: 'boost', reward: { xp: 8 } },
  { id: 'task-recovery', title: '失败之后仍在', description: '一次失败或中断的任务完成恢复。', trigger: 'task_recovery', reward: { xp: 12 } },
]);

function clone(value) { return value == null ? value : JSON.parse(JSON.stringify(value)); }

function initialState(now) {
  return {
    version: STORY_VERSION,
    completed: [],
    claimed: [],
    seenEventIds: [],
    revision: 0,
    updatedAt: Number(now),
  };
}

function normalizeState(value, now) {
  const source = value && typeof value === 'object' ? value : {};
  const defaults = initialState(now);
  const completed = Array.isArray(source.completed)
    ? source.completed.filter(item => item && item.id).map(item => ({
      id: String(item.id),
      sourceEventId: String(item.sourceEventId || ''),
      completedAt: Number(item.completedAt) || Number(now),
    })).filter(item => MOTE_STORY_EVENTS.some(event => event.id === item.id))
    : [];
  const claimed = Array.isArray(source.claimed)
    ? source.claimed.filter(item => item && item.id).map(item => ({
      id: String(item.id),
      claimId: String(item.claimId || item.eventId || ''),
      claimedAt: Number(item.claimedAt) || Number(now),
      reward: clone(item.reward || MOTE_STORY_EVENTS.find(event => event.id === item.id)?.reward || { xp: 0 }),
    })).filter(item => MOTE_STORY_EVENTS.some(event => event.id === item.id))
    : [];
  return {
    ...defaults,
    ...source,
    version: STORY_VERSION,
    completed: [...new Map(completed.map(item => [item.id, item])).values()],
    claimed: [...new Map(claimed.map(item => [item.id, item])).values()],
    seenEventIds: [...new Set((Array.isArray(source.seenEventIds) ? source.seenEventIds : []).map(String).filter(Boolean))].slice(-MAX_SEEN_EVENTS),
    revision: Math.max(0, Number(source.revision) || 0),
    updatedAt: Number(source.updatedAt) || Number(now),
  };
}

function numberAt(value) {
  return Number.isFinite(Number(value)) ? Number(value) : 0;
}

function hasClue(context, type) {
  return Boolean(context.clues?.[type]) || numberAt(context.clueCounts?.[type]) > 0;
}

function matchesTrigger(event, context) {
  switch (event.trigger) {
    case 'activation': return Boolean(context.activeId);
    case 'conversation': return numberAt(context.conversationCount) > 0;
    case 'task_success': return numberAt(context.successfulTasks) > 0;
    case 'exploration': return numberAt(context.explorationCount) > 0 || Boolean(context.explorationActive);
    case 'clue_location': return hasClue(context, 'location');
    case 'clue_object': return hasClue(context, 'object');
    case 'clue_light': return hasClue(context, 'light');
    case 'mote_unlock': return numberAt(context.unlockedCount) > 6;
    case 'relationship_2': return numberAt(context.relationshipLevel) >= 2;
    case 'relationship_3': return numberAt(context.relationshipLevel) >= 3;
    case 'boost': return numberAt(context.boostCount) > 0;
    case 'task_recovery': return numberAt(context.recoveredTasks) > 0;
    default: return false;
  }
}

class MoteStoryStore {
  constructor({ now = () => Date.now(), persistence = null } = {}) {
    this.now = now;
    this.persistence = persistence;
    this.state = initialState(this.now());
    this._load();
  }

  _load() {
    const saved = this.persistence?.load?.('mote-story', null);
    if (saved && typeof saved === 'object') this.state = normalizeState(saved, this.now());
  }

  _save() {
    this.state.updatedAt = Number(this.now());
    this.persistence?.save?.('mote-story', this.state);
  }

  snapshot() { return clone(this.state); }

  list() {
    const completed = new Map(this.state.completed.map(item => [item.id, item]));
    const claimed = new Map(this.state.claimed.map(item => [item.id, item]));
    return MOTE_STORY_EVENTS.map(event => ({
      ...clone(event),
      completed: completed.has(event.id),
      completedAt: completed.get(event.id)?.completedAt || null,
      claimed: claimed.has(event.id),
      claimedAt: claimed.get(event.id)?.claimedAt || null,
    }));
  }

  evaluate(context = {}) {
    const source = context && typeof context === 'object' ? context : {};
    const eventId = String(source.eventId || `story-eval:${this.state.revision + 1}`).trim();
    if (!eventId) throw new Error('story eventId is required');
    if (this.state.seenEventIds.includes(eventId)) {
      return { duplicate: true, newlyCompleted: [], events: this.list(), state: this.snapshot() };
    }
    this.state.seenEventIds.push(eventId);
    this.state.seenEventIds = this.state.seenEventIds.slice(-MAX_SEEN_EVENTS);
    const completedIds = new Set(this.state.completed.map(item => item.id));
    const newlyCompleted = [];
    for (const event of MOTE_STORY_EVENTS) {
      if (completedIds.has(event.id) || !matchesTrigger(event, source)) continue;
      const completion = { id: event.id, sourceEventId: eventId, completedAt: Number(this.now()) };
      this.state.completed.push(completion);
      completedIds.add(event.id);
      newlyCompleted.push({ ...clone(event), ...completion, completed: true, claimed: false });
    }
    this.state.revision += 1;
    this._save();
    return { duplicate: false, newlyCompleted, events: this.list(), state: this.snapshot() };
  }

  claim(id, claimId) {
    const normalizedId = String(id || '').trim();
    const event = MOTE_STORY_EVENTS.find(item => item.id === normalizedId);
    if (!event) throw new Error('story event not found');
    if (!this.state.completed.some(item => item.id === normalizedId)) throw new Error('story event is not complete');
    const normalizedClaimId = String(claimId || '').trim();
    if (!normalizedClaimId) throw new Error('claimId is required');
    const existing = this.state.claimed.find(item => item.id === normalizedId);
    if (existing) return { duplicate: true, event: { ...clone(event), claimed: true }, reward: { xp: 0 }, state: this.snapshot() };
    const claimed = { id: normalizedId, claimId: normalizedClaimId, claimedAt: Number(this.now()), reward: clone(event.reward) };
    this.state.claimed.push(claimed);
    this.state.revision += 1;
    this._save();
    return { duplicate: false, event: { ...clone(event), claimed: true }, reward: clone(event.reward), state: this.snapshot() };
  }
}

module.exports = { STORY_VERSION, MOTE_STORY_EVENTS, MoteStoryStore, normalizeState, matchesTrigger };
