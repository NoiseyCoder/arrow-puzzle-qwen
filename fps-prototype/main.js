import * as THREE from 'three';
import { PointerLockControls } from 'three/addons/controls/PointerLockControls.js';

// Game State
const gameState = {
    isPlaying: false,
    health: 100,
    maxHealth: 100,
    ammo: 30,
    maxAmmo: 30,
    reserveAmmo: 90,
    isReloading: false,
    isSprinting: false,
    canShoot: true,
    lastShotTime: 0,
    fireRate: 100, // ms between shots
};

// Scene setup
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x87ceeb);
scene.fog = new THREE.Fog(0x87ceeb, 50, 200);

// Camera
const camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 1000);
camera.position.set(0, 1.7, 5);

// Renderer
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.0;
document.getElementById('game-container').appendChild(renderer.domElement);

// Controls
const controls = new PointerLockControls(camera, document.body);
const startScreen = document.getElementById('start-screen');
const startBtn = document.getElementById('start-btn');

startBtn.addEventListener('click', () => {
    controls.lock();
});

controls.addEventListener('lock', () => {
    gameState.isPlaying = true;
    startScreen.style.display = 'none';
});

controls.addEventListener('unlock', () => {
    gameState.isPlaying = false;
    startScreen.style.display = 'flex';
});

// Lighting
const ambientLight = new THREE.AmbientLight(0x404040, 0.6);
scene.add(ambientLight);

const directionalLight = new THREE.DirectionalLight(0xffffff, 1);
directionalLight.position.set(50, 100, 50);
directionalLight.castShadow = true;
directionalLight.shadow.mapSize.width = 2048;
directionalLight.shadow.mapSize.height = 2048;
directionalLight.shadow.camera.near = 0.5;
directionalLight.shadow.camera.far = 500;
directionalLight.shadow.camera.left = -100;
directionalLight.shadow.camera.right = 100;
directionalLight.shadow.camera.top = 100;
directionalLight.shadow.camera.bottom = -100;
scene.add(directionalLight);

// Add some point lights for atmosphere
const pointLight1 = new THREE.PointLight(0xff6600, 0.5, 50);
pointLight1.position.set(-20, 5, -20);
scene.add(pointLight1);

const pointLight2 = new THREE.PointLight(0x0066ff, 0.5, 50);
pointLight2.position.set(20, 5, -20);
scene.add(pointLight2);

// Ground
const groundGeometry = new THREE.PlaneGeometry(200, 200);
const groundMaterial = new THREE.MeshStandardMaterial({ 
    color: 0x3a5f3a,
    roughness: 0.8,
    metalness: 0.1
});
const ground = new THREE.Mesh(groundGeometry, groundMaterial);
ground.rotation.x = -Math.PI / 2;
ground.receiveShadow = true;
scene.add(ground);

// Create environment
const objects = [];
const enemies = [];
const bullets = [];

