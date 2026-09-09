const fs = require('node:fs');
const path = require('node:path');

const INITIAL_MOTE_IDS = Object.freeze(['mote', 'sprite', 'ghost', 'circuit', 'cloud_whale', 'rimuru']);
const EXPLORABLE_MOTE_IDS = Object.freeze(['ember_sprig', 'prism_moth', 'moss_tortoise', 'orbit_raven']);
const CLUE_TYPES = Object.freeze(['location', 'object', 'light']);

const MOTE_PROFILES = Object.freeze([
  { id: 'mote', name: '星核', initial: true, voice: '理性稳重', proactive: 'balanced', taskAffinity: ['planning', 'analysis'], emotionBias: { calm: .18, focus: .24 }, colors: { primary: '#8ea7ff', secondary: '#d8e2ff' }, motion: 'measured-orbit', particles: 'stardust', visualPreset: 'star-core' },
  { id: 'sprite', name: '叶狐', initial: true, voice: '好奇活泼', proactive: 'high', taskAffinity: ['exploration', 'creative'], emotionBias: { joy: .22, curiosity: .25 }, colors: { primary: '#8ee6b0', secondary: '#efffa9' }, motion: 'quick-hop', particles: 'leaf-spark', visualPreset: 'leaf-fox' },
  { id: 'ghost', name: '雾猫', initial: true, voice: '安静共情', proactive: 'low', taskAffinity: ['reflection', 'conversation'], emotionBias: { empathy: .28, calm: .2 }, colors: { primary: '#b8a7e8', secondary: '#edf1ff' }, motion: 'soft-float', particles: 'mist', visualPreset: 'mist-cat' },
  { id: 'circuit', name: '机甲兽', initial: true, voice: '警觉技术', proactive: 'high', taskAffinity: ['monitoring', 'debugging'], emotionBias: { alert: .26, focus: .2 }, colors: { primary: '#53d9ff', secondary: '#a8fff0' }, motion: 'precise-pulse', particles: 'circuit', visualPreset: 'mecha-beast' },
  { id: 'cloud_whale', name: '云鲸', initial: true, voice: '耐心宏观', proactive: 'low', taskAffinity: ['long-form', 'planning'], emotionBias: { calm: .3, patience: .3 }, colors: { primary: '#9cc9ff', secondary: '#fff1c7' }, motion: 'slow-swell', particles: 'cloud', visualPreset: 'cloud-whale' },
  { id: 'rimuru', name: '利姆鲁', initial: true, voice: '温暖适应', proactive: 'balanced', taskAffinity: ['conversation', 'adaptation'], emotionBias: { joy: .2, empathy: .22 }, colors: { primary: '#75e6d1', secondary: '#d1fff5' }, motion: 'elastic-bob', particles: 'bubble', visualPreset: 'rimuru' },
  { id: 'ember_sprig', name: '焰芽', initial: false, voice: '勇敢迅捷', proactive: 'high', taskAffinity: ['execution', 'momentum'], emotionBias: { courage: .3, urgency: .18 }, colors: { primary: '#ff6b35', secondary: '#ffd166' }, motion: 'rapid-bounce', particles: 'embers', visualPreset: 'ember-sprig' },
  { id: 'prism_moth', name: '棱光蝶', initial: false, voice: '敏锐灵巧', proactive: 'balanced', taskAffinity: ['observation', 'triage'], emotionBias: { curiosity: .25, alert: .2 }, colors: { primary: '#64e8ff', secondary: '#c896ff' }, motion: 'orbiting-glide', particles: 'prismatic-rays', visualPreset: 'prism-moth' },
  { id: 'moss_tortoise', name: '苔龟', initial: false, voice: '沉稳守护', proactive: 'low', taskAffinity: ['health', 'maintenance'], emotionBias: { calm: .32, protect: .28 }, colors: { primary: '#77b255', secondary: '#c3a66b' }, motion: 'slow-breath', particles: 'moss-dust', visualPreset: 'moss-tortoise' },
  { id: 'orbit_raven', name: '星鸦', initial: false, voice: '远眺侦察', proactive: 'high', taskAffinity: ['remote-status', 'scanning'], emotionBias: { alert: .28, distance: .24 }, colors: { primary: '#5267c9', secondary: '#f4f6ff' }, motion: 'scan-turn', particles: 'scan-arcs', visualPreset: 'orbit-raven' },
]);

const PROFILE_BY_ID = new Map(MOTE_PROFILES.map(profile => [profile.id, profile]));

function clone(value) { return value == null ? value : JSON.parse(JSON.stringify(value)); }

function deriveMoteBehavior({ profileId = 'mote', taskState = 'idle', deviceHealth = 'unknown', interaction = 'none', explorationActive = false, mood = 0, relationshipLevel = 1 } = {}) {
  const profile = PROFILE_BY_ID.get(String(profileId)) || PROFILE_BY_ID.get('mote');
  const state = String(taskState).toLowerCase();
  const health = String(deviceHealth).toLowerCase();
  const intensity = Math.max(0, Math.min(1, .28 + (state === 'running' ? .28 : 0) + (state === 'failed' ? .18 : 0) + (health === 'degraded' || health === 'error' ? .16 : 0) + (interaction === 'tap' ? .12 : 0) + (explorationActive ? .08 : 0) + Number(mood || 0) * .08));
  const proactive = health === 'error' || state === 'failed' ? 'high' : profile.proactive;
  const proactiveBase = profile.proactive === 'high' ? .72 : (profile.proactive === 'low' ? .24 : .48);
  const reminderStrength = Math.max(0, Math.min(1, proactiveBase + (Math.max(1, Number(relationshipLevel) || 1) - 1) * .04));
  return {
    version: 1,
    profileId: profile.id,
    motionIntensity: Number(intensity.toFixed(3)),
    gaze: health === 'error' ? 'attentive' : (explorationActive ? 'scanning' : 'settled'),
    haloColor: health === 'error' ? '#ff6b6b' : profile.colors.primary,
    particleType: profile.particles,
    proactive,
    reminderStrength: Number(reminderStrength.toFixed(3)),
    speechMode: profile.voice,
  };
}

