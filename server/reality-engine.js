'use strict';

const crypto = require('node:crypto');
const { RealityEventStore } = require('./reality-event-store');

const CLUE_TYPES = Object.freeze(['location', 'object', 'light']);
const EVENT_KINDS = Object.freeze(['location', 'object', 'light', 'mote']);
const MATERIALS = Object.freeze([
  '潮汐玻璃', '月影纤维', '岩脉碎片', '风行丝', '雷羽', '霜晶', '花露', '折光鳞', '沙金', '暗翅粉',
  '云盐', '星砂', '苔木', '微光种', '回声石', '暖核', '静谧露', '脉冲线圈', '远眺羽', '雾露',
  '晨露', '暮色片', '青苔珠', '光尘', '风铃片', '小行星屑', '镜面砂', '果冻晶', '守护壳', '火星',
  '蓝焰', '金叶', '夜墨', '白霜', '蜂蜜石', '珊瑚线', '浮云绒', '电光籽', '绿芽', '星图纸'
]);
const ITEM_USE_PROFILES = Object.freeze({
  item_21: { kind: 'xp_multiplier', label: '专注增益', description: '30 分钟内探索经验提高 50%。', multiplier: 1.5, durationMs: 30 * 60 * 1000 },
  item_22: { kind: 'clue_xp', label: '地点观察加深', description: '30 分钟内完成地点线索额外获得 5 XP。', clueType: 'location', amount: 5, durationMs: 30 * 60 * 1000 },
  item_23: { kind: 'extra_item', label: '发现加倍', description: '接下来一次有效探索额外获得一份材料。', amount: 1, charges: 1, durationMs: 24 * 60 * 60 * 1000 },
  item_24: { kind: 'encounter_xp', label: '共鸣回响', description: '30 分钟内完成遭遇额外获得 4 XP。', amount: 4, durationMs: 30 * 60 * 1000 },
  item_25: { kind: 'home_comfort', label: '暖居微光', description: '1 小时内家园舒适度提高 2。', amount: 2, durationMs: 60 * 60 * 1000 },
  item_26: { kind: 'clue_xp', label: '光线校准', description: '30 分钟内完成光线线索额外获得 5 XP。', clueType: 'light', amount: 5, durationMs: 30 * 60 * 1000 },
});
const ITEM_EQUIPMENT_PROFILES = Object.freeze({
  item_31: { key: 'xpBonus', amount: 2, label: '巡游徽章', description: '每次有效探索额外获得 2 XP。' },
  item_32: { key: 'lightXpBonus', amount: 3, label: '折光镜片', description: '光线线索额外获得 3 XP。' },
  item_33: { key: 'extraItem', amount: 1, label: '采集挎包', description: '每次有效探索额外获得一份材料。' },
  item_34: { key: 'actionXpBonus', amount: 1, label: '共鸣手套', description: '遭遇中的每个有效行动额外贡献 1 XP。' },
  item_35: { key: 'homeComfort', amount: 3, label: '栖居护符', description: '家园舒适度提高 3。' },
  item_36: { key: 'encounterXpBonus', amount: 5, label: '稳定线圈', description: '完成遭遇额外获得 5 XP。' },
});
const ITEMS = Object.freeze(Array.from({ length: 40 }, (_, index) => ({
  id: `item_${String(index + 1).padStart(2, '0')}`,
  name: MATERIALS[index],
  kind: index < 20 ? 'material' : index < 30 ? 'boost' : 'equipment',
  value: 1 + (index % 5),
  ...(ITEM_USE_PROFILES[`item_${String(index + 1).padStart(2, '0')}`]
    ? { useEffect: ITEM_USE_PROFILES[`item_${String(index + 1).padStart(2, '0')}`], effectLabel: ITEM_USE_PROFILES[`item_${String(index + 1).padStart(2, '0')}`].label, effectDescription: ITEM_USE_PROFILES[`item_${String(index + 1).padStart(2, '0')}`].description }
    : {}),
  ...(ITEM_EQUIPMENT_PROFILES[`item_${String(index + 1).padStart(2, '0')}`]
    ? { equipmentEffect: ITEM_EQUIPMENT_PROFILES[`item_${String(index + 1).padStart(2, '0')}`], effectLabel: ITEM_EQUIPMENT_PROFILES[`item_${String(index + 1).padStart(2, '0')}`].label, effectDescription: ITEM_EQUIPMENT_PROFILES[`item_${String(index + 1).padStart(2, '0')}`].description }
    : {}),
})));
const RECIPES = Object.freeze(Array.from({ length: 20 }, (_, index) => ({
  id: `recipe_${String(index + 1).padStart(2, '0')}`,
  name: ['安定香', '方向罗盘', '微光滤镜', '共鸣护符', '风行贴', '温度护罩', '记忆花环', '雷达羽饰', '月光灯', '栖息软垫'][index % 10] + (index >= 10 ? ` ${Math.floor(index / 10) + 1}` : ''),
  input: [`item_${String((index % 20) + 1).padStart(2, '0')}`, `item_${String(((index + 7) % 20) + 1).padStart(2, '0')}`],
  output: `item_${String(index + 21).padStart(2, '0')}`,
  amount: 1,
})));
const DECORATION_PRESENTATIONS = Object.freeze([
  { visualType: 'star-lamp', interaction: 'tap-glow' },
  { visualType: 'moss-stone', interaction: 'tap-soothe' },
  { visualType: 'cloud-platform', interaction: 'tap-sit' },
  { visualType: 'wind-chime', interaction: 'tap-chime' },
  { visualType: 'moon-pool', interaction: 'tap-ripple' },
  { visualType: 'flower-rack', interaction: 'tap-bloom' },
  { visualType: 'crystal-cluster', interaction: 'tap-pulse' },
  { visualType: 'warm-hearth', interaction: 'tap-warm' },
  { visualType: 'sand-dune', interaction: 'tap-rest' },
  { visualType: 'feather-curtain', interaction: 'tap-wave' },
]);
const DECORATIONS = Object.freeze(Array.from({ length: 30 }, (_, index) => ({
  id: `decor_${String(index + 1).padStart(2, '0')}`,
  name: ['星灯', '苔石', '云台', '风铃', '月池', '花架', '晶簇', '暖炉', '沙丘', '羽帘'][index % 10] + (Math.floor(index / 10) ? ` ${Math.floor(index / 10) + 1}` : ''),
  comfort: 1 + (index % 4),
  ...DECORATION_PRESENTATIONS[index % DECORATION_PRESENTATIONS.length],
})));
const QUESTS = Object.freeze(Array.from({ length: 36 }, (_, index) => ({
  id: `quest_${String(index + 1).padStart(2, '0')}`,
  kind: index < 18 ? 'daily' : index < 26 ? 'weekly' : 'story',
  title: ['完成一次观察', '收集两种材料', '进行一次共鸣', '布置一个装饰', '合成一件道具', '让 Mote 休息'][index % 6],
  rewardXp: 5 + (index % 5) * 2,
})));
const EVENTS = Object.freeze(Array.from({ length: 40 }, (_, index) => ({
  templateId: `event_${String(index + 1).padStart(2, '0')}`,
  kind: EVENT_KINDS[index % EVENT_KINDS.length],
  clueType: CLUE_TYPES[index % CLUE_TYPES.length],
  difficulty: 1 + (index % 5),
  rewardItem: ITEMS[index].id,
  xp: 4 + (index % 7),
})));
const ROUTINE_REWARDS = Object.freeze({
  'focus-timer': { title: '专注计时', baseXp: 2 },
  'walk-observation': { title: '散步观察', baseXp: 5 },
  'bedtime-review': { title: '睡前回顾', baseXp: 3 },
});
const ENCOUNTERS = Object.freeze(Array.from({ length: 20 }, (_, index) => ({
  id: `encounter_${String(index + 1).padStart(2, '0')}`,
  style: ['observe', 'soothe', 'dodge', 'resonance'][index % 4],
  difficulty: 1 + (index % 5),
  rewardItem: ITEMS[(index + 20) % ITEMS.length].id,
})));