// Create crates and obstacles
function createEnvironment() {
    const boxGeometry = new THREE.BoxGeometry(2, 2, 2);
    const boxMaterial = new THREE.MeshStandardMaterial({ 
        color: 0x8b4513,
        roughness: 0.7,
        metalness: 0.2
    });

    // Create various obstacles
    const positions = [
        { x: 10, z: -10 }, { x: -10, z: -10 }, { x: 10, z: 10 }, { x: -10, z: 10 },
        { x: 20, z: 0 }, { x: -20, z: 0 }, { x: 0, z: -20 }, { x: 0, z: 20 },
        { x: 15, z: -15 }, { x: -15, z: -15 }, { x: 15, z: 15 }, { x: -15, z: 15 },
        { x: 25, z: -10 }, { x: -25, z: -10 }, { x: 25, z: 10 }, { x: -25, z: 10 },
    ];

    positions.forEach((pos, index) => {
        const box = new THREE.Mesh(boxGeometry, boxMaterial);
        box.position.set(pos.x, 1, pos.z);
        box.castShadow = true;
        box.receiveShadow = true;
        scene.add(box);
        objects.push(box);

        // Stack some boxes
        if (index % 3 === 0) {
            const box2 = new THREE.Mesh(boxGeometry, boxMaterial);
            box2.position.set(pos.x, 3, pos.z);
            box2.castShadow = true;
            box2.receiveShadow = true;
            scene.add(box2);
            objects.push(box2);
        }
    });

    // Create walls
    const wallGeometry = new THREE.BoxGeometry(30, 4, 1);
    const wallMaterial = new THREE.MeshStandardMaterial({ 
        color: 0x666666,
        roughness: 0.6,
        metalness: 0.3
    });

    const wallPositions = [
        { x: 0, z: -30, rot: 0 },
        { x: 0, z: 30, rot: 0 },
        { x: -30, z: 0, rot: Math.PI / 2 },
        { x: 30, z: 0, rot: Math.PI / 2 },
    ];

    wallPositions.forEach(pos => {
        const wall = new THREE.Mesh(wallGeometry, wallMaterial);
        wall.position.set(pos.x, 2, pos.z);
        wall.rotation.y = pos.rot;
        wall.castShadow = true;
        wall.receiveShadow = true;
        scene.add(wall);
        objects.push(wall);
    });

    // Create ramps
    const rampGeometry = new THREE.BoxGeometry(4, 0.2, 8);
    const rampMaterial = new THREE.MeshStandardMaterial({ 
        color: 0x888888,
        roughness: 0.5,
        metalness: 0.4
    });

    const ramp = new THREE.Mesh(rampGeometry, rampMaterial);
    ramp.position.set(0, 1, -25);
    ramp.rotation.x = -0.3;
    ramp.castShadow = true;
    ramp.receiveShadow = true;
    scene.add(ramp);
    objects.push(ramp);
}

createEnvironment();

// Create enemy function
function createEnemy(x, z) {
    const enemyGeometry = new THREE.CapsuleGeometry(0.5, 1.8, 4, 8);
    const enemyMaterial = new THREE.MeshStandardMaterial({ 
        color: 0xff0000,
        roughness: 0.5,
        metalness: 0.3
    });
    const enemy = new THREE.Mesh(enemyGeometry, enemyMaterial);
    enemy.position.set(x, 1.4, z);
    enemy.castShadow = true;
    enemy.receiveShadow = true;
    enemy.userData = { 
        health: 100, 
        maxHealth: 100, 
        speed: 2 + Math.random(),
        lastAttack: 0 
    };
    scene.add(enemy);
    enemies.push(enemy);
    return enemy;
}

// Spawn initial enemies
for (let i = 0; i < 5; i++) {
    const angle = (i / 5) * Math.PI * 2;
    const distance = 20 + Math.random() * 10;
    createEnemy(Math.cos(angle) * distance, Math.sin(angle) * distance);
}

