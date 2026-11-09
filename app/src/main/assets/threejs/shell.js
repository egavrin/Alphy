import session from './session.js';

const canvas = document.querySelector('canvas');
const nativePost = AlphySceneHost.postMessage.bind(AlphySceneHost);
const allowedAssets = new Set(session.assets.map(asset => asset.id));
const send = (type, sequence, payload) => nativePost(JSON.stringify({
    protocolVersion: 1, artifactId: session.artifactId, revision: session.revision,
    sessionId: session.sessionId, type, sequence, payload
}));

let worker;
let port;
let pending = null;
let pendingAssetReads = 0;
let assetRequests = 0;
let failed = false;

function fail(message) {
    if (failed) return;
    failed = true;
    worker?.terminate();
    port?.close();
    send('failed', pending ?? 0, {message: String(message).slice(0, 280)});
}

async function readAsset({id, request}) {
    if (!allowedAssets.has(id) || !Number.isSafeInteger(request) ||
        pendingAssetReads >= 4 || ++assetRequests > 128) {
        port.postMessage({type: 'asset', request, error: 'Asset request denied'});
        return;
    }
    pendingAssetReads++;
    try {
        const response = await fetch('/asset/' + encodeURIComponent(id), {credentials: 'omit'});
        if (!response.ok) throw new Error('Asset unavailable');
        const data = await response.arrayBuffer();
        port.postMessage({type: 'asset', request, data}, [data]);
    } catch (error) {
        port.postMessage({type: 'asset', request, error: String(error).slice(0, 120)});
    } finally {
        pendingAssetReads--;
    }
}

try {
    const surface = canvas.transferControlToOffscreen();
    const channel = new MessageChannel();
    port = channel.port1;
    worker = new Worker('/worker.js', {type: 'module'});
    worker.onerror = event => fail(event.message || 'Could not start the scene worker');
    port.onmessage = ({data}) => {
        if (data?.type === 'asset') {
            void readAsset(data);
            return;
        }
        if (data?.type === 'contextLost') {
            fail('WebGL context lost');
            return;
        }
        if (data?.sequence !== pending || pending === null) {
            fail('Invalid scene response sequence');
            return;
        }
        const sequence = pending;
        pending = null;
        if (data.type === 'failed') {
            send('failed', sequence, {message: String(data.message).slice(0, 280)});
            failed = true;
            worker.terminate();
            port.close();
            return;
        }
        if (data.type !== 'snapshot') {
            fail('Invalid scene response');
            return;
        }
        try {
            send('snapshot', sequence, data.snapshot);
        } catch (error) {
            fail(error);
        }
    };
    port.onmessageerror = () => fail('Invalid scene message');
    worker.postMessage({surface, port: channel.port2, assets: session.assets}, [surface, channel.port2]);
    window.alphySceneSend = raw => {
        if (failed) return;
        if (pending !== null) {
            fail('Overlapping scene requests');
            return;
        }
        const request = JSON.parse(raw);
        pending = request.sequence;
        port.postMessage(request);
    };
    send('ready', 0, {});
} catch (error) {
    fail(error);
}