function clone(value) { return value == null ? value : JSON.parse(JSON.stringify(value)); }
function hash(value) { return crypto.createHash('sha256').update(String(value)).digest('hex'); }
function normalizeRegion(value) {
  const region = String(value || '').trim();
  if (region === 'camera' || /^cell:-?\d+:-?\d+$/.test(region)) return region;
  throw new Error('coarse region is required');
}
function defaultDecorationPosition(slot) {
  const index = Math.max(0, Number(slot) || 0);
  return {
    x: Number((0.16 + (index % 4) * 0.225).toFixed(3)),
    y: Number((0.79 - Math.floor(index / 4) * 0.16).toFixed(3)),
    rotation: 0,
  };
}
function normalizeHabitat(value) {
  const source = value && typeof value === 'object' ? value : {};
  const knownIds = new Set(DECORATIONS.map(item => item.id));
  const rawIds = Array.isArray(source.decorations) ? source.decorations : [];
  const ids = [...new Set(rawIds.map(String).filter(id => knownIds.has(id)))].slice(0, 12);
  const savedLayout = new Map((Array.isArray(source.layout) ? source.layout : [])
    .filter(item => item && knownIds.has(String(item.id)))
    .map(item => [String(item.id), item]));
  const layout = ids.map((id, slot) => {
    const saved = savedLayout.get(id);
    const defaults = defaultDecorationPosition(slot);
    const x = Number(saved?.x);
    const y = Number(saved?.y);
    const rotation = Number(saved?.rotation);
    return {
      id,
      x: Number.isFinite(x) && x >= 0 && x <= 1 ? Number(x.toFixed(3)) : defaults.x,
      y: Number.isFinite(y) && y >= 0 && y <= 1 ? Number(y.toFixed(3)) : defaults.y,
      rotation: Number.isFinite(rotation) && rotation >= -180 && rotation <= 180 ? Math.round(rotation) : 0,
    };
  });
  return {
    layoutVersion: 2,
    decorations: ids,
    layout,
    comfort: ids.reduce((sum, id) => sum + (DECORATIONS.find(item => item.id === id)?.comfort || 0), 0),
  };
}

