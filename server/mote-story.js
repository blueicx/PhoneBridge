'use strict';

const { MOTE_PROFILES } = require('./mote-profiles');

const STORY_VERSION = 2;
const MAX_SEEN_EVENTS = 1024;

const SHARED_STORY_EVENTS = Object.freeze([
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

const EXCLUSIVE_STORY_DEFINITIONS = Object.freeze([
  { moteId: 'mote', title: '星核的第一份推演', description: '把一个计划交给星核，并一起看见它顺利落地。', trigger: 'task_success', completion: '激活星核时成功完成一项任务', reward: { xp: 10 } },
  { moteId: 'sprite', title: '叶狐追风', description: '叶狐发现舞台之外还有新的方向。', trigger: 'exploration', completion: '激活叶狐并开始一次现实探索', reward: { xp: 9 } },
  { moteId: 'ghost', title: '雾猫听见了', description: '在一次真诚对话里，让雾猫知道你愿意停下来。', trigger: 'conversation', completion: '激活雾猫并完成一次对话', reward: { xp: 9 } },
  { moteId: 'circuit', title: '机甲兽的复位信号', description: '遇到波折之后，和机甲兽一起把任务重新带回正轨。', trigger: 'task_recovery', completion: '激活机甲兽并恢复完成一项重试任务', reward: { xp: 12 } },
  { moteId: 'cloud_whale', title: '云鲸的长线航程', description: '与云鲸共同完成一件计划中的事，让耐心留下回声。', trigger: 'task_success', completion: '激活云鲸并成功完成一项任务', reward: { xp: 10 } },
  { moteId: 'rimuru', title: '利姆鲁的回声', description: '用一次来回的交谈，找到彼此都舒服的节奏。', trigger: 'conversation', completion: '激活利姆鲁并完成一次对话', reward: { xp: 9 } },
  { moteId: 'ember_sprig', title: '焰芽先行一步', description: '焰芽把勇气变成行动，陪你完成眼前的一步。', trigger: 'task_success', completion: '激活焰芽并成功完成一项任务', reward: { xp: 11 } },
  { moteId: 'prism_moth', title: '棱光蝶的折射', description: '捕捉一束变化中的光，让棱光蝶看见细节。', trigger: 'clue_light', completion: '激活棱光蝶并收集一枚光线线索', reward: { xp: 10 } },
  { moteId: 'moss_tortoise', title: '苔龟的守望', description: '在持续相处中建立足以互相托付的默契。', trigger: 'relationship_2', completion: '激活苔龟并将关系提升至 2 级', reward: { xp: 11 } },
  { moteId: 'orbit_raven', title: '星鸦望向远方', description: '带星鸦走进一次探索，让它替你先望向远处。', trigger: 'exploration', completion: '激活星鸦并开始一次现实探索', reward: { xp: 10 } },
  { moteId: 'tide_otter', title: '潮獭找到岸线', description: '潮獭沿着新的地点线索，找到探索的起点。', trigger: 'clue_location', completion: '激活潮獭并收集一枚地点线索', reward: { xp: 10 } },
  { moteId: 'moon_deer', title: '月鹿与静夜', description: '在安静而持续的陪伴里，让关系走得更深一点。', trigger: 'relationship_3', completion: '激活月鹿并将关系提升至 3 级', reward: { xp: 13 } },
  { moteId: 'stone_mole', title: '岩鼹的发现', description: '从一件寻常物体开始，和岩鼹找出被忽略的注脚。', trigger: 'clue_object', completion: '激活岩鼹并收集一枚物体线索', reward: { xp: 10 } },
  { moteId: 'wind_marten', title: '风貂的顺风步', description: '顺着风貂的节奏，把一项任务轻快地完成。', trigger: 'task_success', completion: '激活风貂并成功完成一项任务', reward: { xp: 10 } },
  { moteId: 'volt_sparrow', title: '雷雀重连', description: '在一次中断后重新接通协作，让雷雀的警觉变成可靠。', trigger: 'task_recovery', completion: '激活雷雀并恢复完成一项重试任务', reward: { xp: 12 } },
  { moteId: 'frost_hare', title: '雪兔的清晰一瞬', description: '稳住视线，和雪兔一起留下一枚光线线索。', trigger: 'clue_light', completion: '激活雪兔并收集一枚光线线索', reward: { xp: 10 } },
  { moteId: 'bloom_sprite', title: '花灵的新芽', description: '陪伴一只新形态来到图鉴，让花灵看见成长的延续。', trigger: 'mote_unlock', completion: '激活花灵并解锁另一只探索 Mote', reward: { xp: 12 } },
  { moteId: 'crystal_lizard', title: '晶蜥的光谱', description: '晶蜥从一束光里辨认出颜色与方向。', trigger: 'clue_light', completion: '激活晶蜥并收集一枚光线线索', reward: { xp: 10 } },
  { moteId: 'dune_fox', title: '沙狐穿过旷野', description: '与沙狐开始一次探索，走出熟悉的边界。', trigger: 'exploration', completion: '激活沙狐并开始一次现实探索', reward: { xp: 10 } },
  { moteId: 'shadow_moth', title: '影蛾的信任', description: '在不催促的陪伴里，让影蛾逐渐愿意靠近。', trigger: 'relationship_2', completion: '激活影蛾并将关系提升至 2 级', reward: { xp: 11 } },
]);

const PROFILE_BY_ID = new Map(MOTE_PROFILES.map(profile => [profile.id, profile]));
const MOTE_EXCLUSIVE_STORIES = Object.freeze(EXCLUSIVE_STORY_DEFINITIONS.map((event, index) => Object.freeze({
  ...event,
  id: `exclusive-${event.moteId}`,
  moteName: PROFILE_BY_ID.get(event.moteId)?.name || event.moteId,
  exclusive: true,
  sortOrder: index,
})));
const MOTE_STORY_EVENTS = Object.freeze([...SHARED_STORY_EVENTS, ...MOTE_EXCLUSIVE_STORIES]);

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

function deriveExclusiveTriggers(context = {}) {
  const triggers = new Set();
  if (numberAt(context.conversationCount) > 0) triggers.add('conversation');
  if (numberAt(context.successfulTasks) > 0) triggers.add('task_success');
  if (numberAt(context.recoveredTasks) > 0) triggers.add('task_recovery');
  if (numberAt(context.explorationCount) > 0 || context.explorationActive === true) triggers.add('exploration');
  for (const type of ['location', 'object', 'light']) {
    if (numberAt(context.clueCounts?.[type]) > 0 || context.clues?.[type] === true) triggers.add(`clue_${type}`);
  }
  const relationshipLevel = numberAt(context.relationshipLevel);
  const previousLevelValue = context.previousRelationshipLevel;
  if (previousLevelValue !== undefined && previousLevelValue !== null && Number.isFinite(Number(previousLevelValue))) {
    const previousRelationshipLevel = Number(previousLevelValue);
    if (previousRelationshipLevel < 2 && relationshipLevel >= 2) triggers.add('relationship_2');
    if (previousRelationshipLevel < 3 && relationshipLevel >= 3) triggers.add('relationship_3');
  }
  if (numberAt(context.unlockedCount) > 6) triggers.add('mote_unlock');
  if (numberAt(context.boostCount) > 0) triggers.add('boost');
  return [...triggers];
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
    const exclusiveTriggers = new Set(Array.isArray(source.exclusiveTriggers) ? source.exclusiveTriggers.map(String) : []);
    for (const event of MOTE_STORY_EVENTS) {
      if (event.exclusive && String(source.activeId || '') !== event.moteId) continue;
      if (event.exclusive && !exclusiveTriggers.has(event.trigger)) continue;
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

function claimMoteStoryWithReward({ storyStore, relationshipStore, eventId, claimId } = {}) {
  if (!storyStore || !relationshipStore) throw new Error('storyStore and relationshipStore are required');
  const result = storyStore.claim(eventId, claimId);
  const definition = MOTE_STORY_EVENTS.find(event => event.id === String(eventId || '').trim());
  const xp = Math.max(0, Math.round(Number(definition?.reward?.xp) || 0));
  const relationship = xp > 0
    ? relationshipStore.recordInteraction({ eventId: `story:${definition.id}`, kind: 'story', amount: xp })
    : null;
  return { ...result, relationship };
}

module.exports = {
  STORY_VERSION,
  SHARED_STORY_EVENTS,
  MOTE_EXCLUSIVE_STORIES,
  MOTE_STORY_EVENTS,
  MoteStoryStore,
  normalizeState,
  matchesTrigger,
  deriveExclusiveTriggers,
  claimMoteStoryWithReward,
};
