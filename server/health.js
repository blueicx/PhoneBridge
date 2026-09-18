class HealthChecks {
  constructor({ dependencies = {}, now = () => Date.now() } = {}) {
    this.dependencies = { ...dependencies };
    this.now = now;
  }

  liveness() {
    return { ok: true, status: 'alive' };
  }

  readiness() {
    const checks = {};
    let ok = true;
    for (const [name, dependency] of Object.entries(this.dependencies)) {
      try {
        const result = typeof dependency === 'function' ? dependency() : dependency;
        checks[name] = result && typeof result === 'object' ? result : { ok: Boolean(result) };
      } catch (error) {
        checks[name] = { ok: false, error: String(error.message || error) };
      }
      if (!checks[name].ok) ok = false;
    }
    return { ok, status: ok ? 'ready' : 'not_ready', checkedAt: new Date(this.now()).toISOString(), checks };
  }
}

module.exports = { HealthChecks };
