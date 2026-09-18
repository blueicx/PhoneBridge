const SECRET_KEY = /(token|secret|password|authorization|cookie|apikey|api_key|privatekey|accesskey)/i;
const SECRET_VALUE = /^(bearer\s+|sk-|AIzaSy|-----BEGIN)/i;

function redact(value, key = '') {
  if (value == null) return value;
  if (SECRET_KEY.test(String(key)) || (typeof value === 'string' && SECRET_VALUE.test(value))) return '[REDACTED]';
  if (Array.isArray(value)) return value.slice(0, 32).map(item => redact(item, key));
  if (typeof value === 'object') {
    const result = {};
    for (const [childKey, childValue] of Object.entries(value).slice(0, 64)) result[childKey] = redact(childValue, childKey);
    return result;
  }
  if (typeof value === 'string') return value.length > 500 ? `${value.slice(0, 499)}…` : value;
  return value;
}

function createStructuredLogger({ sink = line => process.stdout.write(`${line}\n`), now = () => Date.now(), context = {} } = {}) {
  const write = (level, event, data = {}) => {
    const record = { timestamp: new Date(now()).toISOString(), level, event: String(event), ...redact(context), data: redact(data) };
    sink(JSON.stringify(record));
    return record;
  };
  return { debug: (event, data) => write('debug', event, data), info: (event, data) => write('info', event, data), warn: (event, data) => write('warn', event, data), error: (event, data) => write('error', event, data), write };
}

module.exports = { createStructuredLogger, redact };
