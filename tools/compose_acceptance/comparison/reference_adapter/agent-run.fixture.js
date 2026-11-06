'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const args = process.argv.slice(2)
assert.deepEqual(args, ['-r', '-j', '1', '-p', args[4], '-o', args[6], 'slot'])
assert.equal(path.basename(args[4]), 'request.tsv')
assert.equal(path.basename(args[6]), 'raw')
assert(fs.existsSync(path.join(process.cwd(), 'tools/vera-harness/agent-run.js')))
for (const key of ['ARKESTR_API_KEY', 'TYPESAFE_API_KEY', 'EXA_API_KEY', 'AGENT_ANSWER',
  'NODE_OPTIONS', 'VERA_SOURCE_ROOT', 'REFERENCE_FIXTURE_MARKER']) {
  assert.equal(process.env[key], undefined)
}
assert.notEqual(process.env.HOME, '/synthetic-parent-home')
const row = fs.readFileSync(args[4], 'utf8')
const request = row.slice(5, -1)
assert.equal(row, `slot\t${request}\n`)

const dir = path.join(args[6], 'slot')
fs.mkdirSync(dir, {recursive: true})
const save = result => fs.writeFileSync(path.join(dir, 'result.json'), JSON.stringify(result))
const marker = 'synthetic-text-do-not-retain'
console.log(marker)
console.error(marker)
switch (request) {
case 'Synthetic caught failure':
  save({label: 'slot', prompt: request, error: marker})
  break
case 'Synthetic bad result':
  save({label: 'slot', prompt: 'Different request', route: 'agent', served: true})
  break
case 'Synthetic nonzero exit':
  fs.writeFileSync(path.join(dir, 'holes.jsonl'), marker)
  process.exitCode = 7
  break
case 'Synthetic not built':
  save({label: 'slot', prompt: request, widget: false, route: 'not-built', served: false, recorded: false})
  break
default:
  assert.equal(request, '  Сделай "счётчик" $(synthetic-shell-marker)  ')
  save({label: 'slot', prompt: request, widget: false, route: 'agent', served: true, recorded: true, note: marker})
  fs.writeFileSync(path.join(dir, 'program.vera'), marker)
  fs.writeFileSync(path.join(dir, 'holes.jsonl'), '')
}