// Weapon model
function createWeapon() {
    const weaponGroup = new THREE.Group();
    
    // Gun body
    const bodyGeometry = new THREE.BoxGeometry(0.15, 0.2, 0.8);
    const bodyMaterial = new THREE.MeshStandardMaterial({ 
        color: 0x333333,
        roughness: 0.3,
        metalness: 0.8
    });
    const body = new THREE.Mesh(bodyGeometry, bodyMaterial);
    weaponGroup.add(body);
    
    // Barrel
    const barrelGeometry = new THREE.CylinderGeometry(0.03, 0.03, 0.4, 8);
    const barrelMaterial = new THREE.MeshStandardMaterial({ 
        color: 0x222222,
        roughness: 0.2,
        metalness: 0.9
    });
    const barrel = new THREE.Mesh(barrelGeometry, barrelMaterial);
    barrel.rotation.x = Math.PI / 2;
    barrel.position.set(0, 0.05, -0.5);
    weaponGroup.add(barrel);
    
    // Magazine
    const magGeometry = new THREE.BoxGeometry(0.08, 0.3, 0.15);
    const magMaterial = new THREE.MeshStandardMaterial({ 
        color: 0x444444,
        roughness: 0.4,
        metalness: 0.6
    });
    const magazine = new THREE.Mesh(magGeometry, magMaterial);
    magazine.position.set(0, -0.15, -0.1);
    weaponGroup.add(magazine);
    
    // Sight
    const sightGeometry = new THREE.BoxGeometry(0.02, 0.05, 0.05);
    const sightMaterial = new THREE.MeshStandardMaterial({ 
        color: 0x111111,
        roughness: 0.3,
        metalness: 0.7
    });
    const sight = new THREE.Mesh(sightGeometry, sightMaterial);
    sight.position.set(0, 0.12, -0.3);
    weaponGroup.add(sight);
    
    weaponGroup.position.set(0.3, -0.3, -0.5);
    camera.add(weaponGroup);
    
    return weaponGroup;
}

const weapon = createWeapon();
scene.add(camera);

// Movement variables
const moveState = {
    forward: false,
    backward: false,
    left: false,
    right: false,
    sprint: false,
    jump: false
};

const velocity = new THREE.Vector3();
const direction = new THREE.Vector3();
let canJump = true;

// Input handling
document.addEventListener('keydown', (event) => {
    switch (event.code) {
        case 'KeyW': moveState.forward = true; break;
        case 'KeyS': moveState.backward = true; break;
        case 'KeyA': moveState.left = true; break;
        case 'KeyD': moveState.right = true; break;
        case 'ShiftLeft': moveState.sprint = true; break;
        case 'Space': 
            if (canJump && gameState.isPlaying) {
                velocity.y = 8;
                canJump = false;
            }
            break;
        case 'KeyR': reload(); break;
    }
});

document.addEventListener('keyup', (event) => {
    switch (event.code) {
        case 'KeyW': moveState.forward = false; break;
        case 'KeyS': moveState.backward = false; break;
        case 'KeyA': moveState.left = false; break;
        case 'KeyD': moveState.right = false; break;
        case 'ShiftLeft': moveState.sprint = false; break;
    }
});

document.addEventListener('mousedown', (event) => {
    if (event.button === 0 && gameState.isPlaying && !gameState.isReloading) {
        shoot();
    }
});

// Shooting mechanics
const raycaster = new THREE.Raycaster();
const hitMarker = document.getElementById('hit-marker');

function shoot() {
    const now = Date.now();
    if (now - gameState.lastShotTime < gameState.fireRate || gameState.ammo <= 0) {
        if (gameState.ammo <= 0 && !gameState.isReloading) {
            reload();
        }
        return;
    }

    gameState.lastShotTime = now;
    gameState.ammo--;
    updateHUD();

    // Weapon recoil animation
    weapon.position.z += 0.1;
    weapon.rotation.x += 0.05;

    // Raycast for hit detection
    raycaster.setFromCamera(new THREE.Vector2(0, 0), camera);
    
    const allObjects = [...objects, ...enemies];
    const intersects = raycaster.intersectObjects(allObjects);

    if (intersects.length > 0) {
        const hit = intersects[0];
        
        // Check if hit enemy
        if (enemies.includes(hit.object)) {
            hitMarker.classList.add('active');
            setTimeout(() => hitMarker.classList.remove('active'), 300);
            
            hit.object.userData.health -= 25;
            
            // Flash effect on hit
            const flash = new THREE.PointLight(0xffff00, 2, 5);
            flash.position.copy(hit.point);
            scene.add(flash);
            setTimeout(() => scene.remove(flash), 50);

            if (hit.object.userData.health <= 0) {
                killEnemy(hit.object);
            }
        } else {
            // Create bullet hole
            createBulletHole(hit.point, hit.face.normal);
        }
    }

    // Reset weapon position smoothly
    setTimeout(() => {
        weapon.position.z = -0.5;
        weapon.rotation.x = 0;
    }, 50);
}

