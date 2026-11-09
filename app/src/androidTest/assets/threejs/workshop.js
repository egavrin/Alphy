export async function createScene({THREE, addons, canvas, state, viewport, readAsset}) {
    const renderer = new THREE.WebGLRenderer({canvas, antialias: true});
    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x16322f);
    const camera = new THREE.OrthographicCamera(-4, 4, 3, -3, .1, 50);
    camera.position.z = 12;
    const model = await new addons.GLTFLoader().parseAsync(await readAsset('robot.glb'), '');
    model.scene.position.set(2.45, -2.15, 0);
    const mixer = new THREE.AnimationMixer(model.scene);
    const wave = mixer.clipAction(model.animations[0]);
    const label = new OffscreenCanvas(128, 128);
    const brush = label.getContext('2d');
    brush.fillStyle = '#fcd691';
    brush.fillRect(0, 0, 128, 128);
    brush.fillStyle = '#182433';
    brush.font = 'bold 86px sans-serif';
    brush.textAlign = 'center';
    brush.textBaseline = 'middle';
    brush.fillText('М', 64, 70);
    const tile = new THREE.Mesh(new THREE.BoxGeometry(1.4, 1.4, .4),
        new THREE.MeshBasicMaterial({map: new THREE.CanvasTexture(label)}));
    tile.position.x = state.placed ? 1.6 : -1.6;
    const target = new THREE.Mesh(new THREE.PlaneGeometry(1.65, 1.65),
        new THREE.MeshBasicMaterial({color: 0x679089}));
    target.position.set(1.6, 0, -.35);
    scene.add(model.scene, target, tile);
    const ray = new THREE.Raycaster();
    let dragging = false;
    let audio;
    function draw() { renderer.render(scene, camera); }
    renderer.setSize(viewport.width, viewport.height, false);
    draw();
    return {
        event(event) {
            if (event.type === 'viewport') renderer.setSize(event.width, event.height, false);
            if (event.type === 'pointerdown') {
                ray.setFromCamera(new THREE.Vector2(event.x * 2 - 1, 1 - event.y * 2), camera);
                dragging = ray.intersectObject(tile).length > 0 && !state.placed;
            }
            if (dragging && event.type === 'pointermove') {
                tile.position.set(event.x * 8 - 4, (0.5 - event.y) * 6, 0);
            }
            if (dragging && event.type === 'pointerup') {
                dragging = false;
                state.placed = event.x > .60 && event.x < .81 && Math.abs(event.y - .5) < .15;
                tile.position.set(state.placed ? 1.6 : -1.6, 0, 0);
                if (state.placed) {
                    state.status = 'Готово';
                    audio = 'cue.wav';
                    wave.reset().play();
                }
            }
            if (event.type === 'pointercancel') {
                dragging = false;
                tile.position.set(state.placed ? 1.6 : -1.6, 0, 0);
            }
            draw();
        },
        frame({dtMs}) { mixer.update(dtMs / 1000); draw(); },
        snapshot() {
            const result = {state};
            if (audio) { result.audio = audio; audio = null; }
            return result;
        },
        dispose() { mixer.stopAllAction(); renderer.dispose(); }
    };
}
