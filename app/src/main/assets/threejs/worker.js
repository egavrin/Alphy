import * as THREE from './three.module.min.js';
import * as addons from './addons.min.js';

self.addEventListener('message', ({data: {surface, port, assets, textures = []}}) => {
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

    const textureTargets = new Map(textures.map(texture => [texture.target, texture]));
    const textureHashes = new Set(textures.map(texture => texture.id));
    const manualAssets = assets.filter(asset => !textureHashes.has(asset.id));
    const bindings = new Map();
    let checkedScene;
    let bindingOpen = true;
    class TextureBindingError extends Error {}
    const bindTexture = async (target, mesh) => {
        const approved = textureTargets.get(target);
        if (!bindingOpen || !approved || bindings.has(target) || !mesh?.isMesh ||
            !mesh.geometry?.getAttribute('uv') || Array.isArray(mesh.material) || !mesh.material ||
            !('map' in mesh.material) || [...bindings.values()].some(binding => binding.mesh === mesh)) {
            throw new TextureBindingError();
        }
        // Reserve before the read so simultaneous calls cannot bind the same target.
        const binding = {mesh};
        bindings.set(target, binding);
        try {
            const bytes = await readAsset(approved.id);
            binding.bitmap = await createImageBitmap(new Blob([bytes], {type: approved.mime}), {imageOrientation: 'flipY'});
            binding.texture = new THREE.Texture(binding.bitmap);
            binding.texture.colorSpace = THREE.SRGBColorSpace;
            binding.texture.wrapS = approved.wrap ? THREE.RepeatWrapping : THREE.ClampToEdgeWrapping;
            binding.texture.needsUpdate = true;
            binding.material = mesh.material.clone();
            binding.material.map = binding.texture;
            binding.material.needsUpdate = true;
            mesh.material = binding.material;
        } catch {
            throw new TextureBindingError();
        }
    };
    const checkScene = scene => {
        if (!scene?.isScene || bindings.size !== textureTargets.size || (checkedScene && scene !== checkedScene)) {
            throw new TextureBindingError();
        }
        for (const {mesh, material, texture, bitmap} of bindings.values()) {
            if (scene.getObjectById(mesh.id) !== mesh || !mesh.geometry?.getAttribute('uv') ||
                !texture || mesh.material !== material || material.map !== texture || texture.image !== bitmap) {
                throw new TextureBindingError();
            }
        }
    };
    const rendererOwners = new WeakSet();
    class TextureRenderer extends THREE.WebGLRenderer {
        constructor(options) {
            if (options?.canvas !== surface) throw new TextureBindingError();
            super(options);
            const gl = this.getContext();
            if (this.domElement !== surface || gl !== surface.getContext('webgl2')) {
                throw new TextureBindingError();
            }
            const draw = this.render.bind(this);
            const target = this.getRenderTarget.bind(this);
            const info = this.info;
            const stats = info.render;
            Object.defineProperty(this, 'render', {
                configurable: false,
                writable: false,
                value: (scene, camera) => {
                    if (target() !== null) return draw(scene, camera);
                    checkScene(scene);
                    if (!(camera instanceof THREE.Camera) || gl.isContextLost()) throw new TextureBindingError();
                    const frame = stats.frame;
                    const calls = info.autoReset ? 0 : stats.calls;
                    const result = draw(scene, camera);
                    if (stats.frame === frame || stats.calls <= calls || target() !== null || gl.isContextLost()) {
                        throw new TextureBindingError();
                    }
                    checkScene(scene);
                    checkedScene = scene;
                    return result;
                },
            });
            rendererOwners.add(this);
        }
    }
    const sceneThree = textureTargets.size === 0 ? THREE : Object.freeze({...THREE, WebGLRenderer: TextureRenderer});
    const render = (renderer, scene, camera) => {
        if (!renderer?.isWebGLRenderer || !scene?.isScene ||
            (textureTargets.size > 0 && !rendererOwners.has(renderer))) throw new TextureBindingError();
        return renderer.render(scene, camera);
    };
    const checkBindings = () => {
        if (textureTargets.size > 0) checkScene(checkedScene);
    };
    const releaseTextures = () => {
        for (const binding of bindings.values()) {
            binding.texture?.dispose();
            binding.material?.dispose();
            binding.bitmap?.close();
        }
        bindings.clear();
    };

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
                    THREE: sceneThree, addons, canvas: surface, input, assets: manualAssets,
                    readAsset: id => textureHashes.has(id) ? Promise.reject(new Error('Use bindTexture')) : readAsset(id),
                    bindTexture, render,
                    state: data.payload.state, viewport
                }));
                bindingOpen = false;
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
                try { await controller.dispose(); }
                finally { releaseTextures(); }
                code = 'snapshot_failed';
                post({type: 'snapshot', sequence: data.sequence, snapshot});
                return;
            } else {
                throw new Error('Unsupported scene event');
            }
            checkBindings();
            code = 'snapshot_failed';
            const snapshot = await controller.snapshot();
            post({type: 'snapshot', sequence: data.sequence, snapshot});
        } catch (failure) {
            closed = true;
            releaseTextures();
            post({type: 'failed', sequence: data.sequence, code: failure instanceof TextureBindingError ? 'texture_binding' : code});
        }
    };
}, {once: true});
