'use strict';

const DEFAULT_CELL_SIZE = 0.02;

function coarseRegion(latitude, longitude, cellSize = DEFAULT_CELL_SIZE) {
  const size = Number(cellSize);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || !Number.isFinite(size) || size <= 0) {
    throw new Error('valid latitude, longitude and cellSize are required');
  }
  return `cell:${Math.floor(Number(latitude) / size)}:${Math.floor(Number(longitude) / size)}`;
}

function seededValue(seed) {
  let hash = 2166136261;
  for (const char of String(seed)) {
    hash ^= char.charCodeAt(0);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0) / 4294967296;
}

class DeviceSimulator {
  constructor({ now = () => Date.now(), seed = 'phonebridge-sim', region = 'cell:0:0' } = {}) {
    this.now = now;
    this.seed = String(seed);
    this.state = {
      enabled: true,
      revision: 0,
      region: String(region),
      bearing: 0,
      distanceBand: 'near',
      clueType: 'location',
      network: 'online',
      battery: 82,
      temperature: 28,
      fps: 30,
      camera: false,
      microphone: false,
      updatedAt: this.now(),
    };
  }

  snapshot() {
    return { ...this.state, seed: this.seed };
  }

  apply(command = {}) {
    const type = String(command.type || 'tick');
    if (type === 'reset') {
      const fresh = new DeviceSimulator({ now: this.now, seed: this.seed, region: command.region || 'cell:0:0' });
      this.state = fresh.state;
      return this.snapshot();
    }
    if (type === 'region') {
      this.state.region = String(command.region || this.state.region);
      if (command.bearing !== undefined) this.state.bearing = normalizeBearing(command.bearing);
      if (command.distanceBand) this.state.distanceBand = normalizeDistance(command.distanceBand);
      if (command.clueType) this.state.clueType = normalizeClue(command.clueType);
    } else if (type === 'network') {
      this.state.network = command.online === false || command.network === 'offline' ? 'offline' : 'online';
    } else if (type === 'sensor') {
      this.state.camera = command.camera === true;
      this.state.microphone = command.microphone === true || command.audio === true;
    } else if (type === 'telemetry') {
      for (const key of ['battery', 'temperature', 'fps']) {
        if (command[key] !== undefined) this.state[key] = Number(command[key]);
      }
    } else if (type !== 'tick') {
      throw new Error(`unsupported simulator command: ${type}`);
    }
    if (type === 'tick') {
      const variation = seededValue(`${this.seed}:${this.state.revision + 1}`);
      this.state.bearing = normalizeBearing(this.state.bearing + Math.round((variation - 0.5) * 18));
      this.state.fps = Math.max(12, Math.min(60, this.state.fps));
      this.state.temperature = Math.max(18, Math.min(48, this.state.temperature + (this.state.camera ? 0.2 : -0.05)));
      this.state.battery = Math.max(0, Math.min(100, this.state.battery - (this.state.camera ? 0.03 : 0.005)));
    }
    this.state.revision += 1;
    this.state.updatedAt = this.now();
    return this.snapshot();
  }

  realityEvent() {
    const value = seededValue(`${this.seed}:${this.state.region}:${Math.floor(this.state.updatedAt / 1800000)}`);
    const clueTypes = ['location', 'object', 'light'];
    return {
      id: `sim:${this.state.region}:${Math.floor(this.state.updatedAt / 1800000)}`,
      region: this.state.region,
      kind: 'mote',
      clueType: clueTypes[Math.floor(value * clueTypes.length)],
      bearing: this.state.bearing,
      distanceBand: this.state.distanceBand,
      seed: `${this.seed}:${Math.floor(value * 100000)}`,
      expiresAt: this.state.updatedAt + 1800000,
    };
  }
}

function normalizeBearing(value) {
  return ((Math.round(Number(value) || 0) % 360) + 360) % 360;
}

function normalizeDistance(value) {
  return ['near', 'mid', 'far'].includes(String(value)) ? String(value) : 'near';
}

function normalizeClue(value) {
  return ['location', 'object', 'light'].includes(String(value)) ? String(value) : 'location';
}

module.exports = { DeviceSimulator, coarseRegion, seededValue };
