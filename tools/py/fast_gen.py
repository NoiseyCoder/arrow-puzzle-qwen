"""Fast reference generator: same algorithm as ref_gen.py (bit-identical results), but the
hot loops are NumPy-vectorized. Emits golden hashes: level w h arrows crc32hex."""
import sys, zlib
import numpy as np

U32 = np.uint32

class Mulberry32:
    """Vectorized mulberry32 producing 32-bit outputs in exact stream order."""
    def __init__(self, seed):
        self.state = seed & 0xFFFFFFFF
    def next_int(self):
        st = (self.state + 0x6D2B79F5) & 0xFFFFFFFF
        self.state = st
        t = st
        t = ((t ^ (t >> 15)) * (t | 1)) & 0xFFFFFFFF
        t = (t ^ ((t + (((t ^ (t >> 7)) * (t | 61)) >> 14)) & 0xFFFFFFFF)) & 0xFFFFFFFF
        v = (t ^ (t >> 14)) & 0xFFFFFFFF
        self.state = v
        return v
    def bounded(self, bound):
        return self.next_int() % bound
    def nextFloat(self):
        return (self.next_int() >> 8) * 1.0 / 16777216.0

DX = np.array([0, 0, -1, 1], dtype=np.int32)   # UP DOWN LEFT RIGHT
DY = np.array([-1, 1, 0, 0], dtype=np.int32)

SHAPES = {
 "HEART": [(-1,-0.6),(-0.75,0.35),(-0.45,0.85),(0,0.45),(0.45,0.85),(0.75,0.35),(1,-0.6),(0,-1)],
 "CAT":   [(-0.9,0.75),(-0.6,1.0),(-0.45,0.6),(0.45,0.6),(0.6,1.0),(0.9,0.75),(0.95,-0.9),(-0.95,-0.9)],
 "DOG":   [(-1,0.2),(-0.7,0.8),(-0.3,0.55),(0.3,0.55),(0.5,0.9),(0.85,0.6),(1,-0.2),(0.6,-0.95),(-0.6,-0.95)],
 "ANCHOR":[(0,1),(0.35,0.75),(0.15,0.6),(0.15,-0.2),(0.85,-0.45),(0.6,-1),(0,-0.7),(-0.6,-1),(-0.85,-0.45),(-0.15,-0.2),(-0.15,0.6),(-0.35,0.75)],
 "TROPHY":[(-0.8,0.9),(-0.8,0.2),(-0.45,-0.1),(-0.2,-0.1),(-0.2,-0.5),(-0.5,-0.9),(-0.5,-1),(0.5,-1),(0.5,-0.9),(0.2,-0.5),(0.2,-0.1),(0.45,-0.1),(0.8,0.2),(0.8,0.9)],
 "STAR":  [(0,1),(0.2245,0.309),(0.951,0.309),(0.363,-0.118),(0.588,-0.809),(0,-0.382),(-0.588,-0.809),(-0.363,-0.118),(-0.951,0.309),(-0.2245,0.309)],
 "TREE":  [(0,1),(-0.5,0.35),(-0.25,0.35),(-0.75,-0.35),(-0.35,-0.35),(-0.85,-0.95),(0.85,-0.95),(0.35,-0.35),(0.75,-0.35),(0.25,0.35),(0.5,0.35)],
 "FISH":  [(-1,0),(-0.55,0.5),(0.25,0.55),(0.95,0.15),(0.95,-0.15),(0.25,-0.55),(-0.55,-0.5)],
}

def shape_cells_np(w, h, kind):
    if kind == "RECT":
        return np.ones((h, w), dtype=bool)
    pts = SHAPES.get(kind)
    if pts is None:
        return np.ones((h, w), dtype=bool)
    poly = [(x, -y) for (x, y) in pts]           # flip y: polygon y up-positive
    gx = (np.arange(w) + 0.5) / w * 2.0 - 1.0
    gy = (np.arange(h) + 0.5) / h * 2.0 - 1.0
    X, Y = np.meshgrid(gx, gy)
    inside = np.zeros((h, w), dtype=bool)
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]; xj, yj = poly[j]
        cond = ((yi > Y) != (yj > Y)) & (X < (xj - xi) * (Y - yi) / (yj - yi) + xi)
        inside ^= cond
        j = i
    m = inside
    if m.sum() < 8:
        m = np.ones((h, w), dtype=bool)
    return m

