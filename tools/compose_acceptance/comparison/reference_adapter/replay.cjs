#!/usr/bin/env node
'use strict'

const assert = require('node:assert/strict')
const {execFileSync} = require('node:child_process')
const {createHash} = require('node:crypto')
const {readFileSync, realpathSync} = require('node:fs')
const {dirname, join, resolve} = require('node:path')

const [snapshot, capturePath, stepsPath] = process.argv.slice(2)
assert(snapshot && capturePath && stepsPath, 'Usage: replay.cjs SNAPSHOT RESULT_JSON STEPS_JSON')
const root = realpathSync(snapshot)
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
const captureBytes = readFileSync(capturePath)
const capture = JSON.parse(captureBytes)
assert.equal(capture.mode, 'live')
assert.equal(capture.status, 'complete')
assert.equal(capture.host_outcome, 'built')
const git = (...args) => execFileSync('git', ['-C', root, ...args], {encoding: 'utf8'}).trim()
assert.equal(git('rev-parse', 'HEAD'), capture.source.revision, 'Reference revision differs.')
assert.equal(git('status', '--porcelain', '--untracked-files=all'), '', 'Use a clean reference checkout.')
assert.equal(hash(readFileSync(process.execPath)), capture.runtime.node_sha256, 'Node differs from capture.')
assert.equal(hash(readFileSync(process.env.VERA_TYPESCRIPT_PATH)), capture.runtime.typescript_sha256,
  'TypeScript differs from capture.')
const program = readFileSync(join(dirname(resolve(capturePath)), 'program.vera'))
const artifact = hash(program)
assert.equal(artifact, capture.artifacts['program.vera'].sha256, 'Program differs from capture.')
const steps = JSON.parse(readFileSync(stepsPath))
assert(Array.isArray(steps) && steps.length <= 64, 'Use at most 64 interaction steps.')

delete process.env.VERA_SOURCE_ROOT
const {loadVera} = require(join(root, 'tools/vera-harness/source-loader.js'))
const {compileVeraSource} = loadVera('VeraCompiler')
const {VirtualMachine, deserializeVbc2, VeraBooleanValue, VeraNumberValue, VeraStringValue} = loadVera('VeraInterpreter')
const {UiHostEnvironment, decodeVeraUi} = loadVera('VeraUi')
const {decodeGfx, pointerValue} = loadVera('VeraGfx')
const host = new UiHostEnvironment()
const vm = new VirtualMachine(deserializeVbc2(compileVeraSource(program.toString('utf8')).vbc2), host)
let state = vm.execute('init')
const pending = new Map()

function dispatch(action, input) {
  host.effectsAllowed = true
  try {
    state = vm.execute(action, [state, input])
  } finally {
    host.effectsAllowed = false
  }
}

function captureEffects() {
  return host.pendingEffects.splice(0).map(effect => {
    pending.set(effect.id, effect)
    return {id: effect.id, target: effect.rawTarget, values: Object.fromEntries(effect.values)}
  })
}

function visibleNode(node) {
  const children = node.children
  if (node.kind !== 'tabs' || !children.length) return {...node, children: children.map(visibleNode)}
  const index = Math.max(0, Math.min(Math.floor(node.eventValue), children.length - 1))
  return {...node, children: [visibleNode(children[index])]}
}

function* descendants(nodes) {
  for (const node of nodes) {
    yield node
    yield* descendants(node.children)
  }
}

const view = () => decodeVeraUi(vm.execute('view', [state])).map(visibleNode)
const controls = (kind, label) => [...descendants(view())].filter(node => node.kind === kind &&
  (label === undefined || node.label === label || node.text === label))
function captureView() {
  const tree = view()
  const drawings = [...descendants(tree)].filter(node => node.kind === 'surface')
    .map(node => decodeGfx(vm.execute(node.label, [state])))
  return {tree, drawings}
}

function controlInput(node, value) {
  switch (node.kind) {
    case 'field':
      assert.equal(typeof value, 'string')
      return new VeraStringValue(value)
    case 'toggle':
      assert.equal(typeof value, 'boolean')
      return new VeraBooleanValue(value)
    case 'select':
    case 'tabs':
      assert(Number.isInteger(value) && value >= 0 && value < node.options.length)
      return new VeraNumberValue(value)
    case 'slider':
      assert(Number.isFinite(value) && value >= node.minimum && value <= node.maximum)
      return new VeraNumberValue(Math.round(value))
    case 'numberfield':
      assert(Number.isFinite(value) && value >= node.minimum && value <= node.maximum)
      return new VeraNumberValue(value)
    case 'timefield':
      assert(Number.isInteger(value) && value >= 0 && value < 1440)
      return new VeraNumberValue(value)
    default:
      assert(['button', 'listitem'].includes(node.kind) && value === undefined, 'Use a supported control step.')
      return new VeraNumberValue(node.eventValue)
  }
}

function advanceFrames(node, count) {
  const stepMs = 16
  const viewRefreshMs = 100 // VeraPreview refreshes its live tree on this interval.
  let current = node
  let sinceRefresh = 0
  let advanced = 0
  while (advanced < count && current?.action) {
    dispatch(current.action, new VeraNumberValue(stepMs))
    advanced++
    sinceRefresh += stepMs
    if (sinceRefresh >= viewRefreshMs) {
      const surfaces = controls('surface', node.label)
      assert(surfaces.length <= 1, 'The refreshed view has ambiguous surfaces.')
      current = surfaces[0]
      sinceRefresh = 0
    }
  }
  return advanced
}

const initial = captureView()
const observations = []
for (const step of steps) {
  if (step.type === 'effect-result') {
    const effect = pending.get(step.id)
    assert(effect?.resultHandler && typeof step.value === 'string', 'Select one pending effect and a controlled response.')
    pending.delete(step.id)
    dispatch(effect.resultHandler, new VeraStringValue(step.value))
    observations.push({step, effects: captureEffects(), view: captureView()})
    continue
  }
  const matches = controls(step.kind, step.label)
  assert.equal(matches.length, 1, 'The step must select one exposed control.')
  const [node] = matches
  let framesApplied
  if (step.type === 'pointer') {
    assert(node.kind === 'surface' && node.value && ['down', 'move', 'up'].includes(step.phase))
    assert(Number.isFinite(step.x) && Number.isFinite(step.y) &&
      step.x >= 0 && step.x <= node.minimum && step.y >= 0 && step.y <= node.maximum)
    dispatch(node.value, pointerValue(step.phase, 0, step.x, step.y))
  } else if (step.type === 'frame') {
    assert(node.kind === 'surface' && node.action && Number.isInteger(step.count) &&
      step.count >= 1 && step.count <= 600, 'Use 1 to 600 frames per step.')
    framesApplied = advanceFrames(node, step.count)
  } else {
    assert(step.type === 'control' && node.kind !== 'surface' && node.action, 'Select a control with an action.')
    if (node.enabled) dispatch(node.action, controlInput(node, step.value))
  }
  observations.push({step, enabled: node.enabled, framesApplied, effects: captureEffects(), view: captureView()})
}

console.log(JSON.stringify({
  slot_id: capture.slot_id, plan_sha256: capture.plan_sha256, request_sha256: capture.request_sha256,
  execution_sha256: capture.execution_sha256, capture_sha256: hash(captureBytes), artifact,
  scope: 'reference-host', initial, observations,
}, null, 2))
