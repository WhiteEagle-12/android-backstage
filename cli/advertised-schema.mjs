// MCP clients disagree on conditional JSON Schema rendering, and large repeated
// schemas bloat every session's prompt. Advertise flat, ultra-lean typed shapes
// (~1.5k-2.0k tokens total) while gateway and phone enforce the full strict schema.
const UI_ACTIONS = new Set(['open', 'tap', 'type', 'key', 'gesture', 'scroll', 'wait']);
const UI_TOOLS = new Set(['observe', 'sequence', ...UI_ACTIONS]);
const OMIT_ON_ACTIONS = new Set([
  'display', 'detail', 'timing', 'maxItems', 'since', 'full', 'scale',
  'expectedPackage', 'timeoutMs', 'windowId', 'observe', 'verify', 'image',
]);
const OMIT_PER_TOOL = {
  observe: new Set(['observe', 'expectedPackage', 'timeoutMs', 'scale', 'maxItems', 'timing', 'since']),
  open: new Set(['activityTaskId']),
  type: new Set(['screenshotId']),
  key: new Set(['holdMs', 'repeat', 'screenshotId']),
  scroll: new Set(['amount', 'maxScrolls']),
  task: new Set(['leaseMinutes', 'width', 'height', 'dpi', 'activityTaskId', 'maxSeconds', 'fps', 'scale']),
  gesture: new Set(['angleDegrees', 'durationMs', 'holdMs']),
  file: new Set(['fileId', 'fileKey', 'sha256']),
  device: new Set(['positionMs', 'maxAgeMs', 'accuracyMeters', 'timeoutMs', 'sampleRate', 'channels']),
  events: new Set(['since', 'limit', 'senderContains', 'textContains', 'secret', 'batteryBelow']),
  personal: new Set(['limit', 'message', 'itemId', 'clear']),
  packages: new Set(['fields', 'detail']),
  logs: new Set(['maxLines', 'max', 'since']),
  confirm: new Set(['timeoutMs']),
  sequence: new Set(['deadlineMs', 'timing']),
  notification: new Set(['max', 'package']),
};
const STRIP_CONSTRAINTS = new Set([
  'minLength', 'maxLength', 'pattern', 'minimum', 'maximum', 'exclusiveMinimum',
  'minItems', 'maxItems', 'uniqueItems', 'minProperties', 'maxProperties', 'additionalProperties',
  'description',
]);
const COLLAPSE_OBJECT = new Set(['selector', 'until', 'verify', 'before', 'intent', 'share', 'source', 'responses']);
const COLLAPSE_STRING_ARRAY = new Set(['modifiers', 'fields', 'notificationPackages']);

function stripConstraints(node) {
  if (!node || typeof node !== 'object') return node;
  if (Array.isArray(node)) return node.map(stripConstraints);
  const out = {};
  for (const [k, v] of Object.entries(node)) {
    if (STRIP_CONSTRAINTS.has(k)) continue;
    out[k] = k === 'properties'
      ? Object.fromEntries(Object.entries(v).map(([pk, pv]) => [pk, stripConstraints(pv)]))
      : k === 'items'
        ? stripConstraints(v)
        : v;
  }
  return out;
}

function flatten(schema, root = schema) {
  if (schema.$ref) {
    const { $ref, ...rest } = schema;
    return { ...flatten($ref.slice(2).split('/').reduce((v, k) => v[k], root), root), ...rest };
  }
  if (Number.isInteger(schema['x-backstage-preferred'])) {
    return flatten(schema.oneOf[schema['x-backstage-preferred']], root);
  }
  const branches = schema.oneOf || schema.anyOf;
  if (branches) {
    const choices = branches.map((s) => flatten(s, root));
    if (choices.every((s) => s.type === 'object')) {
      const keys = [...new Set(choices.flatMap((s) => Object.keys(s.properties || {})))];
      return {
        type: 'object',
        ...(choices.every((s) => s.description === choices[0].description) && choices[0].description
          ? { description: choices[0].description } : {}),
        properties: Object.fromEntries(keys.map((k) => {
          const alternatives = choices.filter((s) => s.properties?.[k]).map((s) => s.properties[k]);
          const unique = [...new Map(alternatives.map((s) => [JSON.stringify(s), s])).values()];
          return [k, unique.length === 1 ? unique[0] : flatten({ anyOf: unique }, root)];
        })),
        required: (choices[0].required || []).filter((k) => choices.every((s) => s.required?.includes(k))),
        additionalProperties: false,
      };
    }
    const values = choices.flatMap((s) => s.enum || []);
    if (values.length && choices.every((s) => s.enum)) return { type: typeof values[0], enum: [...new Set(values)] };
    const withoutHelp = choices.map(({ description, ...shape }) => shape);
    if (withoutHelp.every((s) => JSON.stringify(s) === JSON.stringify(withoutHelp[0]))) return withoutHelp[0];
    throw new Error('Unsupported advertised schema union');
  }
  const out = {};
  for (const [key, value] of Object.entries(schema)) {
    if (['allOf', 'not', '$defs'].includes(key)) continue;
    if (key === 'properties') out[key] = Object.fromEntries(Object.entries(value).map(([k, s]) => [k, flatten(s, root)]));
    else if (key === 'items') out[key] = flatten(value, root);
    else if (key === 'const') { out.type = typeof value; out.enum = [value]; }
    else out[key] = value;
  }
  if (out.enum && !out.type) out.type = typeof out.enum[0];
  return out;
}

export function advertisedSchema(schema, root = schema, opts = {}) {
  const flat = flatten(schema, root);
  if (!opts.toolName) return flat;
  const short = String(opts.toolName).replace(/^android[_.]/, '');
  const slim = stripConstraints(flat);
  if (slim.type !== 'object' || !slim.properties) return slim;

  const omitTool = OMIT_PER_TOOL[short];
  const props = {};
  for (const [k, raw] of Object.entries(slim.properties)) {
    if (k === 'requestId') continue;
    if (UI_TOOLS.has(short) && k === 'display') continue;
    if (UI_ACTIONS.has(short) && OMIT_ON_ACTIONS.has(k) && !slim.required?.includes(k)) continue;
    if (omitTool?.has(k) && !slim.required?.includes(k)) continue;
    let spec = raw;
    if (COLLAPSE_OBJECT.has(k)) {
      spec = { type: 'object' };
    } else if (COLLAPSE_STRING_ARRAY.has(k)) {
      spec = { type: 'array', items: { type: 'string' } };
    } else if (spec.type === 'boolean' && spec.enum) {
      spec = { type: 'boolean' };
    } else if (k === 'image') {
      spec = short === 'observe'
        ? { type: 'string', enum: ['never', 'always', 'if_needed', 'only'] }
        : { type: 'string' };
    } else if (short === 'key' && k === 'key') {
      spec = { type: 'string' };
    } else if (short === 'gesture' && k === 'paths') {
      spec = { type: 'array' };
    } else if (short === 'device' && (k === 'action' || k === 'stream' || k === 'rotation')) {
      spec = { type: 'string' };
    } else if (short === 'sequence' && k === 'steps') {
      spec = { type: 'array', items: { type: 'object' } };
    }
    props[k] = spec;
  }
  const required = (slim.required || []).filter((k) => k !== 'requestId');
  return {
    type: 'object',
    properties: props,
    ...(required.length ? { required } : {}),
  };
}
