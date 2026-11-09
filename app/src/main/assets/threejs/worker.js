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
        try {
            if (data.type === 'init') {
                setViewport(data.payload.viewport);
                if (!surface.getContext('webgl2')) throw new Error('WebGL 2 is unavailable');
                const {createScene} = await import('./scene.js');
                if (typeof createScene !== 'function') throw new Error('Export createScene(context)');
                controller = checkedHandler(await createScene({
                    THREE, addons, canvas: surface, input, assets, readAsset,
                    state: data.payload.state, viewport
                }));
            } else if (data.type === 'event') {
                const event = data.payload;
                if (event.type === 'viewport') setViewport(event);
                if (event.type.startsWith('pointer')) {
                    input.dispatchEvent({...event, preventDefault() {}, stopPropagation() {}});
                }
                await controller.event(event);
            } else if (data.type === 'frame') {
                await controller.frame(data.payload);
            } else if (data.type === 'dispose') {
                closed = true;
                const snapshot = await controller.snapshot();
                await controller.dispose();
                post({type: 'snapshot', sequence: data.sequence, snapshot});
                return;
            } else {
                throw new Error('Unsupported scene event');
            }
            const snapshot = await controller.snapshot();
            post({type: 'snapshot', sequence: data.sequence, snapshot});
        } catch (error) {
            closed = true;
            post({type: 'failed', sequence: data.sequence, message: String(error).slice(0, 280)});
        }
    };
}, {once: true});
