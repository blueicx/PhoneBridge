const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { WorkspaceTimeline } = require('./workspace-timeline');

test('protocol fixture workspace-timeline.json conforms to unified timeline schema', () => {
  const fixturePath = path.join(__dirname, '..', 'protocol-fixtures', 'workspace-timeline.json');
  assert.ok(fs.existsSync(fixturePath), 'fixture file must exist');
  const fixture = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));

  assert.equal(typeof fixture.revision, 'number');
  assert.ok(Array.isArray(fixture.events), 'events must be an array');
  assert.ok(fixture.events.length >= 6, 'must contain sample events across domain entities');
  assert.ok(fixture.snapshot && typeof fixture.snapshot === 'object', 'must contain snapshot projection');
  assert.ok(fixture.mockInput && typeof fixture.mockInput === 'object', 'must contain mock inputs');

  // Verify entity types present
  const entityTypes = new Set(fixture.events.map(e => e.entityType));
  for (const expected of ['task', 'chat', 'attention', 'mote', 'health']) {
    assert.ok(entityTypes.has(expected), `fixture must contain ${expected} event`);
  }

  // Verify deletion event
  const deleteEvt = fixture.events.find(e => e.operation === 'delete');
  assert.ok(deleteEvt, 'fixture must contain a delete operation');
  assert.equal(deleteEvt.deleted, true);

  // Verify GPS placeholder input
  assert.ok(fixture.mockInput.gps, 'must have gps placeholder');
  assert.equal(typeof fixture.mockInput.gps.latitude, 'number');
  assert.equal(typeof fixture.mockInput.gps.longitude, 'number');
});

test('WorkspaceTimeline tracks monotonic revisions and per-entity versions', () => {
  const timeline = new WorkspaceTimeline();

  const e1 = timeline.recordEvent({
    entityType: 'task',
    entityId: 'task_1',
    operation: 'create',
    payload: { id: 'task_1', title: 'Task 1', state: 'pending' }
  });

  assert.equal(e1.revision, 1);
  assert.equal(e1.entityVersion, 1);
  assert.equal(timeline.headRevision, 1);

  const e2 = timeline.recordEvent({
    entityType: 'task',
    entityId: 'task_1',
    operation: 'update',
    payload: { id: 'task_1', title: 'Task 1', state: 'running' }
  });

  assert.equal(e2.revision, 2);
  assert.equal(e2.entityVersion, 2);
  assert.equal(timeline.headRevision, 2);

  const e3 = timeline.recordEvent({
    entityType: 'chat',
    entityId: 'msg_1',
    operation: 'create',
    payload: { id: 'msg_1', text: 'Hello', relatedTaskId: 'task_1' }
  });

  assert.equal(e3.revision, 3);
  assert.equal(e3.entityVersion, 1);
  assert.equal(timeline.headRevision, 3);
});

test('WorkspaceTimeline supports cursor-based pagination', () => {
  const timeline = new WorkspaceTimeline();

  for (let i = 1; i <= 5; i++) {
    timeline.recordEvent({
      entityType: 'task',
      entityId: `task_${i}`,
      operation: 'create',
      payload: { id: `task_${i}`, title: `Task ${i}` }
    });
  }

  const page1 = timeline.query({ cursor: 0, limit: 2 });
  assert.equal(page1.events.length, 2);
  assert.equal(page1.events[0].revision, 1);
  assert.equal(page1.events[1].revision, 2);
  assert.equal(page1.cursor, 2);
  assert.equal(page1.hasMore, true);
  assert.equal(page1.nextCursor, 2);

  const page2 = timeline.query({ cursor: page1.nextCursor, limit: 2 });
  assert.equal(page2.events.length, 2);
  assert.equal(page2.events[0].revision, 3);
  assert.equal(page2.events[1].revision, 4);
  assert.equal(page2.hasMore, true);

  const page3 = timeline.query({ cursor: page2.nextCursor, limit: 2 });
  assert.equal(page3.events.length, 1);
  assert.equal(page3.events[0].revision, 5);
  assert.equal(page3.hasMore, false);
  assert.equal(page3.nextCursor, null);
});

test('WorkspaceTimeline supports deletion operation and updates projection', () => {
  const timeline = new WorkspaceTimeline();

  timeline.recordEvent({
    entityType: 'task',
    entityId: 'task_del',
    operation: 'create',
    payload: { id: 'task_del', title: 'To Delete', state: 'pending' }
  });

  let snapshot = timeline.getSnapshot();
  assert.equal(snapshot.tasks.length, 1);
  assert.equal(snapshot.tasks[0].id, 'task_del');

  const delEvent = timeline.deleteEntity('task', 'task_del');
  assert.equal(delEvent.operation, 'delete');
  assert.equal(delEvent.deleted, true);
  assert.equal(delEvent.entityId, 'task_del');

  snapshot = timeline.getSnapshot();
  assert.equal(snapshot.tasks.length, 0, 'deleted entity should be removed from active snapshot projection');
});

test('WorkspaceTimeline handles delta vs snapshot recovery when cursor is too old', () => {
  const timeline = new WorkspaceTimeline({ retention: 3 });

  for (let i = 1; i <= 5; i++) {
    timeline.recordEvent({
      entityType: 'chat',
      entityId: `msg_${i}`,
      operation: 'create',
      payload: { id: `msg_${i}`, text: `Msg ${i}` }
    });
  }

  // Cursor 1 is too old since retention is 3 and we have 5 events (events are 3, 4, 5)
  const result = timeline.sync({ cursor: 1 });
  assert.equal(result.resetRequired, true);
  assert.equal(result.mode, 'snapshot');
  assert.ok(result.snapshot, 'snapshot must be included on reset');

  // Cursor 3 is within window
  const deltaResult = timeline.sync({ cursor: 3 });
  assert.equal(deltaResult.resetRequired, false);
  assert.equal(deltaResult.mode, 'delta');
  assert.equal(deltaResult.events.length, 2);
  assert.equal(deltaResult.events[0].revision, 4);
  assert.equal(deltaResult.events[1].revision, 5);
});

test('WorkspaceTimeline keeps canonical protocol aliases and deduplicates repeated eventIds', () => {
  const timeline = new WorkspaceTimeline();
  const first = timeline.recordEvent({
    eventId: 'evt_idempotent',
    entityType: 'task',
    entityId: 'task_1',
    operation: 'create',
    timestamp: '2026-09-12T10:00:00.000Z',
    payload: { id: 'task_1', title: '一次事件' }
  });

  const duplicate = timeline.recordEvent({
    eventId: 'evt_idempotent',
    entityType: 'task',
    entityId: 'task_1',
    operation: 'update',
    payload: { id: 'task_1', title: '不应重复写入' }
  });

  assert.deepEqual(duplicate, first);
  assert.equal(timeline.headRevision, 1);
  assert.equal(timeline.query({}).events.length, 1);
  assert.equal(first.entity, 'task');
  assert.equal(first.createdAt, '2026-09-12T10:00:00.000Z');
  assert.equal(first.entityType, first.entity);
  assert.equal(first.timestamp, first.createdAt);
});
