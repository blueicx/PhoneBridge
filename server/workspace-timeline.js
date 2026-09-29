const crypto = require('node:crypto');

function iso(timestamp = Date.now()) {
  return new Date(timestamp).toISOString();
}

function clone(value) {
  return value == null ? value : JSON.parse(JSON.stringify(value));
}

class WorkspaceTimeline {
  constructor({ retention = 500, now = () => Date.now(), persistence = null } = {}) {
    this.retention = Math.max(1, Number(retention) || 500);
    this.now = now;
    this.persistence = persistence;
    this.headRevision = 0;
    this.minimumSnapshotRevision = 0;
    this.events = [];
    this.eventsById = new Map();
    this.entityVersions = new Map(); // key: `${entityType}:${entityId}` => version (int)

    // In-memory active projection
    this.projection = {
      tasks: new Map(),
      messages: new Map(),
      attention: new Map(),
      mote: {
        profileId: 'rimuru',
        name: '利姆鲁',
        active: true,
        level: 1,
        xp: 0,
        interactions: 0,
        mood: 80,
        gaze: 'ambient',
        reminderStrength: 0.0,
        updatedAt: iso(this.now())
      },
      health: {
        connected: false,
        battery: 100,
        memory: 0,
        temperature: 25.0,
        networkRx: 0,
        networkTx: 0,
        sensors: { camera: false, audio: false },
        updatedAt: iso(this.now())
      },
      autonomy: {
        level: 'whitelist',
        allowedTools: [],
        emergencyStop: false,
        pendingApprovals: 0,
        updatedAt: iso(this.now())
      }
    };
    this._load();
  }

  _load() {
    if (!this.persistence) return;
    const saved = this.persistence.load('workspace-timeline', {});
    this.headRevision = Math.max(0, Number(saved.headRevision) || 0);
    this.minimumSnapshotRevision = Math.max(0, Number(saved.minimumSnapshotRevision) || 0);
    this.entityVersions = new Map(Array.isArray(saved.entityVersions) ? saved.entityVersions : []);
    this.events = Array.isArray(saved.events) ? saved.events.slice(-this.retention) : [];
    this.eventsById = new Map(this.events.filter(event => event?.eventId).map(event => [event.eventId, event]));
    for (const event of this.events) this._applyToProjection(event);
  }

  _persist() {
    if (!this.persistence) return;
    this.persistence.save('workspace-timeline', {
      headRevision: this.headRevision,
      minimumSnapshotRevision: this.minimumSnapshotRevision,
      entityVersions: [...this.entityVersions.entries()],
      events: this.events,
    });
  }

  _entityKey(entityType, entityId) {
    return `${entityType}:${entityId}`;
  }

  _nextEntityVersion(entityType, entityId) {
    const key = this._entityKey(entityType, entityId);
    const current = this.entityVersions.get(key) || 0;
    const next = current + 1;
    this.entityVersions.set(key, next);
    return next;
  }

  recordEvent({
    eventId = null,
    entityType,
    entityId,
    operation = 'update',
    payload = {},
    deleted = false,
    timestamp = null
  }) {
    if (!entityType || !entityId) {
      throw new Error('entityType and entityId are required for timeline events');
    }

    const id = eventId || `tl_evt_${crypto.randomUUID()}`;
    const previous = this.eventsById.get(id);
    if (previous) return clone(previous);

    const nextRevision = ++this.headRevision;
    const entityVersion = this._nextEntityVersion(entityType, entityId);
    const eventTime = timestamp || iso(this.now());

    const envelope = {
      eventId: id,
      revision: nextRevision,
      entity: String(entityType),
      createdAt: eventTime,
      timestamp: eventTime,
      entityType: String(entityType),
      entityId: String(entityId),
      entityVersion,
      operation: String(operation),
      deleted: Boolean(deleted),
      payload: clone(payload)
    };

    this.events.push(envelope);
    this.eventsById.set(id, envelope);
    if (this.events.length > this.retention) {
      this.events.splice(0, this.events.length - this.retention);
    }

    this._applyToProjection(envelope);
    this._persist();
    return clone(envelope);
  }

  deleteEntity(entityType, entityId) {
    return this.recordEvent({
      entityType,
      entityId,
      operation: 'delete',
      deleted: true,
      payload: { id: entityId, deleted: true, updatedAt: iso(this.now()) }
    });
  }

  purgePersonalData(entityTypes = [], entityIdsByType = {}) {
    const types = new Set((Array.isArray(entityTypes) ? entityTypes : []).map(String));
    if (!types.size) throw new Error('at least one entity type is required');
    for (const type of types) {
      const hasSelection = Object.prototype.hasOwnProperty.call(entityIdsByType || {}, type);
      const selectedIds = new Set((hasSelection && Array.isArray(entityIdsByType[type]) ? entityIdsByType[type] : []).map(String));
      if (hasSelection) {
        this.events = this.events.filter(event => String(event.entityType || event.entity) !== type || !selectedIds.has(String(event.entityId || '')));
      } else {
        this.events = this.events.filter(event => String(event.entityType || event.entity) !== type);
      }
      const projection = type === 'task' ? this.projection.tasks
        : type === 'chat' ? this.projection.messages
          : type === 'attention' ? this.projection.attention : null;
      if (projection) {
        if (hasSelection) selectedIds.forEach(id => projection.delete(id));
        else projection.clear();
      }
      if (type === 'mote') this.projection.mote = {
        profileId: 'rimuru', name: '利姆鲁', active: true, level: 1, xp: 0, interactions: 0,
        mood: 80, gaze: 'ambient', reminderStrength: 0.0, updatedAt: iso(this.now()),
      };
      for (const key of this.entityVersions.keys()) {
        if (hasSelection ? selectedIds.has(key.slice(type.length + 1)) && key.startsWith(`${type}:`) : key.startsWith(`${type}:`)) {
          this.entityVersions.delete(key);
        }
      }
    }
    this.eventsById = new Map(this.events.filter(event => event?.eventId).map(event => [event.eventId, event]));
    this.headRevision += 1;
    this.minimumSnapshotRevision = this.headRevision;
    this._persist();
    return this.headRevision;
  }