def turn_penalty(turns):
    p = 0
    for i in range(1, len(turns)):
        d = abs(turns[i] - turns[i-1])
        if d == 2: p += 2
    return p

def params_for(n):
    is_breather = (n % 10 == 0)
    is_milestone = (n % 25 == 0)
    target = 0.50 + min(n, 1000) / 1000.0 * 0.45
    if is_breather: target *= 0.70
    if n <= 5:
        w = 6 + (n - 1); maxArrows = 12; minFill = 0.80; maxFill = 0.90; lmin, lmax = 2, 5; tmax = 2
    elif n <= 30:
        w = 12 + (n - 6) * 6 // 25; maxArrows = 60; minFill = 0.92; maxFill = 0.98; lmin, lmax = 2, 8; tmax = 3
    elif n <= 150:
        w = 18 + (n - 31) * 8 // 120; maxArrows = 120; minFill = 0.92; maxFill = 0.98; lmin, lmax = 3, 10; tmax = 4
    else:
        w = min(26 + (n - 151) * 14 // 450, 60); maxArrows = 400; minFill = 0.92; maxFill = 0.98; lmin, lmax = 3, 14; tmax = 5
    shape = "RECT"
    if is_milestone and n >= 25:
        _ms = ["HEART", "STAR", "CAT", "DOG", "ANCHOR", "TROPHY", "TREE", "FISH"]
        shape = _ms[(n // 25 - 1) % len(_ms)]
    return dict(w=w, h=w, maxArrows=maxArrows, minFill=minFill, maxFill=maxFill,
                lmin=lmin, lmax=lmax, tmax=tmax, target=target, shape=shape)

# ---------------------------------------------------------------- solver-side helpers

def owner_to_arrows(owner, w, h):
    """owner int16 array (cell -> arrow id or -1) -> list of paths tail->head (id order)."""
    n = int(owner.max()) + 1
    if n <= 0: return []
    idx = np.argsort(owner.ravel(), kind="stable")
    counts = np.bincount(owner.ravel()[owner.ravel() >= 0].astype(np.int64), minlength=n)
    starts = np.concatenate(([0], np.cumsum(counts)))
    arrows = []
    for i in range(n):
        flat = idx[starts[i]:starts[i+1]]
        ys, xs = flat // w, flat % w
        arrows.append(list(zip(xs.tolist(), ys.tolist())))
    return arrows

def head_dirs(arrows):
    dirs = []
    for p in arrows:
        hx, hy = p[-1]; lx, ly = p[-2]
        dirs.append((1 if hx > lx else -1 if hx < lx else 0, 1 if hy > ly else -1 if hy < ly else 0))
    return dirs

def free_mask(arrows, dirs, alive, w, h):
    """Bool array: which alive arrows can currently slide out (full-board raycast)."""
    occ = np.full((h, w), -1, dtype=np.int32)
    for i, p in enumerate(arrows):
        if not alive[i]: continue
        for (x, y) in p: occ[y, x] = i
    fm = np.zeros(len(arrows), dtype=bool)
    for i, p in enumerate(arrows):
        if not alive[i]: continue
        dx, dy = dirs[i]
        hx, hy = p[-1]
        rx, ry = hx + dx, hy + dy
        ok = True
        while 0 <= rx < w and 0 <= ry < h:
            o = occ[ry, rx]
            if o != -1 and o != i: ok = False; break
            rx += dx; ry += dy
        fm[i] = ok
    return fm

def difficulty_np(arrows, dirs, w, h):
    n = len(arrows)
    if n == 0: return 0.0
    occ = np.full((h, w), -1, dtype=np.int32)
    for i, p in enumerate(arrows):
        for (x, y) in p: occ[y, x] = i
    deps = [[] for _ in range(n)]
    for i, p in enumerate(arrows):
        dx, dy = dirs[i]
        hx, hy = p[-1]
        rx, ry = hx + dx, hy + dy
        seen = set()
        while 0 <= rx < w and 0 <= ry < h:
            j = int(occ[ry, rx])
            if j != -1 and j != i and j not in seen:
                seen.add(j); deps[i].append(j)
            rx += dx; ry += dy
    memo = [-1]*n
    visiting = [False]*n
    def calc(i):
        if memo[i] >= 0: return memo[i]
        if visiting[i]: return 0
        visiting[i] = True
        m = 0
        for j in deps[i]:
            v = calc(j) + 1
            if v > m: m = v
        visiting[i] = False
        memo[i] = m
        return m
    longest = 0
    for i in range(n):
        v = calc(i)
        if v > longest: longest = v
    initial_free = sum(1 for i in range(n) if not deps[i])
    avg_blockers = sum(len(d) for d in deps) / float(n)
    norm_longest = min(longest / 12.0, 1.0)
    norm_free = initial_free / float(n)
    norm_density = min(avg_blockers / 4.0, 1.0)
    return 0.45*norm_longest + 0.30*(1.0-norm_free) + 0.25*norm_density

def greedy_ok(arrows, dirs, w, h):
    alive = np.ones(len(arrows), dtype=bool)
    cnt = int(alive.sum())
    while cnt:
        fm = free_mask(arrows, dirs, alive, w, h)
        cand = np.flatnonzero(fm & alive)
        if cand.size == 0: return False
        alive[cand[0]] = False
        cnt -= 1
    return True

def content_hash_from_owner(owner, w, h):
    n = int(owner.max()) + 1
    if n <= 0:
        data = bytearray([w & 0xFF, h & 0xFF])
        return format(zlib.crc32(bytes(data)) & 0xFFFFFFFF, "08X")
    idx = np.argsort(owner.ravel(), kind="stable")
    flat = idx[owner.ravel() >= 0]
    counts = np.bincount(owner.ravel()[owner.ravel() >= 0].astype(np.int64), minlength=n)
    starts = np.concatenate(([0], np.cumsum(counts)))
    data = bytearray()
    data.append(w & 0xFF); data.append(h & 0xFF)
    for i in range(n):
        seg = flat[starts[i]:starts[i+1]]
        ys = seg // w; xs = seg % w
        path = list(zip(xs.tolist(), ys.tolist()))  # row-major == tail->head by construction
        data.append(len(path))
        for (x, y) in path:
            data.append(x); data.append(y)
    return format(zlib.crc32(bytes(data)) & 0xFFFFFFFF, "08X")

# ---------------------------------------------------------------- generator

def grow_head_first(rng, w, h, mask_np, owner, L, T):
    free_idx = np.flatnonzero(mask_np.ravel() & (owner.ravel() == -1))
    if free_idx.size == 0: return None
    pick = int(free_idx[rng.bounded(int(free_idx.size))])
    hx, hy = pick % w, pick // w
    d0 = rng.bounded(4)
    fx0, fy0 = hx + int(DX[d0]), hy + int(DY[d0])
    if 0 <= fx0 < w and 0 <= fy0 < h and owner[fy0*w+fx0] != -1: return None
    px, py = hx - int(DX[d0]), hy - int(DY[d0])
    if not (0 <= px < w and 0 <= py < h): return None
    cell = py*w+px
    if not mask_np.ravel()[cell] or owner[cell] != -1: return None
    cells = [(hx, hy), (px, py)]
    used = {(hx, hy), (px, py)}
    dirs = [d0]
    cur_x, cur_y = px, py
    remaining_turns = T
    for _ in range(L - 2):
        opts = []
        for d in range(4):
            nx, ny = cur_x + int(DX[d]), cur_y + int(DY[d])
            if not (0 <= nx < w and 0 <= ny < h): continue
            c = ny*w+nx
            if not mask_np.ravel()[c] or owner[c] != -1 or (nx, ny) in used: continue
            prev = dirs[-1]
            if d == prev: pen = 0
            elif abs(d - prev) == 2: pen = 2
            else: pen = 1
            if pen > remaining_turns: continue
            opts.append((d, nx, ny, pen))
        if not opts: break
        weights = []
        for (d, nx, ny, pen) in opts:
            base = 3.0 if pen == 0 else 1.0
            fwd = 0.0
            pd = dirs[-1]
            if int(DX[pd])*int(DX[d]) + int(DY[pd])*int(DY[d]) == 1:
                fx, fy = nx + int(DX[d]), ny + int(DY[d])
                if 0 <= fx < w and 0 <= fy < h:
                    c2 = fy*w+fx
                    if mask_np.ravel()[c2] and owner[c2] == -1 and (fx, fy) not in used:
                        fwd = 1.0
            weights.append(base + fwd)
        r = rng.nextFloat() * sum(weights)
        acc = 0.0
        chosen = opts[-1]
        for k, wt in enumerate(weights):
            acc += wt
            if r <= acc: chosen = opts[k]; break
        d, nx, ny, pen = chosen
        if pen > 0: remaining_turns -= pen
        dirs.append(d)
        cur_x, cur_y = nx, ny
        used.add((nx, ny)); cells.append((nx, ny))
    if len(cells) < 2: return None
    path = list(reversed(cells))     # tail -> head
    # clearable_now: head has a straight free line to the edge through EMPTY cells only
    if not clearable_now(path, owner, w, h): return None
    # every non-head cell immediately clearable
    for (cx, cy) in path[:-1]:
        if not cell_clearable(cx, cy, mask_np, owner, w, h): return None
    return path, d0

def clearable_now(path, owner, w, h):
    hx, hy = path[-1]; lx, ly = path[-2]
    dx = 1 if hx > lx else -1 if hx < lx else 0
    dy = 1 if hy > ly else -1 if hy < ly else 0
    rx, ry = hx + dx, hy + dy
    while 0 <= rx < w and 0 <= ry < h:
        if owner[ry*w+rx] != -1: return False
        rx += dx; ry += dy
    return True

def cell_clearable(x, y, mask_np, owner, w, h):
    if owner[y*w+x] != -1: return False
    for d in range(4):
        rx, ry = x + int(DX[d]), y + int(DY[d])
        ok = True
        while 0 <= rx < w and 0 <= ry < h:
            if owner[ry*w+rx] != -1: ok = False; break
            rx += int(DX[d]); ry += int(DY[d])
        if ok: return True
    return False

def try_build(rng, prm):
    w, h = prm["w"], prm["h"]
    mask_np = shape_cells_np(w, h, prm["shape"])
    total = int(mask_np.sum())
    owner = np.full(w*h, -1, dtype=np.int16)
    paths = []
    fill_lo = int(total * prm["minFill"] + 0.5)
    fill_hi = int(total * prm["maxFill"] + 0.5)
    filled = 0
    guard = 0
    hard_cap = prm["maxArrows"] * 2
    while filled < fill_lo and guard < 6000:
        guard += 1
        if len(paths) >= hard_cap: break
        L = prm["lmin"] + rng.bounded(prm["lmax"] - prm["lmin"] + 1)
        grown = grow_head_first(rng, w, h, mask_np, owner, L, prm["tmax"])
        if grown is None and guard % 5 == 0:
            grown = grow_head_first(rng, w, h, mask_np, owner, prm["lmin"], prm["tmax"] + 2)
        if grown is None: continue
        path, d0 = grown
        if filled + len(path) > fill_hi and filled >= fill_lo: break
        idx = len(paths)
        for (px, py) in path: owner[py*w+px] = idx
        paths.append(path)
        filled += len(path)
    if not paths: return None
    if filled < fill_lo and filled < fill_lo // 2: return None
    dirs = [None]*len(paths)
    for i, p in enumerate(paths):
        hx, hy = p[-1]; lx, ly = p[-2]
        dirs[i] = (1 if hx > lx else -1 if hx < lx else 0, 1 if hy > ly else -1 if hy < ly else 0)
    if not greedy_ok(paths, dirs, w, h): return None
    dev = abs(difficulty_np(paths, dirs, w, h) - prm["target"])
    return dev, owner, w, h

def generate_level(n, pack_id):
    s = (n * 2654435761 + pack_id * 974711) & 0xFFFFFFFF
    s ^= (s >> 15); s = (s * 2246822519) & 0xFFFFFFFF
    s ^= (s >> 13); s = (s * 3266489917) & 0xFFFFFFFF
    s ^= (s >> 16)
    rng = Mulberry32(s)
    prm = params_for(n)
    best = None
    for _ in range(6):
        res = try_build(rng, prm)
        if res is not None and (best is None or res[0] < best[0]):
            best = res
    if best is None: raise RuntimeError("no candidate for level %d" % n)
    dev, owner, w, h = best
    return w, h, int(owner.max()) + 1, content_hash_from_owner(owner, w, h)

if __name__ == "__main__":
    N = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    pack_id = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    out = []
    for n in range(1, N + 1):
        w, h, a, chash = generate_level(n, pack_id)
        out.append("%d %dx%d %d %s" % (n, w, h, a, chash))
    print("\n".join(out))