function createMoteState() {
  return {
    version: 1,
    activeId: INITIAL_MOTE_IDS[0],
    unlockedIds: [...INITIAL_MOTE_IDS],
    exploration: { targetId: null, fragments: { location: false, object: false, light: false }, seenEventIds: [] },
    updatedAt: new Date().toISOString(),
  };
}

function normalizeMoteState(value) {
  const source = value && typeof value === 'object' ? value : {};
  const unlockedIds = [...new Set((Array.isArray(source.unlockedIds) ? source.unlockedIds : INITIAL_MOTE_IDS).map(String).filter(id => PROFILE_BY_ID.has(id)))];
  for (const id of INITIAL_MOTE_IDS) if (!unlockedIds.includes(id)) unlockedIds.push(id);
  const activeId = unlockedIds.includes(String(source.activeId)) ? String(source.activeId) : INITIAL_MOTE_IDS[0];
  const exploration = source.exploration && typeof source.exploration === 'object' ? source.exploration : {};
  const targetId = EXPLORABLE_MOTE_IDS.includes(String(exploration.targetId)) && !unlockedIds.includes(String(exploration.targetId)) ? String(exploration.targetId) : null;
  const fragments = Object.fromEntries(CLUE_TYPES.map(type => [type, Boolean(exploration.fragments?.[type])]));
  const seenEventIds = [...new Set((Array.isArray(exploration.seenEventIds) ? exploration.seenEventIds : []).map(String).filter(Boolean))].slice(-256);
  return { version: 1, activeId, unlockedIds, exploration: { targetId, fragments, seenEventIds }, updatedAt: source.updatedAt || new Date().toISOString() };
}

class MoteStore {
  constructor({ now = () => Date.now(), snapshotPath = null } = {}) {
    this.now = now;
    this.snapshotPath = snapshotPath;
    this.state = createMoteState();
    this._load();
  }

  _load() {
    if (!this.snapshotPath) return;
    try { this.state = normalizeMoteState(JSON.parse(fs.readFileSync(this.snapshotPath, 'utf8'))); } catch (error) { if (error.code !== 'ENOENT') this.loadError = error; }
  }

  _save() {
    this.state.updatedAt = new Date(this.now()).toISOString();
    if (!this.snapshotPath) return;
    fs.mkdirSync(path.dirname(this.snapshotPath), { recursive: true });
    const temporary = `${this.snapshotPath}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify(this.state, null, 2));
    fs.renameSync(temporary, this.snapshotPath);
  }

  getState() { return clone(this.state); }
  roster() { return MOTE_PROFILES.map(profile => ({ ...clone(profile), unlocked: this.state.unlockedIds.includes(profile.id), active: this.state.activeId === profile.id })); }

  setActive(id) {
    const normalized = String(id || '');
    if (!this.state.unlockedIds.includes(normalized)) throw new Error('Mote 尚未解锁');
    this.state.activeId = normalized;
    this._save();
    return this.getState();
  }

  setExplorationTarget(targetId) {
    const normalized = String(targetId || '');
    if (!EXPLORABLE_MOTE_IDS.includes(normalized)) throw new Error('目标 Mote 不可探索');
    if (this.state.unlockedIds.includes(normalized)) throw new Error('Mote 已解锁');
    this.state.exploration = { targetId: normalized, fragments: { location: false, object: false, light: false }, seenEventIds: this.state.exploration.seenEventIds };
    this._save();
    return this.getState();
  }

  collectClue({ eventId, clueType }) {
    const event = String(eventId || '').trim();
    const type = String(clueType || '').trim().toLowerCase();
    if (!event) throw new Error('eventId is required');
    if (!CLUE_TYPES.includes(type)) throw new Error(`invalid clueType: ${type}`);
    if (this.state.exploration.seenEventIds.includes(event)) return { duplicate: true, unlockedId: null, state: this.getState() };
    if (!this.state.exploration.targetId) throw new Error('请先选择探索目标');
    this.state.exploration.seenEventIds.push(event);
    this.state.exploration.seenEventIds = this.state.exploration.seenEventIds.slice(-256);
    this.state.exploration.fragments[type] = true;
    let unlockedId = null;
    if (CLUE_TYPES.every(item => this.state.exploration.fragments[item])) {
      unlockedId = this.state.exploration.targetId;
      this.state.unlockedIds.push(unlockedId);
      this.state.exploration = { targetId: null, fragments: { location: false, object: false, light: false }, seenEventIds: this.state.exploration.seenEventIds };
    }
    this._save();
    return { duplicate: false, unlockedId, state: this.getState() };
  }
}

module.exports = { MOTE_PROFILES, INITIAL_MOTE_IDS, EXPLORABLE_MOTE_IDS, CLUE_TYPES, createMoteState, normalizeMoteState, MoteStore, deriveMoteBehavior };