  _applyToProjection(event) {
    const { entityType, entityId, operation, deleted, payload } = event;

    if (deleted || operation === 'delete') {
      if (entityType === 'task') this.projection.tasks.delete(entityId);
      else if (entityType === 'chat') this.projection.messages.delete(entityId);
      else if (entityType === 'attention') this.projection.attention.delete(entityId);
      return;
    }

    switch (entityType) {
      case 'task': {
        const existing = this.projection.tasks.get(entityId) || {};
        this.projection.tasks.set(entityId, { ...existing, ...payload, entityVersion: event.entityVersion });
        break;
      }
      case 'chat': {
        this.projection.messages.set(entityId, { ...payload, entityVersion: event.entityVersion });
        break;
      }
      case 'attention': {
        const existing = this.projection.attention.get(entityId) || {};
        this.projection.attention.set(entityId, { ...existing, ...payload, entityVersion: event.entityVersion });
        break;
      }
      case 'mote': {
        this.projection.mote = { ...this.projection.mote, ...payload, entityVersion: event.entityVersion };
        break;
      }
      case 'health': {
        this.projection.health = { ...this.projection.health, ...payload, entityVersion: event.entityVersion };
        break;
      }
      case 'autonomy': {
        this.projection.autonomy = { ...this.projection.autonomy, ...payload, entityVersion: event.entityVersion };
        break;
      }
    }
  }

  getSnapshot() {
    return {
      revision: this.headRevision,
      tasks: [...this.projection.tasks.values()].map(clone),
      messages: [...this.projection.messages.values()].map(clone),
      attention: [...this.projection.attention.values()].map(clone),
      mote: clone(this.projection.mote),
      health: clone(this.projection.health),
      autonomy: clone(this.projection.autonomy)
    };
  }

  seedSnapshot(snapshot = {}) {
    for (const task of snapshot.tasks || []) if (task?.id) this.projection.tasks.set(String(task.id), clone(task));
    for (const message of snapshot.messages || []) if (message?.id) this.projection.messages.set(String(message.id), clone(message));
    for (const item of snapshot.attention || []) if (item?.id) this.projection.attention.set(String(item.id), clone(item));
    if (snapshot.mote) this.projection.mote = { ...this.projection.mote, ...clone(snapshot.mote) };
    if (snapshot.health) this.projection.health = { ...this.projection.health, ...clone(snapshot.health) };
    if (snapshot.autonomy) this.projection.autonomy = { ...this.projection.autonomy, ...clone(snapshot.autonomy) };
  }

  query({ cursor = 0, limit = 50, entityType = null } = {}) {
    const startRevision = Math.max(0, Number(cursor) || 0);
    const maxLimit = Math.max(1, Math.min(200, Number(limit) || 50));

    let matching = this.events.filter(e => e.revision > startRevision);
    if (entityType) {
      matching = matching.filter(e => e.entityType === entityType);
    }

    const items = matching.slice(0, maxLimit);
    const hasMore = matching.length > maxLimit;
    const lastItem = items.length > 0 ? items[items.length - 1] : null;
    const nextCursor = hasMore && lastItem ? lastItem.revision : null;

    return {
      ok: true,
      revision: this.headRevision,
      cursor: lastItem ? lastItem.revision : startRevision,
      nextCursor,
      hasMore,
      events: items.map(clone)
    };
  }

  sync({ cursor = 0, limit = 100, entityType = null } = {}) {
    const fromRevision = Math.max(0, Number(cursor) || 0);
    const oldestEvent = this.events[0];
    const oldestRevision = oldestEvent ? oldestEvent.revision : 0;

    // If cursor is 0, or if events have been pruned beyond retention
    const resetRequired = fromRevision > this.headRevision || fromRevision < this.minimumSnapshotRevision ||
      (this.events.length > 0 && fromRevision < oldestRevision - 1);

    if (resetRequired || fromRevision === 0) {
      return {
        ok: true,
        mode: 'snapshot',
        resetRequired: true,
        revision: this.headRevision,
        fromRevision,
        toRevision: this.headRevision,
        snapshot: this.getSnapshot(),
        events: []
      };
    }

    const delta = this.query({ cursor: fromRevision, limit, entityType });
    return {
      ok: true,
      mode: 'delta',
      resetRequired: false,
      revision: this.headRevision,
      fromRevision,
      toRevision: this.headRevision,
      events: delta.events,
      hasMore: delta.hasMore,
      nextCursor: delta.nextCursor
    };
  }
}

module.exports = {
  WorkspaceTimeline
};
