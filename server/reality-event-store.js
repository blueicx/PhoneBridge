'use strict';

const crypto = require('node:crypto');
const { dayKey } = require('./mote-growth');

const BUCKET_MS = 30 * 60 * 1000;

function hash(value) { return crypto.createHash('sha256').update(String(value)).digest('hex'); }

function normalizeRegion(value) {
  const region = String(value || '').trim();
  if (region === 'camera' || /^cell:-?\d+:-?\d+$/.test(region)) return region;
  throw new Error('coarse region is required');
}

function bearingFrom(seed) { return parseInt(seed.slice(0, 4), 16) % 360; }
function distanceFrom(seed) { return ['near', 'mid', 'far'][parseInt(seed.slice(4, 6), 16) % 3]; }

class RealityEventStore {
  constructor({ templates = [], now = () => Date.now(), bucketMs = BUCKET_MS } = {}) {
    this.templates = Array.isArray(templates) ? templates.map(item => ({ ...item })) : [];
    this.now = now;
    this.bucketMs = Math.max(60_000, Number(bucketMs) || BUCKET_MS);
  }

  eventsFor(region, at = this.now()) {
    const normalized = normalizeRegion(region);
    const timestamp = Number(at);
    if (!Number.isFinite(timestamp)) throw new Error('invalid event time');
    const bucket = Math.floor(timestamp / this.bucketMs);
    const seed = hash(`${normalized}:${bucket}`);
    return this.templates.slice(0, 8).map((template, index) => {
      const eventSeed = hash(`${seed}:${template.templateId}`);
      return {
        id: `reality:v2:${dayKey(timestamp)}:${normalized}:${template.clueType}:${bucket}:${template.templateId}`,
        region: normalized,
        kind: String(template.kind || 'mote'),
        clueType: String(template.clueType || 'location'),
        difficulty: Number(template.difficulty) || 1,
        xp: Number(template.xp) || 0,
        bearing: (bearingFrom(eventSeed) + index * 17) % 360,
        distanceBand: distanceFrom(eventSeed),
        seed: eventSeed.slice(0, 16),
        expiresAt: (bucket + 1) * this.bucketMs,
        rewardPreview: template.rewardItem || null,
      };
    });
  }
}

module.exports = { BUCKET_MS, RealityEventStore, normalizeRegion };
