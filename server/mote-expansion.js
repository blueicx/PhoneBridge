const fs = require('node:fs');
const path = require('node:path');
const { MOTE_STORY_EVENTS } = require('./mote-story');

const DURABLE_STORY_REWARD_RECEIPTS = new Set(MOTE_STORY_EVENTS.map(event => `story:${event.id}`));

class MoteRelationshipStore {
  constructor({ now = () => Date.now(), snapshotPath = null, persistence = null } = {}) {
    this.now = now;
    this.snapshotPath = snapshotPath;
    this.persistence = persistence;
    this.state = { version: 1, level: 1, xp: 0, interactions: 0, seenEventIds: [] };
    this._load();
  }
  _load() {
    if (!this.snapshotPath && !this.persistence) return;
    try {
      const value = this.persistence ? this.persistence.load('mote-relationship', {}) : JSON.parse(fs.readFileSync(this.snapshotPath, 'utf8'));
      this.state = { ...this.state, ...value };
    } catch (error) { if (error.code !== 'ENOENT') this.loadError = error; }
  }
  _save() {
    if (this.persistence) { this.persistence.save('mote-relationship', this.state); return; }
    if (!this.snapshotPath) return;
    fs.mkdirSync(path.dirname(this.snapshotPath), { recursive: true });
    const temp = `${this.snapshotPath}.tmp`;
    fs.writeFileSync(temp, JSON.stringify(this.state, null, 2));
    fs.renameSync(temp, this.snapshotPath);
  }
  recordInteraction({ eventId, kind = 'interaction', amount = 1 } = {}) {
    const key = String(eventId || '').trim();
    if (!key) throw new Error('eventId is required');
    if (this.state.seenEventIds.includes(key)) return { duplicate: true, ...this.snapshot() };
    const eventIds = [...new Set([...this.state.seenEventIds, key])];
    const durableRewardReceipts = eventIds.filter(id => DURABLE_STORY_REWARD_RECEIPTS.has(id));
    const recentInteractions = eventIds.filter(id => !DURABLE_STORY_REWARD_RECEIPTS.has(id)).slice(-512);
    this.state.seenEventIds = [...durableRewardReceipts, ...recentInteractions];
    this.state.xp += Math.max(0, Math.round(Number(amount) || 0));
    this.state.interactions += 1;
    this.state.level = Math.max(1, Math.floor(this.state.xp / 100) + 1);
    this.state.updatedAt = new Date(this.now()).toISOString();
    this._save();
    return { duplicate: false, kind, ...this.snapshot() };
  }
  snapshot() { return JSON.parse(JSON.stringify(this.state)); }
}

class MoteQuestStore {
  constructor({ quests = [], now = () => Date.now(), snapshotPath = null, persistence = null } = {}) {
    this.quests = quests.map(q => ({ ...q }));
    this.now = now;
    this.snapshotPath = snapshotPath;
    this.persistence = persistence;
    this.claimed = new Map();
    this._load();
  }
  _load() {
    if (!this.snapshotPath && !this.persistence) return;
    try {
      const data = this.persistence ? this.persistence.load('mote-quests', {}) : JSON.parse(fs.readFileSync(this.snapshotPath, 'utf8'));
      for (const item of data.claimed || []) if (item?.eventId && item?.questId) this.claimed.set(String(item.eventId), String(item.questId));
    } catch (error) { if (error.code !== 'ENOENT') this.loadError = error; }
  }
  _save() {
    if (this.persistence) { this.persistence.save('mote-quests', this.snapshot()); return; }
    if (!this.snapshotPath) return;
    fs.mkdirSync(path.dirname(this.snapshotPath), { recursive: true });
    const temp = `${this.snapshotPath}.tmp`;
    fs.writeFileSync(temp, JSON.stringify(this.snapshot(), null, 2));
    fs.renameSync(temp, this.snapshotPath);
  }
  list() { return this.quests.map(q => ({ ...q, claimed: [...this.claimed.values()].includes(q.id) })); }
  claim(id, eventId) {
    const quest = this.quests.find(q => q.id === id);
    if (!quest) throw new Error('quest not found');
    const key = String(eventId || '').trim();
    if (!key) throw new Error('eventId is required');
    if (this.claimed.has(key)) return { duplicate: true, quest, state: this.snapshot() };
    this.claimed.set(key, id);
    this._save();
    return { duplicate: false, quest, state: this.snapshot() };
  }
  snapshot() { return { claimed: [...this.claimed.entries()].map(([eventId, questId]) => ({ eventId, questId })) }; }
}

module.exports = { MoteRelationshipStore, MoteQuestStore };