class RealityEngine {
  constructor({ persistence = null, now = () => Date.now() } = {}) {
    this.persistence = persistence;
    this.now = now;
    this.eventStore = new RealityEventStore({ templates: EVENTS, now });
    this.state = {
      version: 4,
      region: null,
      seenEventIds: [],
      rewardReceipts: {},
      inventory: {},
      loadout: [],
      habitat: normalizeHabitat(),
      claimedQuestIds: [],
      activeBoosts: [],
      activeEffects: [],
      itemUseReceipts: {},
      craftReceipts: {},
      activityReceipts: {},
      growthRecords: [],
      xp: 0,
      level: 1,
      activeEncounter: null,
      updatedAt: this.now(),
    };
    this._load();
  }

  _load() {
    const saved = this.persistence?.load('reality-state', null);
    if (!saved || typeof saved !== 'object') return;
    this.state = {
      ...this.state,
      ...saved,
      version: 4,
      inventory: saved.inventory && typeof saved.inventory === 'object' ? saved.inventory : {},
      habitat: normalizeHabitat(saved.habitat),
      seenEventIds: Array.isArray(saved.seenEventIds) ? saved.seenEventIds.slice(-1000) : [],
      rewardReceipts: saved.rewardReceipts && typeof saved.rewardReceipts === 'object' ? saved.rewardReceipts : {},
      claimedQuestIds: Array.isArray(saved.claimedQuestIds) ? saved.claimedQuestIds.slice(-500) : [],
      activeBoosts: Array.isArray(saved.activeBoosts) ? saved.activeBoosts.slice(-12) : [],
      activeEffects: Array.isArray(saved.activeEffects) ? saved.activeEffects.slice(-100) : [],
      itemUseReceipts: saved.itemUseReceipts && typeof saved.itemUseReceipts === 'object' ? saved.itemUseReceipts : {},
      craftReceipts: saved.craftReceipts && typeof saved.craftReceipts === 'object' ? saved.craftReceipts : {},
      activityReceipts: saved.activityReceipts && typeof saved.activityReceipts === 'object' ? saved.activityReceipts : {},
      growthRecords: Array.isArray(saved.growthRecords) ? saved.growthRecords.slice(-1000) : [],
    };
  }