function createBulletHole(position, normal) {
    const holeGeometry = new THREE.CircleGeometry(0.05, 8);
    const holeMaterial = new THREE.MeshBasicMaterial({ color: 0x111111 });
    const hole = new THREE.Mesh(holeGeometry, holeMaterial);
    hole.position.copy(position).add(normal.multiplyScalar(0.01));
    hole.lookAt(position.clone().add(normal));
    scene.add(hole);
    
    setTimeout(() => scene.remove(hole), 5000);
}

function reload() {
    if (gameState.isReloading || gameState.ammo === gameState.maxAmmo || gameState.reserveAmmo <= 0) {
        return;
    }

    gameState.isReloading = true;
    
    // Reload animation
    weapon.rotation.x = -0.5;
    weapon.position.y = -0.5;

    setTimeout(() => {
        const needed = gameState.maxAmmo - gameState.ammo;
        const toReload = Math.min(needed, gameState.reserveAmmo);
        gameState.ammo += toReload;
        gameState.reserveAmmo -= toReload;
        gameState.isReloading = false;
        
        // Reset animation
        weapon.rotation.x = 0;
        weapon.position.y = 0;
        
        updateHUD();
    }, 1500);
}

function killEnemy(enemy) {
    scene.remove(enemy);
    const index = enemies.indexOf(enemy);
    if (index > -1) {
        enemies.splice(index, 1);
    }
    
    // Spawn new enemy after delay
    setTimeout(() => {
        const angle = Math.random() * Math.PI * 2;
        const distance = 20 + Math.random() * 10;
        createEnemy(Math.cos(angle) * distance, Math.sin(angle) * distance);
    }, 3000);
}

// Enemy AI
function updateEnemies(delta) {
    const now = Date.now();
    
    enemies.forEach(enemy => {
        const directionToPlayer = new THREE.Vector3()
            .subVectors(camera.position, enemy.position)
            .normalize();
        
        // Simple chase AI
        const distance = enemy.position.distanceTo(camera.position);
        
        if (distance > 2) {
            enemy.position.add(directionToPlayer.multiplyScalar(enemy.userData.speed * delta));
            enemy.lookAt(camera.position.x, enemy.position.y, camera.position.z);
        } else {
            // Attack player
            if (now - enemy.userData.lastAttack > 1000) {
                enemy.userData.lastAttack = now;
                gameState.health -= 10;
                updateHUD();
                
                // Screen flash red
                document.getElementById('game-container').style.boxShadow = 'inset 0 0 50px rgba(255, 0, 0, 0.5)';
                setTimeout(() => {
                    document.getElementById('game-container').style.boxShadow = 'none';
                }, 200);
                
                if (gameState.health <= 0) {
                    gameOver();
                }
            }
        }
    });
}

function gameOver() {
    gameState.isPlaying = false;
    controls.unlock();
    startScreen.style.display = 'flex';
    startScreen.querySelector('h1').textContent = 'GAME OVER';
    startScreen.querySelector('p').textContent = `You survived ${Math.floor(Date.now() / 1000)} seconds`;
    startBtn.textContent = 'Try Again';
    
    // Reset game
    setTimeout(() => {
        gameState.health = 100;
        gameState.ammo = 30;
        gameState.reserveAmmo = 90;
        updateHUD();
    }, 1000);
}

// Update HUD
function updateHUD() {
    document.getElementById('health-fill').style.width = `${gameState.health}%`;
    document.getElementById('ammo-count').textContent = `${gameState.ammo} / ${gameState.reserveAmmo}`;
}

// Minimap
const minimapCanvas = document.getElementById('minimap-canvas');
const minimapCtx = minimapCanvas.getContext('2d');
minimapCanvas.width = 150;
minimapCanvas.height = 150;

