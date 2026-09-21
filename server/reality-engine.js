'use strict';

const crypto = require('node:crypto');

const CLUE_TYPES = Object.freeze(['location', 'object', 'light']);
const EVENT_KINDS = Object.freeze(['location', 'object', 'light', 'mote']);
const MATERIALS = Object.freeze([
  '潮汐玻璃', '月影纤维', '岩脉碎片', '风行丝', '雷羽', '霜晶', '花露', '折光鳞', '沙金', '暗翅粉',
  '云盐', '星砂', '苔木', '微光种', '回声石', '暖核', '静谧露', '脉冲线圈', '远眺羽', '雾露',
  '晨露', '暮色片', '青苔珠', '光尘', '风铃片', '小行星屑', '镜面砂', '果冻晶', '守护壳', '火星',
  '蓝焰', '金叶', '夜墨', '白霜', '蜂蜜石', '珊瑚线', '浮云绒', '电光籽', '绿芽', '星图纸'
]);
const ITEMS = Object.freeze(Array.from({ length: 40 }, (_, index) => ({
  id: `item_${String(index + 1).padStart(2, '0')}`,
  name: MATERIALS[index],
  kind: index < 20 ? 'material' : index < 30 ? 'boost' : 'equipment',
  value: 1 + (index % 5),
})));
const RECIPES = Object.freeze(Array.from({ length: 20 }, (_, index) => ({
  id: `recipe_${String(index + 1).padStart(2, '0')}`,
  name: ['安定香', '方向罗盘', '微光滤镜', '共鸣护符', '风行贴', '温度护罩', '记忆花环', '雷达羽饰', '月光灯', '栖息软垫'][index % 10] + (index >= 10 ? ` ${Math.floor(index / 10) + 1}` : ''),
  input: [`item_${String((index % 20) + 1).padStart(2, '0')}`, `item_${String(((index + 7) % 20) + 1).padStart(2, '0')}`],
  output: `item_${String(index + 21).padStart(2, '0')}`,
  amount: 1,
})));
const DECORATIONS = Object.freeze(Array.from({ length: 30 }, (_, index) => ({
  id: `decor_${String(index + 1).padStart(2, '0')}`,
  name: ['星灯', '苔石', '云台', '风铃', '月池', '花架', '晶簇', '暖炉', '沙丘', '羽帘'][index % 10] + (Math.floor(index / 10) ? ` ${Math.floor(index / 10) + 1}` : ''),
  comfort: 1 + (index % 4),
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
const ENCOUNTERS = Object.freeze(Array.from({ length: 20 }, (_, index) => ({
  id: `encounter_${String(index + 1).padStart(2, '0')}`,
  style: ['observe', 'soothe', 'dodge', 'resonance'][index % 4],
  difficulty: 1 + (index % 5),
  rewardItem: ITEMS[(index + 20) % ITEMS.length].id,
})));

function clone(value) { return value == null ? value : JSON.parse(JSON.stringify(value)); }
function hash(value) { return crypto.createHash('sha256').update(String(value)).digest('hex'); }
function regionSeed(region, bucket) { return hash(`${region}:${bucket}`); }
function bearingFrom(seed) { return parseInt(seed.slice(0, 4), 16) % 360; }
function distanceFrom(seed) { return ['near', 'mid', 'far'][parseInt(seed.slice(4, 6), 16) % 3]; }

class RealityEngine {
  constructor({ persistence = null, now = () => Date.now() } = {}) {
    this.persistence = persistence;
    this.now = now;
    this.state = {
      version: 2,
      region: null,
      seenEventIds: [],
      inventory: {},
      loadout: [],
      habitat: { decorations: [], comfort: 0 },
      claimedQuestIds: [],
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
      inventory: saved.inventory && typeof saved.inventory === 'object' ? saved.inventory : {},
      habitat: { ...this.state.habitat, ...(saved.habitat || {}) },
      seenEventIds: Array.isArray(saved.seenEventIds) ? saved.seenEventIds.slice(-1000) : [],
      claimedQuestIds: Array.isArray(saved.claimedQuestIds) ? saved.claimedQuestIds.slice(-500) : [],
    };
  }

  _save() {
    this.state.updatedAt = this.now();
    this.persistence?.save?.('reality-state', this.state);
  }

  snapshot() { return clone(this.state); }
  catalog() { return { items: clone(ITEMS), recipes: clone(RECIPES), decorations: clone(DECORATIONS), quests: clone(QUESTS), events: clone(EVENTS), encounters: clone(ENCOUNTERS) }; }

  eventsFor(region, at = this.now()) {
    const normalized = String(region || '').trim();
    if (!normalized || normalized.length > 80) throw new Error('coarse region is required');
    const bucket = Math.floor(Number(at) / 1800000);
    const seed = regionSeed(normalized, bucket);
    return EVENTS.slice(0, 8).map((template, index) => {
      const eventSeed = hash(`${seed}:${template.templateId}`);
      return {
        id: `reality:${normalized}:${bucket}:${template.templateId}`,
        region: normalized,
        kind: template.kind,
        clueType: template.clueType,
        difficulty: template.difficulty,
        xp: template.xp,
        bearing: (bearingFrom(eventSeed) + index * 17) % 360,
        distanceBand: distanceFrom(eventSeed),
        seed: eventSeed.slice(0, 16),
        expiresAt: (bucket + 1) * 1800000,
        rewardPreview: template.rewardItem,
      };
    });
  }

  startEncounter({ eventId, region, at = this.now() } = {}) {
    const event = this.eventsFor(region, at).find(item => item.id === String(eventId));
    if (!event) throw new Error('event is not valid for this region or time');
    const encounter = ENCOUNTERS[parseInt(hash(event.id).slice(0, 4), 16) % ENCOUNTERS.length];
    this.state.activeEncounter = { eventId: event.id, encounterId: encounter.id, step: 0, score: 0, startedAt: this.now() };
    this._save();
    return { event, encounter: clone(encounter), state: this.snapshot() };
  }

  resolve({ eventId, region, clueType = null, actions = [], at = this.now() } = {}) {
    const id = String(eventId || '');
    if (!id) throw new Error('eventId is required');
    if (this.state.seenEventIds.includes(id)) return { duplicate: true, state: this.snapshot() };
    const event = this.eventsFor(region, at).find(item => item.id === id);
    if (!event || event.expiresAt <= Number(at)) throw new Error('event is expired or invalid for this region');
    if (clueType && !CLUE_TYPES.includes(String(clueType))) throw new Error('invalid clueType');
    if (clueType && String(clueType) !== event.clueType) throw new Error('clue type does not match event');
    const encounter = this.state.activeEncounter?.eventId === id ? this.state.activeEncounter : null;
    const bonus = encounter ? Math.min(5, actions.filter(action => ['observe', 'soothe', 'dodge', 'skill'].includes(String(action))).length) : 0;
    const itemId = encounter ? ENCOUNTERS.find(item => item.id === encounter.encounterId)?.rewardItem : event.rewardPreview;
    this.state.seenEventIds.push(id);
    this.state.seenEventIds = this.state.seenEventIds.slice(-1000);
    this.state.inventory[itemId] = (this.state.inventory[itemId] || 0) + 1;
    this.state.xp += event.difficulty + event.xp + bonus;
    this.state.level = Math.max(1, Math.floor(this.state.xp / 100) + 1);
    this.state.activeEncounter = null;
    this._save();
    return { duplicate: false, event, reward: { itemId, amount: 1, xp: event.difficulty + event.xp + bonus }, state: this.snapshot() };
  }

  craft(recipeId) {
    const recipe = RECIPES.find(item => item.id === String(recipeId));
    if (!recipe) throw new Error('recipe not found');
    for (const input of recipe.input) if ((this.state.inventory[input] || 0) < 1) throw new Error(`missing material: ${input}`);
    for (const input of recipe.input) this.state.inventory[input] -= 1;
    this.state.inventory[recipe.output] = (this.state.inventory[recipe.output] || 0) + recipe.amount;
    this._save();
    return { recipe: clone(recipe), state: this.snapshot() };
  }

  setLoadout(items = []) {
    this.state.loadout = [...new Set((Array.isArray(items) ? items : []).map(String))].filter(id => ITEMS.some(item => item.id === id)).slice(0, 4);
    this._save();
    return this.snapshot();
  }

  decorate(decorationId) {
    const decoration = DECORATIONS.find(item => item.id === String(decorationId));
    if (!decoration) throw new Error('decoration not found');
    if (this.state.habitat.decorations.includes(decoration.id)) return this.snapshot();
    this.state.habitat.decorations = [...this.state.habitat.decorations, decoration.id].slice(-12);
    this.state.habitat.comfort = this.state.habitat.decorations.reduce((sum, id) => sum + (DECORATIONS.find(item => item.id === id)?.comfort || 0), 0);
    this._save();
    return this.snapshot();
  }

  claimQuest(questId, eventId) {
    const quest = QUESTS.find(item => item.id === String(questId));
    if (!quest) throw new Error('quest not found');
    if (this.state.claimedQuestIds.includes(String(eventId))) return { duplicate: true, quest, state: this.snapshot() };
    this.state.claimedQuestIds.push(String(eventId));
    this.state.claimedQuestIds = this.state.claimedQuestIds.slice(-500);
    this.state.xp += quest.rewardXp;
    this.state.level = Math.max(1, Math.floor(this.state.xp / 100) + 1);
    this._save();
    return { duplicate: false, quest: clone(quest), state: this.snapshot() };
  }
}

module.exports = { RealityEngine, CLUE_TYPES, EVENT_KINDS, ITEMS, RECIPES, DECORATIONS, QUESTS, EVENTS, ENCOUNTERS };