  _save() {
    this.state.updatedAt = this.now();
    this.persistence?.save?.('reality-state', this.state);
  }

  snapshot() { return clone(this.state); }
  reset() {
    const deleted = this.state.seenEventIds.length + Object.keys(this.state.inventory).length + this.state.claimedQuestIds.length + this.state.growthRecords.length;
    this.state = {
      version: 4,
      region: null,
      seenEventIds: [],
      rewardReceipts: {},
      inventory: {},
      loadout: [],
      habitat: normalizeHabitat(),
      claimedQuestIds: [],
      activeBoosts: [],
      activeEffects: [],
      itemUseReceipts: {},
      craftReceipts: {},
      activityReceipts: {},
      growthRecords: [],
      xp: 0,
      level: 1,
      activeEncounter: null,
      updatedAt: this.now(),
    };
    this._save();
    return { deleted };
  }
  getReceipt(eventId) { return clone(this.state.rewardReceipts[String(eventId || '')] || null); }
  listReceipts() { return Object.values(this.state.rewardReceipts).map(clone); }
  catalog() { return { items: clone(ITEMS), recipes: clone(RECIPES), decorations: clone(DECORATIONS), quests: clone(QUESTS), events: clone(EVENTS), encounters: clone(ENCOUNTERS) }; }

  setBoosts(boosts = []) {
    const at = this.now();
    this.state.activeBoosts = (Array.isArray(boosts) ? boosts : [])
      .filter(boost => boost && Number(boost.expiresAt) > at && Number(boost.multiplier) > 0)
      .map(boost => ({ id: String(boost.id), multiplier: Number(boost.multiplier), expiresAt: Number(boost.expiresAt) }))
      .slice(-12);
    this._save();
    return this.snapshot();
  }

  activeBoost(at = this.now()) {
    const boosts = this.state.activeBoosts
      .filter(boost => Number(boost.expiresAt) > Number(at))
      .concat(this.state.activeEffects
        .filter(effect => effect.kind === 'xp_multiplier' && Number(effect.expiresAt) > Number(at))
        .map(effect => ({ id: effect.itemId, multiplier: Number(effect.multiplier), expiresAt: Number(effect.expiresAt) })))
      .sort((left, right) => Number(right.multiplier) - Number(left.multiplier))[0] || null;
    return boosts;
  }

  equipmentEffects() {
    const totals = { xpBonus: 0, extraItem: 0, homeComfort: 0, actionXpBonus: 0, lightXpBonus: 0, encounterXpBonus: 0 };
    for (const id of this.state.loadout) {
      const item = ITEMS.find(candidate => candidate.id === id && candidate.kind === 'equipment');
      const effect = item?.equipmentEffect;
      if (effect && (this.state.inventory[id] || 0) > 0 && Object.hasOwn(totals, effect.key)) totals[effect.key] += Number(effect.amount) || 0;
    }
    return totals;
  }

  effectiveHabitatComfort(at = this.now()) {
    const temporary = this.state.activeEffects
      .filter(effect => effect.kind === 'home_comfort' && Number(effect.expiresAt) > Number(at))
      .reduce((sum, effect) => sum + (Number(effect.amount) || 0), 0);
    return Number(this.state.habitat.comfort || 0) + this.equipmentEffects().homeComfort + temporary;
  }

  listActiveItemEffects(at = this.now()) {
    return clone(this.state.activeEffects.filter(effect => Number(effect.expiresAt) > Number(at)));
  }