function updateMinimap() {
    minimapCtx.clearRect(0, 0, minimapCanvas.width, minimapCanvas.height);
    
    const scale = 3;
    const centerX = minimapCanvas.width / 2;
    const centerY = minimapCanvas.height / 2;
    
    // Draw player
    minimapCtx.fillStyle = '#00ff00';
    minimapCtx.beginPath();
    minimapCtx.arc(centerX, centerY, 5, 0, Math.PI * 2);
    minimapCtx.fill();
    
    // Draw enemies
    enemies.forEach(enemy => {
        const dx = (enemy.position.x - camera.position.x) * scale;
        const dz = (enemy.position.z - camera.position.z) * scale;
        
        if (Math.abs(dx) < 70 && Math.abs(dz) < 70) {
            minimapCtx.fillStyle = '#ff0000';
            minimapCtx.beginPath();
            minimapCtx.arc(centerX + dx, centerY + dz, 4, 0, Math.PI * 2);
            minimapCtx.fill();
        }
    });
    
    // Draw player direction
    const dirX = Math.sin(camera.rotation.y) * 20;
    const dirZ = Math.cos(camera.rotation.y) * 20;
    minimapCtx.strokeStyle = '#00ff00';
    minimapCtx.lineWidth = 2;
    minimapCtx.beginPath();
    minimapCtx.moveTo(centerX, centerY);
    minimapCtx.lineTo(centerX + dirX, centerY - dirZ);
    minimapCtx.stroke();
}

// Animation loop
const clock = new THREE.Clock();

function animate() {
    requestAnimationFrame(animate);
    
    const delta = Math.min(clock.getDelta(), 0.1);
    const now = Date.now();

    if (gameState.isPlaying) {
        // Movement
        const speed = moveState.sprint ? 12 : 6;
        
        direction.z = Number(moveState.forward) - Number(moveState.backward);
        direction.x = Number(moveState.right) - Number(moveState.left);
        direction.normalize();

        if (moveState.forward || moveState.backward) {
            velocity.z -= direction.z * speed * delta;
        }
        if (moveState.left || moveState.right) {
            velocity.x -= direction.x * speed * delta;
        }

        // Apply movement relative to camera direction
        const forward = new THREE.Vector3(0, 0, -1).applyQuaternion(camera.quaternion);
        forward.y = 0;
        forward.normalize();
        
        const right = new THREE.Vector3(1, 0, 0).applyQuaternion(camera.quaternion);
        right.y = 0;
        right.normalize();

        const moveVector = new THREE.Vector3();
        if (moveState.forward) moveVector.add(forward.clone().multiplyScalar(speed * delta));
        if (moveState.backward) moveVector.add(forward.clone().multiplyScalar(-speed * delta));
        if (moveState.left) moveVector.add(right.clone().multiplyScalar(-speed * delta));
        if (moveState.right) moveVector.add(right.clone().multiplyScalar(speed * delta));

        camera.position.add(moveVector);
        
        // Gravity
        velocity.y -= 20 * delta;
        camera.position.y += velocity.y * delta;

        // Ground collision
        if (camera.position.y < 1.7) {
            camera.position.y = 1.7;
            velocity.y = 0;
            canJump = true;
        }

        // Update enemies
        updateEnemies(delta);
        
        // Update minimap
        updateMinimap();
        
        // Weapon sway
        if (moveState.forward || moveState.backward || moveState.left || moveState.right) {
            const time = now * 0.005;
            weapon.position.x = 0.3 + Math.sin(time) * 0.01;
            weapon.position.y = -0.3 + Math.cos(time * 2) * 0.005;
        }
    }

    renderer.render(scene, camera);
}

// Handle window resize
window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
});

// Start animation loop
animate();

console.log('FPS Prototype loaded! Click "Start" to play.');
