import * as THREE from './three.module.min.js';
import * as addons from './addons.min.js';

self.addEventListener('message', ({data: {surface, port, assets}}) => {
    const post = port.postMessage.bind(port);
    const awaiting = new Map();
    const authorizedAssets = new Set(assets.map(asset => asset.id));
    const input = new THREE.EventDispatcher();
    let nextAssetRequest = 0;
    let controller;
    let viewport = {width: 1, height: 1};
    let closed = false;

    input.style = {};
    input.getRootNode = () => input;
    input.getBoundingClientRect = () => ({left: 0, top: 0, ...viewport});
    input.setPointerCapture = () => {};
    input.releasePointerCapture = () => {};
    Object.defineProperties(input, {
        clientWidth: {get: () => viewport.width},
        clientHeight: {get: () => viewport.height}
    });

    const readAsset = id => new Promise((resolve, reject) => {
        if (!authorizedAssets.has(id) || awaiting.size >= 4) {
            reject(new Error('Asset request denied'));
            return;
        }
        const request = ++nextAssetRequest;
        awaiting.set(request, {resolve, reject});
        post({type: 'asset', id, request});
    });

    const checkedHandler = value => {
        if (['event', 'frame', 'snapshot', 'dispose'].some(key => typeof value?.[key] !== 'function')) {
            throw new Error('createScene must return event, frame, snapshot, and dispose handlers');
        }
        return value;
    };

    const setViewport = size => {
        viewport = size;
        surface.width = size.width;
        surface.height = size.height;
    };

    surface.addEventListener('webglcontextlost', () => post({type: 'contextLost'}));
    self.addEventListener('unhandledrejection', event => {
        event.preventDefault();
        if (closed) return;
        closed = true;
        post({type: 'fatal'});
    });
    port.onmessage = async ({data}) => {
        if (data.type === 'asset') {
            const request = awaiting.get(data.request);
            if (!request) return;
            awaiting.delete(data.request);
            if (data.error) request.reject(new Error(data.error));
            else request.resolve(data.data);
            return;
        }
        if (closed) return;
        let code = 'invalid_response';
        try {
            if (data.type === 'init') {
                setViewport(data.payload.viewport);
                code = 'webgl_unavailable';
                if (!surface.getContext('webgl2')) throw new Error('WebGL 2 is unavailable');
                code = 'module_load';
                const {createScene} = await import('./scene.js');
                if (typeof createScene !== 'function') throw new Error('Export createScene(context)');
                code = 'init_failed';
                controller = checkedHandler(await createScene({
                    THREE, addons, canvas: surface, input, assets, readAsset,
                    state: data.payload.state, viewport
                }));
            } else if (data.type === 'event') {
                code = 'event_failed';
                const event = data.payload;
                if (event.type === 'viewport') setViewport(event);
                if (event.type.startsWith('pointer')) {
                    input.dispatchEvent({...event, preventDefault() {}, stopPropagation() {}});
                }
                await controller.event(event);
            } else if (data.type === 'frame') {
                code = 'frame_failed';
                await controller.frame(data.payload);
            } else if (data.type === 'dispose') {
                closed = true;
                code = 'snapshot_failed';
                const snapshot = await controller.snapshot();
                code = 'dispose_failed';
                await controller.dispose();
                code = 'snapshot_failed';
                post({type: 'snapshot', sequence: data.sequence, snapshot});
                return;
            } else {
                throw new Error('Unsupported scene event');
            }
            code = 'snapshot_failed';
            const snapshot = await controller.snapshot();
            post({type: 'snapshot', sequence: data.sequence, snapshot});
        } catch {
            closed = true;
            post({type: 'failed', sequence: data.sequence, code});
        }
    };
}, {once: true});