  recordActivityCompletion({ eventId, routineId, elapsedSeconds = 0 } = {}) {
    const id = String(eventId || '').trim();
    const routine = String(routineId || '').trim();
    const elapsed = Number(elapsedSeconds);
    if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(id)) throw new Error('eventId is required and must contain safe characters');
    const definition = ROUTINE_REWARDS[routine];
    if (!definition) throw new Error('routine is not eligible for completion growth');
    if (!Number.isSafeInteger(elapsed) || elapsed < 0 || elapsed > 24 * 60 * 60) throw new Error('activity duration is invalid');
    const fingerprint = hash(JSON.stringify({ routineId: routine, elapsedSeconds: elapsed }));
    const prior = this.state.activityReceipts[id];
    if (prior) {
      if (prior.fingerprint !== fingerprint) throw new Error('eventId was reused for different activity completion');
      return { duplicate: true, receipt: clone(prior.receipt), state: this.snapshot() };
    }
    const previousState = clone(this.state);
    const xpDelta = routine === 'focus-timer' ? Math.max(definition.baseXp, Math.min(20, Math.floor(elapsed / 300) * 2)) : definition.baseXp;
    const receipt = {
      eventId: id, routineId: routine, title: definition.title, source: 'daily_activity',
      elapsedSeconds: elapsed, xpDelta, relationshipDelta: 1, completedAt: this.now(),
    };
    this.state.xp += xpDelta;
    this.state.level = Math.max(1, Math.floor(this.state.xp / 100) + 1);
    this.state.growthRecords.push(receipt);
    this.state.growthRecords = this.state.growthRecords.slice(-1000);
    this.state.activityReceipts[id] = { fingerprint, receipt };
    const receiptIds = Object.keys(this.state.activityReceipts);
    for (const oldId of receiptIds.slice(0, Math.max(0, receiptIds.length - 1000))) delete this.state.activityReceipts[oldId];
    try { this._save(); } catch (error) { this.state = previousState; throw error; }
    return { duplicate: false, receipt: clone(receipt), state: this.snapshot() };
  }

  previewRecipe(recipeId) {
    const recipe = RECIPES.find(item => item.id === String(recipeId));
    if (!recipe) throw new Error('recipe not found');
    const clueNames = { location: '地点线索', object: '物体线索', light: '光线线索' };
    const ingredients = recipe.input.map(inputId => {
      const item = ITEMS.find(candidate => candidate.id === inputId);
      const required = 1;
      const owned = Math.max(0, Number(this.state.inventory[inputId]) || 0);
      const sources = [...new Set(EVENTS.filter(event => event.rewardItem === inputId).map(event => clueNames[event.clueType]).filter(Boolean))];
      if (ENCOUNTERS.some(encounter => encounter.rewardItem === inputId)) sources.push('伙伴遭遇');
      return { itemId: inputId, name: item?.name || inputId, required, owned, missing: Math.max(0, required - owned), sources: sources.length ? [...new Set(sources)] : ['现实探索'] };
    });
    const missing = ingredients.filter(item => item.missing > 0).map(item => item.itemId);
    return { recipe: clone(recipe), ingredients, missing, canCraft: missing.length === 0, output: clone(ITEMS.find(item => item.id === recipe.output) || null) };
  }

  useItem({ eventId, itemId } = {}) {
    const id = String(eventId || '').trim();
    const key = String(itemId || '').trim();
    if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(id)) throw new Error('eventId is required and must contain safe characters');
    const prior = this.state.itemUseReceipts[id];
    if (prior) {
      if (prior.itemId !== key) throw new Error('eventId was reused for a different item');
      return { duplicate: true, receipt: clone(prior), state: this.snapshot() };
    }
    const item = ITEMS.find(candidate => candidate.id === key);
    if (!item?.useEffect || item.kind !== 'boost') throw new Error('item is not usable');
    if ((this.state.inventory[key] || 0) < 1) throw new Error('item is not owned');
    const previousState = clone(this.state);
    const effect = clone(item.useEffect);
    const usedAt = this.now();
    const receipt = { eventId: id, itemId: key, effect, usedAt, expiresAt: usedAt + Number(effect.durationMs || 0) };
    this.state.inventory[key] -= 1;
    this.state.activeEffects = this.state.activeEffects
      .filter(active => Number(active.expiresAt) > usedAt)
      .concat({ eventId: id, itemId: key, ...effect, expiresAt: receipt.expiresAt })
      .slice(-100);
    this.state.itemUseReceipts[id] = receipt;
    const receiptIds = Object.keys(this.state.itemUseReceipts);
    for (const oldId of receiptIds.slice(0, Math.max(0, receiptIds.length - 1000))) delete this.state.itemUseReceipts[oldId];
    try { this._save(); } catch (error) { this.state = previousState; throw error; }
    return { duplicate: false, receipt: clone(receipt), state: this.snapshot() };
  }

  eventsFor(region, at = this.now()) {
    return this.eventStore.eventsFor(region, at);
  }

  startEncounter({ eventId, region, at = this.now() } = {}) {
    const id = String(eventId || '');
    const event = this.eventsFor(region, at).find(item => item.id === id);
    if (!event) throw new Error('event is not valid for this region or time');
    if (this.state.seenEventIds.includes(id)) {
      const receipt = this.getReceipt(id);
      const completedEncounter = receipt?.encounterId
        ? ENCOUNTERS.find(item => item.id === receipt.encounterId)
        : null;
      return { duplicate: true, event, encounter: clone(completedEncounter), receipt, state: this.snapshot() };
    }
    const active = this.state.activeEncounter;
    if (active?.eventId === id) {
      const existingEncounter = ENCOUNTERS.find(item => item.id === active.encounterId) || null;
      return { duplicate: true, event, encounter: clone(existingEncounter), state: this.snapshot() };
    }
    const encounter = ENCOUNTERS[parseInt(hash(event.id).slice(0, 4), 16) % ENCOUNTERS.length];
    const previousState = clone(this.state);
    this.state.activeEncounter = { eventId: event.id, encounterId: encounter.id, step: 0, score: 0, startedAt: this.now() };
    try { this._save(); } catch (error) { this.state = previousState; throw error; }
    return { duplicate: false, event, encounter: clone(encounter), state: this.snapshot() };
  }

  resolve({ eventId, region, clueType = null, actions = [], at = this.now() } = {}) {
    const id = String(eventId || '');
    if (!id) throw new Error('eventId is required');
    if (this.state.seenEventIds.includes(id)) {
      const receipt = this.getReceipt(id);
      return { duplicate: true, receipt, event: clone(receipt?.event || null), reward: clone(receipt?.reward || null), state: this.snapshot() };
    }
    const event = this.eventsFor(region, at).find(item => item.id === id);
    if (!event || event.expiresAt <= Number(at)) throw new Error('event is expired or invalid for this region');
    if (clueType && !CLUE_TYPES.includes(String(clueType))) throw new Error('invalid clueType');
    if (clueType && String(clueType) !== event.clueType) throw new Error('clue type does not match event');
    const encounter = this.state.activeEncounter?.eventId === id ? this.state.activeEncounter : null;
    const selectedActions = [...new Set((Array.isArray(actions) ? actions : [])
      .map(action => String(action))
      .filter(action => ['observe', 'soothe', 'dodge', 'skill'].includes(action)))].slice(0, 4);
    const bonus = encounter ? Math.min(5, selectedActions.length) : 0;
    const itemId = encounter ? ENCOUNTERS.find(item => item.id === encounter.encounterId)?.rewardItem : event.rewardPreview;
    const item = ITEMS.find(candidate => candidate.id === itemId) || null;
    const previousState = clone(this.state);
    const boost = this.activeBoost(at);
    const multiplier = boost?.multiplier || 1;
    const equipment = this.equipmentEffects();
    const effects = this.state.activeEffects.filter(effect => Number(effect.expiresAt) > Number(at));
    const clueBonus = effects.filter(effect => effect.kind === 'clue_xp' && effect.clueType === event.clueType)
      .reduce((sum, effect) => sum + (Number(effect.amount) || 0), 0);
    const encounterBonus = encounter
      ? equipment.encounterXpBonus + effects.filter(effect => effect.kind === 'encounter_xp').reduce((sum, effect) => sum + (Number(effect.amount) || 0), 0)
      : 0;
    const actionBonus = encounter ? Math.min(5, selectedActions.length * (1 + equipment.actionXpBonus)) : 0;
    const baseXp = event.difficulty + event.xp + actionBonus + equipment.xpBonus + equipment.lightXpBonus * Number(event.clueType === 'light') + clueBonus + encounterBonus;
    const rewardXp = Math.max(baseXp, Math.round(baseXp * multiplier));
    const extraEffect = effects.find(effect => effect.kind === 'extra_item' && Number(effect.charges) > 0);
    const rewardAmount = 1 + equipment.extraItem + (extraEffect ? Number(extraEffect.amount) || 0 : 0);
    const reward = { itemId, item: clone(item), amount: rewardAmount, xp: rewardXp, baseXp, multiplier, boostId: boost?.id || null,
      bonuses: { equipmentXp: equipment.xpBonus, clueXp: clueBonus, encounterXp: encounterBonus, extraItems: rewardAmount - 1 } };
    const receipt = {
      eventId: id,
      region: String(region || ''),
      clueType: event.clueType,
      encounterId: encounter?.encounterId || null,
      action: selectedActions[0] || null,
      event: clone(event),
      reward: clone(reward),
      resolvedAt: this.now(),
    };
    this.state.seenEventIds.push(id);
    this.state.seenEventIds = this.state.seenEventIds.slice(-1000);
    this.state.inventory[itemId] = (this.state.inventory[itemId] || 0) + rewardAmount;
    this.state.xp += rewardXp;
    this.state.level = Math.max(1, Math.floor(this.state.xp / 100) + 1);
    this.state.rewardReceipts[id] = receipt;
    if (extraEffect) {
      this.state.activeEffects = this.state.activeEffects.map(effect => effect.eventId === extraEffect.eventId
        ? { ...effect, charges: Math.max(0, Number(effect.charges) - 1) }
        : effect).filter(effect => Number(effect.expiresAt) > Number(at) && Number(effect.charges ?? 1) > 0);
    } else {
      this.state.activeEffects = this.state.activeEffects.filter(effect => Number(effect.expiresAt) > Number(at));
    }
    const receiptIds = Object.keys(this.state.rewardReceipts);
    for (const oldId of receiptIds.slice(0, Math.max(0, receiptIds.length - 1000))) delete this.state.rewardReceipts[oldId];
    this.state.activeEncounter = null;
    try { this._save(); } catch (error) { this.state = previousState; throw error; }
    return { duplicate: false, event, reward: clone(reward), receipt: clone(receipt), state: this.snapshot() };
  }

  craft(recipeId, eventId = null) {
    const recipe = RECIPES.find(item => item.id === String(recipeId));
    if (!recipe) throw new Error('recipe not found');
    const receiptId = eventId == null ? '' : String(eventId).trim();
    if (receiptId && !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(receiptId)) throw new Error('eventId is invalid');
    if (receiptId && this.state.craftReceipts[receiptId]) {
      const receipt = this.state.craftReceipts[receiptId];
      if (receipt.recipeId !== recipe.id) throw new Error('eventId was reused for a different recipe');
      return { duplicate: true, recipe: clone(recipe), receipt: clone(receipt), state: this.snapshot() };
    }
    const preview = this.previewRecipe(recipeId);
    if (!preview.canCraft) throw new Error(`missing material: ${preview.missing.join(', ')}`);
    const previousState = clone(this.state);
    for (const input of recipe.input) this.state.inventory[input] -= 1;
    this.state.inventory[recipe.output] = (this.state.inventory[recipe.output] || 0) + recipe.amount;
    const receipt = receiptId ? { eventId: receiptId, recipeId: recipe.id, output: recipe.output, amount: recipe.amount, craftedAt: this.now() } : null;
    if (receipt) this.state.craftReceipts[receiptId] = receipt;
    const receiptIds = Object.keys(this.state.craftReceipts);
    for (const oldId of receiptIds.slice(0, Math.max(0, receiptIds.length - 1000))) delete this.state.craftReceipts[oldId];
    try { this._save(); } catch (error) { this.state = previousState; throw error; }
    return { duplicate: false, recipe: clone(recipe), preview, receipt, state: this.snapshot() };
  }

  setLoadout(items = []) {
    const requested = [...new Set((Array.isArray(items) ? items : []).map(String))].slice(0, 4);
    for (const id of requested) {
      if (!ITEMS.some(item => item.id === id && item.kind === 'equipment')) throw new Error(`item is not equipment: ${id}`);
      if ((this.state.inventory[id] || 0) < 1) throw new Error(`equipment not owned: ${id}`);
    }
    const previousState = clone(this.state);
    this.state.loadout = requested;
    try { this._save(); } catch (error) { this.state = previousState; throw error; }
    return this.snapshot();
  }

  decorate(decorationId) {
    const decoration = DECORATIONS.find(item => item.id === String(decorationId));
    if (!decoration) throw new Error('decoration not found');
    if (this.state.habitat.decorations.includes(decoration.id)) return this.snapshot();
    if (this.state.habitat.decorations.length >= 12) throw new Error('habitat supports at most 12 decorations');
    const previousState = clone(this.state);
    this.state.habitat.decorations.push(decoration.id);
    this.state.habitat = normalizeHabitat(this.state.habitat);
    try { this._save(); } catch (error) { this.state = previousState; throw error; }
    return this.snapshot();
  }

  updateHabitat({ action, decorationId, x, y, rotation } = {}) {
    const normalizedAction = String(action || '').trim().toLowerCase();
    const id = String(decorationId || '').trim();
    const previousState = clone(this.state);
    let habitat = this.state.habitat;
    if (normalizedAction === 'move') {
      const index = habitat.layout.findIndex(item => item.id === id);
      if (index < 0) throw new Error('decoration is not placed in habitat');
      const nextX = Number(x);
      const nextY = Number(y);
      const nextRotation = rotation == null ? habitat.layout[index].rotation : Number(rotation);
      if (!Number.isFinite(nextX) || nextX < 0 || nextX > 1 || !Number.isFinite(nextY) || nextY < 0 || nextY > 1 || !Number.isFinite(nextRotation) || nextRotation < -180 || nextRotation > 180) {
        throw new Error('habitat position or rotation is outside its allowed range');
      }
      habitat.layout[index] = { id, x: Number(nextX.toFixed(3)), y: Number(nextY.toFixed(3)), rotation: Math.round(nextRotation) };
    } else if (normalizedAction === 'remove') {
      if (!habitat.decorations.includes(id)) throw new Error('decoration is not placed in habitat');
      habitat = this.state.habitat = normalizeHabitat({ decorations: habitat.decorations.filter(item => item !== id), layout: habitat.layout.filter(item => item.id !== id) });
    } else if (normalizedAction === 'reset') {
      habitat = this.state.habitat = normalizeHabitat({ decorations: habitat.decorations });
    } else {
      throw new Error('unknown habitat action');
    }
    try { this._save(); } catch (error) { this.state = previousState; throw error; }
    return this.snapshot();
  }

  claimQuest(questId, eventId) {
    const quest = QUESTS.find(item => item.id === String(questId));
    if (!quest) throw new Error('quest not found');
    if (this.state.claimedQuestIds.includes(String(eventId))) return { duplicate: true, quest, state: this.snapshot() };
    const completed = quest.id === 'quest_01'
      ? this.state.seenEventIds.length >= 1
      : quest.id === 'quest_02'
        ? Object.values(this.state.inventory).reduce((sum, amount) => sum + Number(amount || 0), 0) >= 2
        : this.state.xp >= quest.rewardXp;
    if (!completed) throw new Error('quest is not complete');
    this.state.claimedQuestIds.push(String(eventId));
    this.state.claimedQuestIds = this.state.claimedQuestIds.slice(-500);
    this.state.xp += quest.rewardXp;
    this.state.level = Math.max(1, Math.floor(this.state.xp / 100) + 1);
    this._save();
    return { duplicate: false, quest: clone(quest), state: this.snapshot() };
  }
}

module.exports = { RealityEngine, CLUE_TYPES, EVENT_KINDS, ITEMS, RECIPES, DECORATIONS, QUESTS, EVENTS, ENCOUNTERS };
