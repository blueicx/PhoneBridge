'use strict';

const crypto = require('node:crypto');

function clone(value) {
  return value == null ? value : JSON.parse(JSON.stringify(value));
}

class MemoryStore {
  constructor({ persistence = null, now = () => Date.now() } = {}) {
    this.persistence = persistence;
    this.now = now;
    this.state = { version: 1, revision: 0, entries: [] };
    this._load();
  }

  _load() {
    const saved = this.persistence?.load('ai-memory', null);
    if (!saved || typeof saved !== 'object') return;
    this.state = {
      version: 1,
      revision: Number(saved.revision) || 0,
      entries: Array.isArray(saved.entries) ? saved.entries.filter(item => item && item.id && item.text).map(item => ({
        ...item,
        status: item.status === 'candidate' ? 'candidate' : 'confirmed',
      })) : [],
    };
  }

  _save() {
    if (this.persistence && typeof this.persistence.save === 'function') this.persistence.save('ai-memory', this.state);
  }

  list({ query = '', limit = 50, sensitivity = null, status = null } = {}) {
    const needle = String(query || '').trim().toLowerCase();
    const items = this.state.entries
      .filter(item => !sensitivity || item.sensitivity === sensitivity)
      .filter(item => !status || item.status === String(status))
      .map(item => ({ item, score: needle ? score(item, needle) : 0 }))
      .filter(({ score }) => !needle || score > 0)
      .sort((a, b) => b.score - a.score || String(b.item.updatedAt).localeCompare(String(a.item.updatedAt)))
      .slice(0, Math.max(1, Math.min(200, Number(limit) || 50)))
      .map(({ item }) => clone(item));
    return items;
  }

  add({ text, source = 'user', sensitivity = 'normal', confidence = 1, id = null, status = null, explicit = false } = {}) {
    const value = String(text || '').trim().slice(0, 2000);
    if (!value) throw new Error('memory text is required');
    const timestamp = new Date(this.now()).toISOString();
    const entry = {
      id: String(id || `mem_${crypto.randomUUID()}`),
      text: value,
      source: String(source).slice(0, 80),
      status: status === 'candidate' || (!explicit && String(source).toLowerCase().includes('auto')) ? 'candidate' : 'confirmed',
      sensitivity: ['normal', 'sensitive'].includes(String(sensitivity)) ? String(sensitivity) : 'normal',
      confidence: Math.max(0, Math.min(1, Number(confidence) || 0)),
      createdAt: timestamp,
      updatedAt: timestamp,
      lastUsedAt: null,
    };
    const existing = this.state.entries.find(item => item.id === entry.id);
    if (existing) return { duplicate: true, entry: clone(existing), revision: this.state.revision };
    this.state.entries.push(entry);
    this.state.entries = this.state.entries.slice(-500);
    this.state.revision += 1;
    this._save();
    return { duplicate: false, entry: clone(entry), revision: this.state.revision };
  }

  update(id, patch = {}) {
    const entry = this.state.entries.find(item => item.id === String(id));
    if (!entry) throw new Error('memory not found');
    if (patch.text !== undefined) {
      const value = String(patch.text || '').trim().slice(0, 2000);
      if (!value) throw new Error('memory text is required');
      entry.text = value;
    }
    if (patch.sensitivity !== undefined && ['normal', 'sensitive'].includes(String(patch.sensitivity))) entry.sensitivity = String(patch.sensitivity);
    if (patch.confidence !== undefined) entry.confidence = Math.max(0, Math.min(1, Number(patch.confidence) || 0));
    if (patch.status !== undefined) {
      const nextStatus = String(patch.status);
      if (!['candidate', 'confirmed'].includes(nextStatus)) throw new Error('invalid memory status');
      entry.status = nextStatus;
    }
    entry.updatedAt = new Date(this.now()).toISOString();
    this.state.revision += 1;
    this._save();
    return { entry: clone(entry), revision: this.state.revision };
  }

  remove(id) {
    const before = this.state.entries.length;
    this.state.entries = this.state.entries.filter(item => item.id !== String(id));
    if (before === this.state.entries.length) return { removed: false, revision: this.state.revision };
    this.state.revision += 1;
    this._save();
    return { removed: true, revision: this.state.revision };
  }

  confirm(id) {
    return this.update(id, { status: 'confirmed' });
  }

  markUsed(ids = []) {
    const timestamp = new Date(this.now()).toISOString();
    const wanted = new Set(ids.map(String));
    let changed = false;
    for (const entry of this.state.entries) {
      if (wanted.has(entry.id)) {
        entry.lastUsedAt = timestamp;
        changed = true;
      }
    }
    if (changed) this._save();
  }

  export({ includeSensitive = false } = {}) {
    return this.state.entries
      .filter(item => includeSensitive || item.sensitivity !== 'sensitive')
      .map(clone);
  }

  selectForConversation({ remember = true, query = '', limit = 20 } = {}) {
    if (remember !== true) return [];
    return this.list({ query, limit, status: 'confirmed' });
  }

  snapshot() {
    return {
      version: this.state.version,
      revision: this.state.revision,
      count: this.state.entries.length,
      candidateCount: this.state.entries.filter(item => item.status === 'candidate').length,
      confirmedCount: this.state.entries.filter(item => item.status !== 'candidate').length,
    };
  }
}

function score(item, needle) {
  const text = String(item.text || '').toLowerCase();
  const words = needle.split(/\s+/).filter(Boolean);
  return words.reduce((total, word) => total + (text.includes(word) ? 1 : 0), 0);
}

module.exports = { MemoryStore };
