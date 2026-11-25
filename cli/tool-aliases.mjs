// Rewrite common, unambiguous argument spellings to the canonical contract
// before validation. Every rewrite is a rename or a default with one meaning,
// and it produces a shape that 2.0.8/2.0.9 phones already accept. A canonical
// field always wins over its alias. Anything that needs a guess stays an error.
const PRIMITIVES = new Set(['observe', 'open', 'tap', 'type', 'key', 'gesture', 'scroll', 'wait']);
const TASK_OPERATIONS = new Set(['begin', 'inspect', 'list', 'finish', 'share', 'handoff', 'record_start', 'record_stop', 'record_status']);
const VIEW = 'android.intent.action.VIEW';
const EXTRA_TYPES = { int: 'integer', integer: 'integer', long: 'long', bool: 'boolean', boolean: 'boolean', str: 'string', string: 'string', uri: 'uri' };
const isObject = (v) => v && typeof v === 'object' && !Array.isArray(v);
const has = (o, k) => Object.hasOwn(o, k);
const isWebUrl = (v) => typeof v === 'string' && /^https?:\/\//i.test(v);
// Only a string with its own scheme (market:, geo:, tel:) is intent data; "www.example.com" stays a url error.
const hasScheme = (v) => /^[a-z][a-z0-9+.-]*:/i.test(v);

function fixIntent(intent) {
  if (!isObject(intent)) return intent;
  const out = { ...intent };
  if (typeof out.category === 'string' && !has(out, 'categories')) { out.categories = [out.category]; delete out.category; }
  if (Array.isArray(out.extras)) out.extras = out.extras.map((extra) => {
    if (!isObject(extra)) return extra;
    const e = { ...extra };
    if (has(e, 'key') && !has(e, 'name')) { e.name = e.key; delete e.key; }
    if (typeof e.type === 'string' && EXTRA_TYPES[e.type.toLowerCase()]) e.type = EXTRA_TYPES[e.type.toLowerCase()];
    if (typeof e.value === 'boolean' && !has(e, 'type')) e.type = 'boolean';
    if ((typeof e.value === 'number' || typeof e.value === 'boolean') && has(e, 'type')) e.value = String(e.value);
    return e;
  });
  return out;
}

// A non-web URL (market:, geo:, tel:, intent data) or a URL pinned to one app is a VIEW intent.
function urlAsIntent(args) {
  if (!has(args, 'url') || has(args, 'intent') || typeof args.url !== 'string') return args;
  const pinned = typeof args.package === 'string' && !has(args, 'activity');
  if (!hasScheme(args.url) || isWebUrl(args.url) && !pinned) return args;
  const { url, ...rest } = args;
  const intent = { action: VIEW, data: url };
  if (pinned) { intent.package = rest.package; delete rest.package; }
  return { ...rest, intent };
}

export function normalizeArguments(tool, rawArgs) {
  if (!isObject(rawArgs)) return rawArgs;
  const short = String(tool).replace(/^android[_.]/, '');
  let args = { ...rawArgs };
  if (short === 'task') {
    if (!has(args, 'operation') && TASK_OPERATIONS.has(args.action)) { args.operation = args.action; delete args.action; }
    if (args.operation === 'begin') {
      args = urlAsIntent(args);
      if (isObject(args.intent) && typeof args.package === 'string' && !has(args.intent, 'package')) {
        args.intent = { ...args.intent, package: args.package }; delete args.package;
      }
    }
    if (has(args, 'intent')) args.intent = fixIntent(args.intent);
  }
  if (short === 'open') {
    if (typeof args.action === 'string' && /^android\.settings\./.test(args.action) && !has(args, 'settingsAction')) {
      args.settingsAction = args.action; delete args.action;
    }
    args = urlAsIntent(args);
    if (has(args, 'intent')) args.intent = fixIntent(args.intent);
  }
  if (short === 'device') {
    if (args.operation === 'clipboard') {
      delete args.sessionId;
      const read = ['get', 'read', 'paste'], write = ['set', 'write', 'copy'];
      if (read.includes(args.action) && !has(args, 'text') || write.includes(args.action) && has(args, 'text')) delete args.action;
    }
    if (args.operation === 'media' && !has(args, 'action')) args.action = 'status';
  }
  if (short === 'gesture' && isObject(args.from) && isObject(args.to) && !['x1', 'y1', 'x2', 'y2'].some((k) => has(args, k))) {
    const { from, to, ...rest } = args;
    args = { preset: 'swipe', ...rest, x1: from.x, y1: from.y, x2: to.x, y2: to.y };
  }
  if (short === 'file' && args.operation === 'fetch' && has(args, 'path') && !has(args, 'remote')) {
    args.remote = args.path; delete args.path;
  }
  // {role:"scroll"} alone names no container; the phone then picks the main scroll area.
  if (short === 'scroll' && isObject(args.selector) && Object.keys(args.selector).length === 1 && args.selector.role === 'scroll') delete args.selector;
  if ((short === 'scroll' || short === 'scroll_until') && isObject(args.until)) {
    let until = { ...args.until };
    if (has(until, 'maxScrolls') && !has(args, 'maxScrolls')) { args.maxScrolls = until.maxScrolls; delete until.maxScrolls; }
    if (isObject(until.selector) && Object.keys(until).length === 1) until = until.selector;
    args.until = until;
  }
  if (short === 'type' && args.focused === true) {
    if (!has(args, 'clipboard')) args.clipboard = true;
    if (!has(args, 'mode')) args.mode = 'replace';
  }
  if (PRIMITIVES.has(short) && isObject(args.verify) && has(args.verify, 'timeoutMs') && !has(args, 'timeoutMs')) {
    const { timeoutMs, ...verify } = args.verify;
    args.timeoutMs = timeoutMs; args.verify = verify;
  }
  if (short === 'sequence' && Array.isArray(args.steps)) {
    // A top-level observation belongs to the steps that name a target from it.
    const shared = typeof args.observation === 'string' ? args.observation : null;
    if (shared) delete args.observation;
    args.steps = args.steps.map((raw) => {
      if (!isObject(raw)) return raw;
      let step = { ...raw };
      // {action:"tap", selector:{...}} or {operation:"tap", ...} is a step with its arguments inlined.
      for (const key of ['action', 'operation']) {
        if (!has(step, 'tool') && !has(step, 'arguments') && typeof step[key] === 'string' && PRIMITIVES.has(step[key].replace(/^android[_.]/, ''))) {
          const { [key]: name, before, optional, ...inline } = step;
          step = { tool: name, arguments: inline, ...(before !== undefined ? { before } : {}), ...(optional !== undefined ? { optional } : {}) };
        }
      }
      if (typeof step.tool === 'string') {
        const name = step.tool.replace(/^android[_.]/, '');
        if (PRIMITIVES.has(name)) {
          step.tool = 'android.' + name;
          if (isObject(step.arguments)) {
            step.arguments = normalizeArguments(name, step.arguments);
            if (shared && has(step.arguments, 'target') && !has(step.arguments, 'observation')) step.arguments = { ...step.arguments, observation: shared };
          }
        }
      }
      return step;
    });
  }
  return args;
}
