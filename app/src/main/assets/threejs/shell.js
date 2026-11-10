import session from './session.js';

const canvas = document.querySelector('canvas');
const nativePost = AlphySceneHost.postMessage.bind(AlphySceneHost);
const allowedAssets = new Map(session.assets.map(asset => [asset.id, asset.size]));
const workerFailures = new Set([
    'module_load', 'webgl_unavailable', 'init_failed', 'event_failed',
    'frame_failed', 'snapshot_failed', 'dispose_failed'
]);
const maxInFlightBytes = 64 * 1024 * 1024;
const maxSessionBytes = 256 * 1024 * 1024;
const send = (type, sequence, payload) => nativePost(JSON.stringify({
    protocolVersion: 1, artifactId: session.artifactId, revision: session.revision,
    sessionId: session.sessionId, type, sequence, payload
}));

let worker;
let port;
let pending = null;
let pendingAssetReads = 0;
let assetRequests = 0;
let inFlightBytes = 0;
let sessionBytes = 0;
let failed = false;
const queued = [];
const assetFetches = new AbortController();

function fail(code) {
    if (failed) return;
    failed = true;
    assetFetches.abort();
    queued.length = 0;
    worker?.terminate();
    port?.close();
    send('failed', pending ?? 0, {code});
}

function queueAsset({id, request}) {
    const size = allowedAssets.get(id);
    if (!Number.isSafeInteger(request) || !size || size > maxInFlightBytes ||
        pendingAssetReads + queued.length >= 4 || ++assetRequests > 128 ||
        sessionBytes + size > maxSessionBytes) {
        port.postMessage({type: 'asset', request, error: 'Asset request denied'});
        return;
    }
    sessionBytes += size;
    queued.push({id, request, size});
    pumpAssets();
}

function pumpAssets() {
    while (!failed && queued.length && inFlightBytes + queued[0].size <= maxInFlightBytes) {
        const read = queued.shift();
        inFlightBytes += read.size;
        pendingAssetReads++;
        void readAsset(read);
    }
}

async function readAsset({id, request, size}) {
    try {
        const response = await fetch('/asset/' + encodeURIComponent(id), {
            credentials: 'omit', signal: assetFetches.signal
        });
        if (!response.ok) throw new Error('Asset unavailable');
        const data = await response.arrayBuffer();
        if (data.byteLength !== size) throw new Error('Asset size changed');
        if (!failed) port.postMessage({type: 'asset', request, data}, [data]);
    } catch {
        if (!failed) port.postMessage({type: 'asset', request, error: 'Asset unavailable'});
    } finally {
        inFlightBytes -= size;
        pendingAssetReads--;
        pumpAssets();
    }
}

try {
    const surface = canvas.transferControlToOffscreen();
    const channel = new MessageChannel();
    port = channel.port1;
    worker = new Worker('/worker.js', {type: 'module'});
    worker.onerror = event => {
        event.preventDefault();
        fail('worker_failed');
    };
    port.onmessage = ({data}) => {
        if (failed) return;
        if (data?.type === 'asset') {
            queueAsset(data);
            return;
        }
        if (data?.type === 'fatal') {
            fail('worker_failed');
            return;
        }
        if (data?.type === 'contextLost') {
            fail('context_lost');
            return;
        }
        if (data?.sequence !== pending || pending === null) {
            fail('invalid_response');
            return;
        }
        if (data.type === 'failed') {
            fail(workerFailures.has(data.code) ? data.code : 'worker_failed');
            return;
        }
        if (data.type !== 'snapshot') {
            fail('invalid_response');
            return;
        }
        try {
            send('snapshot', pending, data.snapshot);
            pending = null;
        } catch {
            fail('invalid_response');
        }
    };
    port.onmessageerror = () => fail('invalid_response');
    worker.postMessage({surface, port: channel.port2, assets: session.assets}, [surface, channel.port2]);
    window.alphySceneSend = raw => {
        if (failed) return;
        if (pending !== null) {
            fail('invalid_response');
            return;
        }
        const request = JSON.parse(raw);
        pending = request.sequence;
        port.postMessage(request);
    };
    send('ready', 0, {});
} catch {
    fail('worker_failed');
}
