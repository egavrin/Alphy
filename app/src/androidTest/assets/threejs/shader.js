export async function createScene({THREE, canvas, state, viewport}) {
    const renderer = new THREE.WebGLRenderer({canvas});
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(42, viewport.width / viewport.height, .1, 50);
    camera.position.z = 4;
    const material = new THREE.ShaderMaterial({
        uniforms: {phase: {value: 0}},
        vertexShader: 'varying vec2 p; void main(){p=uv;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.);}',
        fragmentShader: 'varying vec2 p; uniform float phase; void main(){gl_FragColor=vec4(.5+.5*sin(p.x*8.+phase),.25,p.y,1.);}'
    });
    const knot = new THREE.Mesh(new THREE.TorusKnotGeometry(.65,.20,48,10), material);
    scene.add(knot);
    function draw() { material.uniforms.phase.value = state.phase; renderer.render(scene, camera); }
    renderer.setSize(viewport.width, viewport.height, false);
    draw();
    return {
        event(event) {
            if (event.type === 'viewport') {
                camera.aspect = event.width / event.height;
                camera.updateProjectionMatrix();
                renderer.setSize(event.width, event.height, false);
            }
            draw();
        },
        frame({dtMs}) { state.phase += dtMs / 50; knot.rotation.y += dtMs / 700; draw(); },
        snapshot() { return {state}; },
        dispose() { material.dispose(); renderer.dispose(); }
    };
}
