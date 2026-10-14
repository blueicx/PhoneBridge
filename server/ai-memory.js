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
        source: String(item.source || 'user'),
        excludedFromRecall: item.excludedFromRecall === true,
        provenance: Array.isArray(item.provenance) ? [...new Set(item.provenance.map(String))].slice(0, 20) : [String(item.source || 'user')],
        mergedFrom: Array.isArray(item.mergedFrom) ? [...new Set(item.mergedFrom.map(String))].slice(0, 100) : [],
        lastUsedReason: String(item.lastUsedReason || ''),
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

  add({ text, source = 'user', sensitivity = 'normal', confidence = 1, id = null, status = null, explicit = false, excludedFromRecall = false } = {}) {
    const value = String(text || '').trim().slice(0, 2000);
    if (!value) throw new Error('memory text is required');
    const timestamp = new Date(this.now()).toISOString();
    const entry = {
      id: String(id || `mem_${crypto.randomUUID()}`),
      text: value,
      source: String(source).slice(0, 80),
      status: status === 'candidate' || (!explicit && String(source).toLowerCase().includes('auto')) ? 'candidate' : 'confirmed',
      sensitivity: ['normal', 'sensitive'].includes(String(sensitivity)) ? String(sensitivity) : 'normal',
      excludedFromRecall: excludedFromRecall === true,
      provenance: [String(source).slice(0, 80)],
      mergedFrom: [],
      confidence: Math.max(0, Math.min(1, Number(confidence) || 0)),
      createdAt: timestamp,
      updatedAt: timestamp,
      lastUsedAt: null,
      lastUsedReason: '',
    };
    const existing = this.state.entries.find(item => item.id === entry.id);
    if (existing) return { duplicate: true, entry: clone(existing), revision: this.state.revision };
    const conflicts = this.findPotentialConflicts(entry.text);
    this.state.entries.push(entry);
    this.state.entries = this.state.entries.slice(-500);
    this.state.revision += 1;
    this._save();
    return { duplicate: false, entry: clone(entry), conflicts, revision: this.state.revision };
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
    if (patch.excludedFromRecall !== undefined) entry.excludedFromRecall = patch.excludedFromRecall === true;
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

  clear() {
    const deleted = this.state.entries.length;
    if (!deleted) return { deleted: 0, revision: this.state.revision };
    this.state.entries = [];
    this.state.revision += 1;
    this._save();
    return { deleted, revision: this.state.revision };
  }

  confirm(id) {
    return this.update(id, { status: 'confirmed' });
  }

  markUsed(ids = [], reason = 'conversation_context') {
    const timestamp = new Date(this.now()).toISOString();
    const wanted = new Set(ids.map(String));
    let changed = false;
    for (const entry of this.state.entries) {
      if (wanted.has(entry.id)) {
        entry.lastUsedAt = timestamp;
        entry.lastUsedReason = String(reason || 'conversation_context').slice(0, 80);
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
    const boundedLimit = Math.max(1, Math.min(200, Number(limit) || 20));
    return this.list({ query, limit: 200, status: 'confirmed' })
      .filter(item => item.excludedFromRecall !== true)
      .slice(0, boundedLimit);
  }

  findPotentialConflicts(text, { excludeId = '' } = {}) {
    const candidateTokens = memoryTokens(text);
    const candidatePolarity = memoryPolarity(text);
    if (!candidateTokens.size || candidatePolarity === 0) return [];
    return this.state.entries
      .filter(item => item.id !== String(excludeId || '') && item.status === 'confirmed' && item.excludedFromRecall !== true)
      .map(item => {
        const existingPolarity = memoryPolarity(item.text);
        if (!existingPolarity || existingPolarity === candidatePolarity) return null;
        const existingTokens = memoryTokens(item.text);
        const shared = [...candidateTokens].filter(token => existingTokens.has(token)).length;
        const similarity = shared / Math.max(1, new Set([...candidateTokens, ...existingTokens]).size);
        if (similarity < 0.5) return null;
        return { ...clone(item), similarity: Number(similarity.toFixed(3)), reason: 'opposite_preference' };
      })
      .filter(Boolean)
      .sort((a, b) => b.similarity - a.similarity || String(b.updatedAt).localeCompare(String(a.updatedAt)))
      .slice(0, 20);
  }

  confirmMany(ids = []) {
    const wanted = new Set((Array.isArray(ids) ? ids : []).map(value => String(value || '').trim()).filter(Boolean));
    const timestamp = new Date(this.now()).toISOString();
    const confirmed = [];
    for (const entry of this.state.entries) {
      if (!wanted.has(entry.id) || entry.status !== 'candidate') continue;
      entry.status = 'confirmed';
      entry.updatedAt = timestamp;
      confirmed.push(clone(entry));
    }
    if (confirmed.length) {
      this.state.revision += 1;
      this._save();
    }
    return { confirmed, revision: this.state.revision };
  }

  mergeCandidates({ targetId, candidateIds = [], text = '' } = {}) {
    const target = this.state.entries.find(item => item.id === String(targetId || ''));
    if (!target) throw new Error('memory target not found');
    const requested = [...new Set((Array.isArray(candidateIds) ? candidateIds : []).map(value => String(value || '').trim()).filter(Boolean))]
      .filter(id => id !== target.id);
    const candidates = this.state.entries.filter(item => requested.includes(item.id) && item.status === 'candidate');
    const mergedText = String(text || target.text).trim().slice(0, 2000);
    if (!mergedText) throw new Error('memory text is required');
    if (!candidates.length) throw new Error('no mergeable memory candidates');
    const timestamp = new Date(this.now()).toISOString();
    const provenance = [...new Set([
      ...(Array.isArray(target.provenance) ? target.provenance : [target.source]),
      ...candidates.flatMap(item => Array.isArray(item.provenance) ? item.provenance : [item.source])
    ].map(value => String(value || '').trim()).filter(Boolean))].slice(0, 20);
    target.text = mergedText;
    target.provenance = provenance;
    target.mergedFrom = [...new Set([...(target.mergedFrom || []), ...candidates.map(item => item.id)])].slice(-100);
    target.updatedAt = timestamp;
    this.state.entries = this.state.entries.filter(item => !candidates.includes(item));
    this.state.revision += 1;
    this._save();
    return {
      entry: clone(target),
      conflicts: this.findPotentialConflicts(target.text, { excludeId: target.id }),
      removedIds: candidates.map(item => item.id),
      revision: this.state.revision
    };
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
  const query = String(needle || '').toLowerCase().trim();
  if (!query) return 0;
  if (text.includes(query)) return 1 + Math.min(1, query.length / 100);
  const queryTokens = memoryTokens(query);
  if (queryTokens.size < 2) return 0;
  const itemTokens = memoryTokens(text);
  const shared = [...queryTokens].filter(token => itemTokens.has(token)).length;
  const ratio = shared / queryTokens.size;
  return ratio >= 0.25 ? ratio : 0;
}

function memoryTokens(value) {
  const tokens = new Set();
  const runs = String(value || '').toLowerCase().replace(/[\s\p{P}\p{S}]+/gu, '').match(/[\p{Script=Han}]+|[a-z0-9]+/gu) || [];
  for (const run of runs) {
    if (/^[\p{Script=Han}]+$/u.test(run)) {
      if (run.length === 1) tokens.add(run);
      else for (let index = 0; index < run.length - 1; index += 1) tokens.add(run.slice(index, index + 2));
    } else tokens.add(run);
  }
  return tokens;
}

function memoryPolarity(value) {
  const text = String(value || '').toLowerCase();
  if (/(不喜欢|不想|不再|不要|不希望|避免|拒绝|讨厌|不能|不需要)/u.test(text)) return -1;
  if (/(喜欢|偏好|希望|可以|需要|想要)/u.test(text)) return 1;
  return 0;
}

module.exports = { MemoryStore };
