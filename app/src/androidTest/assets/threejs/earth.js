export async function createScene({THREE, addons, canvas, input, state, viewport}) {
    const enabled = true;
    const renderer = new THREE.WebGLRenderer({canvas, antialias: true});
    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x0b1220);
    const camera = new THREE.PerspectiveCamera(40, viewport.width / viewport.height, .1, 50);
    function setCamera() {
        if (state.camera === 'close') camera.position.set(2, 1.5, 3.5);
        else camera.position.set(0, 4, 8);
    }
    setCamera();
    const controls = new addons.OrbitControls(camera, input);
    controls.update();
    const earth = new THREE.Mesh(new THREE.SphereGeometry(.92, 24, 16),
        new THREE.MeshBasicMaterial({color: 0x3180f4}));
    const moon = new THREE.Mesh(new THREE.SphereGeometry(.29, 18, 12),
        new THREE.MeshBasicMaterial({color: 0xe0dce2}));
    scene.add(earth, moon);
    function size(width, height) {
        camera.aspect = width / height;
        camera.updateProjectionMatrix();
        renderer.setSize(width, height, false);
    }
    function draw() {
        moon.position.set(Math.cos(state.phase) * 2.3, 0, Math.sin(state.phase) * 2.3);
        renderer.render(scene, camera);
    }
    size(viewport.width, viewport.height);
    draw();
    return {
        event(event) {
            if (event.type === 'viewport') size(event.width, event.height);
            if (event.type === 'action' && event.actionId === 'camera') {
                state.camera = event.value;
                setCamera();
                controls.update();
            }
            if (event.type === 'pause') state.paused = true;
            if (event.type === 'resume' || event.type === 'start') state.paused = false;
            if (event.type === 'restart') {
                state.paused = false;
                state.phase = 0;
            }
            draw();
        },
        frame({dtMs}) {
            if (enabled && !state.paused) state.phase = (state.phase + dtMs * Math.PI / 4000) % (2 * Math.PI);
            draw();
        },
        snapshot() { return {state}; },
        dispose() { controls.dispose(); renderer.dispose(); }
    };
}
