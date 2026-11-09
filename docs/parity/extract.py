#!/usr/bin/env python3
"""Extract the P0 source inventory from pinned Git objects. No service calls."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

PIN = '7ee06af1941729b00a110493ad9d1d9b5b127d17'
HERE = Path(__file__).resolve().parent
VERA = 'entry/src/main/ets/vera/'
RAW = 'entry/src/main/resources/rawfile/'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def check_android_sdk(sdk_root):
    contract = json.loads((HERE / 'android-dispositions.json').read_text())
    build = contract['android_build']
    jar = sdk_root / build['sdk_jar_relative_path']
    require(hashlib.sha256(jar.read_bytes()).hexdigest() == build['sdk_jar_sha256'], 'Android SDK jar differs')
    version_path = jar.parent / 'data/api-versions.xml'
    require(hashlib.sha256(version_path.read_bytes()).hexdigest() == build['api_versions_sha256'],
            'Android API-version evidence differs')
    versions = {c.attrib['name']: c for c in ET.parse(version_path).getroot().findall('class')}
    classes = {}
    for label, member in contract['android_api_evidence'].items():
        cls, name = member['class'], member['member']
        if cls not in classes:
            classes[cls] = subprocess.check_output(['javap', '-classpath', str(jar), cls], text=True)
        lines = [line.strip() for line in classes[cls].splitlines()]
        require(all(signature in lines for signature in member['public_signatures']), 'Android member differs: ' + label)
        version = versions.get(cls.replace('.', '/'))
        history = []
        if version is not None:
            for item in version:
                identifier = item.attrib.get('name', '')
                if identifier == name or identifier.startswith(name + '('):
                    history.append({'signature': identifier,
                                    'since': item.attrib.get('since', version.attrib.get('since', '1')),
                                    'deprecated': item.attrib.get('deprecated')})
        require(history == member['api_history'], 'Android API history differs: ' + label)
    return len(contract['android_api_evidence'])


def delimiters(text, start, opener='(', closer=')'):
    """Return the closing delimiter, ignoring source strings and comments."""
    depth, quote, i = 0, None, start
    while i < len(text):
        char = text[i]
        if quote:
            if char == '\\':
                i += 2
                continue
            if char == quote:
                quote = None
        elif text.startswith('//', i):
            i = text.find('\n', i)
            require(i >= 0, 'Unclosed source declaration')
            continue
        elif text.startswith('/*', i):
            i = text.find('*/', i + 2)
            require(i >= 0, 'Unclosed source comment')
            i += 2
            continue
        elif char in "'\"`":
            quote = char
        elif char == opener:
            depth += 1
        elif char == closer:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError('Unclosed source declaration')


def extract(repo):
    sources = {}
    cache = {}

    def read(path):
        if path not in cache:
            raw = subprocess.check_output(['git', '-C', str(repo), 'show', PIN + ':' + path])
            cache[path] = raw.decode('utf-8')
            sources[path] = {'commit': PIN, 'sha256': hashlib.sha256(raw).hexdigest(),
                             'git_blob': subprocess.check_output(
                                 ['git', '-C', str(repo), 'rev-parse', PIN + ':' + path], text=True).strip()}
        return cache[path]

    def provenance(path, symbol, offset=0, end=None):
        source = read(path)
        return {'path': path, 'symbol': symbol, 'commit': PIN,
                'line': source.count('\n', 0, offset) + 1,
                'end_line': source.count('\n', 0, end if end is not None else offset) + 1}

    def declarations(path, constructor):
        source = read(path)
        result = []
        for match in re.finditer(r'\bnew ' + constructor + r'\(', source):
            end = delimiters(source, match.end() - 1)
            result.append((match.start(), end, source[match.start():end]))
        return result

    baseline = json.loads((HERE / 'alphy-baseline.json').read_text())
    android_text = (HERE / 'android-dispositions.json').read_text()
    android = json.loads(android_text)
    entries = []

    def add(kind, key, source, contract, group, reference_status='registered', related=None):
        mapping = baseline['overrides'].get(kind + ':' + key, baseline['defaults'][kind]).copy()
        if kind in ('sdk', 'calendar', 'intent'):
            disposition = android['targets'][kind + ':' + key]
            require(disposition['reference_source'] == source, 'Stale Android mapping: ' + key)
            mapping.pop('android_applicability', None)
            mapping['android'] = disposition
            if disposition['disposition'] == 'platform_restriction':
                mapping['status'] = 'android_platform_limitation'
                mapping['scope'] = disposition['restriction']['reason']
            elif disposition['disposition'] == 'unresolved':
                mapping['scope'] = disposition['missing_evidence']['question']
            else:
                mapping['scope'] = disposition['boundary']
        if related:
            mapping['related_alphy'] = related
        entries.append({'kind': kind, 'key': key, 'group': group, 'source': source,
                        'reference_status': reference_status, 'contract': contract, 'alphy': mapping})

    ui_path = VERA + 'VeraUiCatalog.ets'
    ui_source = read(ui_path)
    style_start = ui_source.index('export const STYLE_SETS:')
    style_end = ui_source.index('\n])', style_start)
    ui_styles = {m[1]: re.findall(r"'([^']+)'", m[2]) for m in re.finditer(
        r"\['([^']+)', \[([^\]]+)\]\]", ui_source[style_start:style_end])}
    child_rules = [{'source': provenance(ui_path, 'CHILD_RULES', start, end), 'declaration': declaration}
                   for start, end, declaration in declarations(ui_path, 'ChildRule')]
    require(len(ui_styles) == 59 and len(child_rules) == 13, 'UI style or child-rule coverage changed')
    for start, end, declaration in declarations(ui_path, 'ComponentSpec'):
        name, node = re.match(r"new ComponentSpec\('([^']+)', '([^']+)'", declaration).groups()
        group = 'composites'
        if name in baseline['ui_groups']['primitives']:
            group = 'primitives'
        elif name in baseline['ui_groups']['inputs']:
            group = 'inputs'
        elif name in baseline['ui_groups']['graphics_and_motion']:
            group = 'graphics_and_motion'
        add('ui', name, provenance(ui_path, 'CATALOG', start, end),
            {'node_kind': node, 'declaration': declaration}, group)

    compiler_path = VERA + 'VeraCompiler.ets'
    compiler = read(compiler_path)
    helper_start = compiler.index('function buildStdUiModule(')
    helper_end = delimiters(compiler, compiler.index('{', helper_start), '{', '}')
    for match in re.finditer(r"new ExternalFunction\('([^']+)'", compiler[helper_start:helper_end]):
        start = helper_start + match.start()
        end = delimiters(compiler, compiler.index('(', start))
        add('ui_helper', match[1], provenance(compiler_path, 'buildStdUiModule', start, end),
            {'declaration': compiler[start:end]}, 'conditional' if match[1] == 'When' else 'formatting')

    gfx_path = VERA + 'VeraGfx.ets'
    for start, end, declaration in declarations(gfx_path, 'GfxFunction'):
        name = re.match(r"new GfxFunction\('([^']+)'", declaration)[1]
        add('graphics', name, provenance(gfx_path, 'GFX_FUNCTIONS', start, end),
            {'declaration': declaration}, 'helpers' if name in ('rgb', 'distance', 'inside') else 'shapes')

    sdk_index_path = RAW + 'sdk-index.json'
    sdk_index = json.loads(read(sdk_index_path))
    indexed = {'.'.join((row['kit'], row['module'], row['name'])): row for row in sdk_index}
    require(len(indexed) == len(sdk_index) == 11684, 'SDK index coverage changed')
    sdk_path = VERA + 'VeraSdkBackend.ets'
    source = read(sdk_path)
    adapter_start = source.index('const ADAPTERS:')
    adapter_end = source.index('\n])', adapter_start)
    rows = list(re.finditer(r"^  \['([^']+)', async", source[adapter_start:adapter_end], re.M))
    occurrences = defaultdict(list)
    bodies = {}
    for ordinal, row in enumerate(rows, 1):
        start = adapter_start + row.start()
        brace = source.index('{', adapter_start + row.end())
        end = delimiters(source, brace, '{', '}')
        body = source[brace:end]
        key = row[1]
        record = provenance(sdk_path, 'ADAPTERS', start, end)
        record.update({'row': ordinal, 'binding_sha256': digest(body)})
        occurrences[key].append(record)
        bodies[key] = body
    duplicates = []
    for key, records in occurrences.items():
        body = bodies[key]
        require(key in indexed, 'Registered SDK target absent from index: ' + key)
        contract = {'sdk_declaration': indexed[key],
                    'parameter_reads': sorted(set(re.findall(r"params\.get\('[^']+'\)|param\w+\(params, [^\n]+", body))),
                    'capability_guards': sorted(set(re.findall(r"canIUse\('([^']+)'\)", body))),
                    'index_source': provenance(sdk_index_path, key)}
        add('sdk', key, records[-1], contract, key.split('.')[0],
            'registered-system-api' if indexed[key]['systemapi'] else 'registered')
        if len(records) > 1:
            duplicates.append({'kind': 'sdk', 'key': key, 'policy': 'last-row-wins',
                               'occurrences': records, 'effective_row': records[-1]['row']})

    cal_path = VERA + 'VeraCalendarSdk.ets'
    source = read(cal_path)
    contract_start = source.index('export function calendarSdkContract(')
    contract_end = delimiters(source, source.index('{', contract_start), '{', '}')
    calendar_contract = {
        'source': provenance(cal_path, 'calendarSdkContract', contract_start, contract_end),
        'declaration': source[contract_start:contract_end],
        'constants': re.findall(r'^const (?:EVENT_\w+|CALENDAR_SELECTION|MANAGER_SELECTION):[^\n]+', source, re.M)}
    start = source.index('export const calendarSdkTargets:')
    end = source.index('\n]', start)
    calendar_keys = []
    for match in re.finditer(r"PREFIX \+ '([^']+)'", source[start:end]):
        key = 'CalendarKit.' + match[1]
        calendar_keys.append(key)
        add('calendar', key, provenance(cal_path, 'calendarSdkTargets', start + match.start()),
            {'sdk_declaration': indexed[key], 'binding_symbol': 'runCalendarSdkAdapter',
             'contract_symbol': 'calendarSdkContract'}, 'calendar')
    live_path = VERA + 'VeraLive.ets'
    source = read(live_path)
    match = re.search(r"export const LIVE_TARGET: string = '([^']+)'", source)
    add('live', match[1], provenance(live_path, 'liveFetch', source.index('export async function liveFetch')),
        {'query': 'nonempty string', 'labels': 'up to three nonempty labels separated by |',
         'result': 'source-checked figures or an explicit error', 'reuse_ms': 30000,
         'requires': ['configured search key', 'configured model key']}, 'live_data')

    intent_path = VERA + 'VeraIntentCatalog.ets'
    curated = defaultdict(list)
    for start, end, declaration in declarations(intent_path, 'IntentSpec'):
        header = re.match(r"new IntentSpec\('([^']+)', '([^']+)',\s*'([^']+)', '([^']+)', '([^']+)', '([^']*)'", declaration)
        require(header is not None, 'Intent header extraction failed')
        name, params_class, bundle, module, ability, intent_name = header.groups()
        key = '/'.join([bundle, module, ability] + ([intent_name] if intent_name else []))
        tier = re.search(r"'(catalog|probed|schema|ignored)',\s*'(foreground|background)',\s*'(intent|launch)'", declaration)
        require(tier is not None, 'Intent tier extraction failed')
        curated[key].append({'name': name, 'params_class': params_class, 'tier': tier[1], 'mode': tier[2],
                             'via': tier[3], 'declaration': declaration,
                             'source': provenance(intent_path, 'INTENT_CATALOG', start, end)})
    for key, variants in curated.items():
        add('intent', key, variants[0]['source'], {'variants': variants}, 'curated_intents',
            'excluded-from-discovery' if all(v['tier'] == 'ignored' for v in variants) else 'curated-address')
        if len(variants) > 1:
            duplicates.append({'kind': 'intent', 'key': key, 'policy': 'retain-discovery-variants',
                               'variants': [{'name': v['name'], 'source': v['source']} for v in variants]})

    celia_path = RAW + 'celia-intent-index.json'
    celia = json.loads(read(celia_path))
    for index, row in enumerate(celia):
        key = row['bundle'] + '/' + row['intentName']
        src = provenance(celia_path, '$[' + str(index) + ']')
        src['json_index'] = index
        add('intent_candidate', key, src, row, 'offline_intent_candidates', 'requires-live-registry')
    summary_path = RAW + 'intent-summaries.json'
    summaries = json.loads(read(summary_path))
    for key, description in summaries.items():
        add('intent_summary', key, provenance(summary_path, '$[' + json.dumps(key) + ']'),
            {'description': description}, 'offline_intent_metadata', 'metadata-only')
    registry_path = VERA + 'VeraIntentRegistry.ets'
    source = read(registry_path)
    add('intent_registry', 'installed-device-intents',
        provenance(registry_path, 'loadRegistry', source.index('export function loadRegistry')),
        {'key': 'bundle/module/ability/IntentName', 'entries': 'device-dependent; no fixed count',
         'requires': ['system signature', 'ohos.permission.GET_BUNDLE_INFO_PRIVILEGED'],
         'failure': 'empty registry; curated catalog remains'}, 'dynamic_intents', 'dynamic')

    for workflow in baseline['workflows']:
        path = VERA + workflow['path']
        source = read(path)
        symbol = workflow['symbol']
        match = re.search(r'\b(?:function|class) ' + re.escape(symbol) + r'\b', source)
        require(match is not None, 'Workflow symbol missing: ' + symbol)
        add('workflow', workflow['key'], provenance(path, symbol, match.start()),
            {'behavior': workflow['behavior'], 'verification': workflow['verification']}, workflow['group'])

    # These sources define dispatch, exclusion, styles, and container constraints.
    for path in ['VeraIntentIndex.ets', 'VeraIntents.ets', 'VeraCeliaIntentIndex.ets',
                 'VeraSdkIndex.ets', 'VeraSdkContracts.ets', 'VeraUi.ets']:
        read(VERA + path)
    counts = dict(sorted(Counter(entry['kind'] for entry in entries).items()))
    expected = {'ui': 62, 'ui_helper': 7, 'graphics': 14, 'sdk': 528, 'calendar': 20, 'live': 1,
                'intent': 6, 'intent_candidate': 682, 'intent_summary': 640,
                'intent_registry': 1, 'workflow': len(baseline['workflows'])}
    require(counts == expected, 'Inventory counts differ: ' + str(counts))
    sdk_duplicates = [d for d in duplicates if d['kind'] == 'sdk']
    require(len(rows) == 530 and len(sdk_duplicates) == 2, 'SDK duplicate coverage changed')
    require({d['key'] for d in sdk_duplicates} == {
        'CoreFileKit.storageStatistics.getFreeSizeSync', 'CoreFileKit.storageStatistics.getTotalSizeSync'},
        'Unexpected SDK duplicates')
    intent_duplicates = [d for d in duplicates if d['kind'] == 'intent']
    require(sum(len(v) for v in curated.values()) == 7 and len(intent_duplicates) == 1,
            'Curated intent variant coverage changed')
    keys = {(e['kind'], e['key']) for e in entries}
    require(len(keys) == len(entries), 'Duplicate inventory key')
    require(set(baseline['overrides']).issubset({kind + ':' + key for kind, key in keys}), 'Unused override')
    require(set(android['targets']) == {kind + ':' + key for kind, key in keys
                                      if kind in ('sdk', 'calendar', 'intent')}, 'Android target coverage differs')
    for label, disposition in android['targets'].items():
        require(disposition['family'] in android['families'], 'Missing Android family: ' + label)
        require(disposition['implementation_status'] != 'implemented', 'Unverified implementation claim: ' + label)
        require(disposition['next_verification'] and disposition['package'], 'Missing target action: ' + label)
        state = disposition['disposition']
        require(state in android['disposition_meanings'], 'Unknown Android disposition: ' + label)
        if state == 'unresolved':
            require(disposition['missing_evidence']['exact_reference_signature'] == disposition['reference_signature'],
                    'Missing exact unresolved signature: ' + label)
            require(label.split(':', 1)[1] in disposition['missing_evidence']['question'],
                    'Generic unresolved question: ' + label)
        else:
            require(disposition['android_symbols'] and all(symbol in android['android_api_evidence']
                    for symbol in disposition['android_symbols']), 'Missing public Android API evidence: ' + label)
        if state == 'platform_restriction':
            require(disposition['restriction']['reason'] and disposition['restriction']['source'],
                    'Missing restriction evidence: ' + label)
    for entry in entries:
        mapping = entry['alphy']
        require(mapping['status'] in ('supported', 'unimplemented', 'android_platform_limitation'), 'Invalid status')
        require(mapping['evidence'] and all(e in baseline['evidence'] for e in mapping['evidence']), 'Missing Alphy evidence')
        require(mapping['status'] != 'supported' or 'workflow:' + entry['key'] in baseline['overrides'],
                'Unsupported capability marked implemented')
        require(entry['source']['path'] in sources and entry['source']['commit'] == PIN, 'Missing provenance')
    require(not set(calendar_keys) & set(occurrences), 'Dispatch overlap requires explicit precedence review')
    registered = set(calendar_keys) | set(occurrences)
    return {'schema_version': 1, 'reference_commit': PIN, 'alphy_baseline': baseline['snapshot'],
            'counts': counts, 'sdk_registry_rows': len(rows),
            'curated_intent_rows': sum(len(v) for v in curated.values()), 'sdk_index_entries': len(indexed),
            'sdk_index_without_adapter': len(set(indexed) - registered),
            'android_disposition_counts': dict(sorted(Counter(d['disposition'] for d in android['targets'].values()).items())),
            'android_contracts': {key: value for key, value in android.items() if key != 'targets'},
            'mapping_inputs': {'alphy-baseline.json': digest((HERE / 'alphy-baseline.json').read_text()),
                               'android-dispositions.json': digest(android_text)},
            'ui_styles': ui_styles, 'ui_child_rules': child_rules, 'calendar_contract': calendar_contract,
            'scope': baseline['scope'], 'verification_criteria': baseline['verification_criteria'],
            'sources': dict(sorted(sources.items())), 'duplicates': sorted(duplicates, key=lambda d: d['key']),
            'entries': sorted(entries, key=lambda e: (e['kind'], e['key']))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('reference_root', type=Path)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--check', action='store_true', help='Verify the pinned extraction digest without writes')
    mode.add_argument('--output', type=Path, help='Write an optional inventory to this path')
    parser.add_argument('--android-sdk', type=Path, help='Also verify saved public signatures against the recorded Android SDK')
    args = parser.parse_args()
    output = extract(args.reference_root)
    rendered = json.dumps(output, ensure_ascii=False, indent=2) + '\n'
    if args.output is None or args.check:
        pinned = json.loads((HERE / 'verification.json').read_text())['artifacts']['inventory.json']
        require(digest(rendered) == pinned, 'Extracted inventory differs from the pinned digest')
    if args.output is not None:
        args.output.write_text(rendered)
    verified_members = check_android_sdk(args.android_sdk) if args.android_sdk else None
    print(json.dumps({'result': 'PASS', 'mode': 'write' if args.output is not None else 'check',
                      'reference_commit': PIN, 'counts': output['counts'],
                      'sdk_registry_rows': output['sdk_registry_rows'],
                      'sdk_index_without_adapter': output['sdk_index_without_adapter'],
                      'android_disposition_counts': output['android_disposition_counts'],
                      'verified_android_members': verified_members,
                      'inventory_sha256': digest(rendered)}, sort_keys=True))


if __name__ == '__main__':
    main()
