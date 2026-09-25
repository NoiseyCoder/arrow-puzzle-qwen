"""Reference implementation of the Arrow Escape level generator (mulberry32, integer math).
Mirrors tools/LevelPackBuilder.kt exactly. Emits golden content hashes for levels 1..N."""
import sys

U = 0xFFFFFFFF

class Mulberry32:
    def __init__(self, seed):
        self.state = seed & U
    def next_int(self):
        self.state = (self.state + 0x6D2B79F5) & U
        t = self.state
        t = ((t ^ (t >> 15)) * (t | 1)) & U
        t ^= (t + (((t ^ (t >> 7)) * (t | 61)) & U)) & U
        return (t ^ (t >> 14)) & U
    def bounded(self, bound):
        if bound <= 0: raise ValueError("bound must be positive")
        return self.next_int() % bound
    def nextFloat(self):
        return (self.next_int() >> 8) * 1.0 / 16777216.0

DX = [0, 0, -1, 1]  # UP DOWN LEFT RIGHT
DY = [-1, 1, 0, 0]

def _in_poly(px, py, poly):
    inside = False
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]; xj, yj = poly[j]
        if ((yi > py) != (yj > py)) and (px < (xj - xi) * (py - yi) / (yj - yi) + xi):
            inside = not inside
        j = i
    return inside

def _fill_poly(w, h, norm_poly):
    """norm_poly in normalized coords [-1,1]; pixel center at (x+0.5)/w*2-1."""
    cells = set()
    for y in range(h):
        for x in range(w):
            nx = ((x + 0.5) / w) * 2.0 - 1.0
            ny = ((y + 0.5) / h) * 2.0 - 1.0
            if _in_poly(nx, ny, norm_poly):
                cells.add((x, y))
    return cells

# Signature shapes as integer-normalized polygons (x, y up-positive).
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

def shape_cells(w, h, kind):
    if kind == "RECT":
        return set((x, y) for y in range(h) for x in range(w))
    pts = SHAPES.get(kind)
    if pts is None:
        return set((x, y) for y in range(h) for x in range(w))
    # flip y: our polygon y is up-positive, grid y grows downward
    flipped = [(x, -y) for (x, y) in pts]
    cells = _fill_poly(w, h, flipped)
    if len(cells) < 8:
        return set((x, y) for y in range(h) for x in range(w))
    return cells

def turn_penalty(turns):
    p = 0
    for i in range(1, len(turns)):
        d = abs(turns[i] - turns[i-1])
        if d == 2: p += 2
    return p

def generate(level_number, rng):
    n = level_number
    is_breather = (n % 10 == 0)
    is_milestone = (n % 25 == 0)
    target = 0.50 + min(n, 1000) / 1000.0 * 0.45
    if is_breather: target *= 0.70
    if n <= 5:
        w = 6 + (n - 1); h = w; maxArrows = 12; minFill = 0.80; maxFill = 0.90; lmin, lmax = 2, 5; tmax = 2
    elif n <= 30:
        w = 12 + (n - 6) * 6 // 25; h = w; maxArrows = 60; minFill = 0.92; maxFill = 0.98; lmin, lmax = 2, 8; tmax = 3
    elif n <= 150:
        w = 18 + (n - 31) * 8 // 120; h = w; maxArrows = 120; minFill = 0.92; maxFill = 0.98; lmin, lmax = 3, 10; tmax = 4
    else:
        w = min(26 + (n - 151) * 14 // 450, 60); h = w; maxArrows = 400; minFill = 0.92; maxFill = 0.98; lmin, lmax = 3, 14; tmax = 5
    shape = "RECT"
    if is_milestone and n >= 25:
        _ms = ["HEART", "STAR", "CAT", "DOG", "ANCHOR", "TROPHY", "TREE", "FISH"]
        shape = _ms[(n // 25 - 1) % len(_ms)]
    mask = shape_cells(w, h, shape)
    total_mask = len(mask)
    best = None
    for c in range(6):
        res = try_build(rng, w, h, mask, total_mask, maxArrows, minFill, maxFill, lmin, lmax, tmax)
        if res is not None:
            sc = difficulty(res[0], w, h)
            dev = abs(sc - target)
            if best is None or dev < best[0]:
                best = (dev, res[0], res[1])
    if best is None:
        raise RuntimeError("no candidate for level %d" % n)
    return best[1], best[2], w, h

def try_build(rng, w, h, mask, total_mask, maxArrows, minFill, maxFill, lmin, lmax, tmax):
    """Place pieces in REVERSE removal order. A piece is accepted only if its head ray
    crosses no already-placed cell AND every body cell (other than the head) is a
    currently free cell that is itself immediately clearable (a straight free line to
    the edge exists). That keeps every blocking relation acyclic => solvable by
    construction; the greedy pass below is a safety net."""
    occ = [[False]*w for _ in range(h)]
    owner = [[-1]*w for _ in range(h)]   # cell -> arrow index occupying it, -1 if free
    arrows = []
    fill_lo = int(total_mask * minFill)
    fill_hi = int(total_mask * maxFill)
    filled = 0
    guard = 0
    hard_cap = maxArrows * 2
    while filled < fill_lo and guard < 6000:
        guard += 1
        if len(arrows) >= hard_cap: break
        L = lmin + rng.bounded(lmax - lmin + 1)
        grown = grow_head_first(rng, w, h, mask, occ, owner, arrows, L, tmax)
        if grown is None and guard % 5 == 0:
            grown = grow_head_first(rng, w, h, mask, occ, owner, arrows, lmin, tmax + 2)
        if grown is None: continue
        if filled + len(grown) > fill_hi and filled >= fill_lo: break
        idx = len(arrows)
        for (px, py) in grown:
            occ[py][px] = True
            owner[py][px] = idx
        arrows.append(grown)
        filled += len(grown)
    if filled < fill_lo:
        if filled < fill_lo // 2 or not arrows: return None
    active = list(range(len(arrows)))
    removed_order = []
    while active:
        idx = greedy_free(arrows, active, w, h)
        if idx is None: return None
        active.remove(idx); removed_order.append(idx)
    return arrows, removed_order

def cell_clearable(x, y, mask, owner, w, h):
    """Free cell (x,y) with at least one fully-free straight line to an edge.
    Outside-mask cells count as free for this check (the runtime raycast ignores them)."""
    if owner[y][x] != -1: return False
    for d in range(4):
        rx, ry = x + DX[d], y + DY[d]
        ok = True
        while 0 <= rx < w and 0 <= ry < h:
            if owner[ry][rx] != -1: ok = False; break
            rx += DX[d]; ry += DY[d]
        if ok: return True
    return False

def clearable_now(p, owner, w, h):
    hx, hy = p[-1]; last = p[-2]
    dx, dy = sign(hx-last[0]), sign(hy-last[1])
    rx, ry = hx+dx, hy+dy
    while 0 <= rx < w and 0 <= ry < h:
        if owner[ry][rx] != -1: return False
        rx += dx; ry += dy
    return True

def grow_head_first(rng, w, h, mask, occ, owner, arrows, L, T):
    heads = [(x, y) for y in range(h) for x in range(w)
             if (x, y) in mask and not occ[y][x]]
    if not heads: return None
    hx, hy = heads[rng.bounded(len(heads))]
    d0 = rng.bounded(4)
    fx0, fy0 = hx + DX[d0], hy + DY[d0]
    if 0 <= fx0 < w and 0 <= fy0 < h:
        if occ[fy0][fx0]: return None
    px, py = hx - DX[d0], hy - DY[d0]
    if not (0 <= px < w and 0 <= py < h): return None
    if (px, py) not in mask or occ[py][px]: return None
    cells = [(hx, hy), (px, py)]
    used = {(hx, hy), (px, py)}
    dirs = [d0]
    cur = (px, py)
    remaining_turns = T
    for s in range(L - 2):
        opts = []
        for d in range(4):
            nx, ny = cur[0]+DX[d], cur[1]+DY[d]
            if not (0 <= nx < w and 0 <= ny < h): continue
            if (nx, ny) not in mask: continue
            if occ[ny][nx] or (nx, ny) in used: continue
            pen = 0
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
            if DX[pd]*DX[d] + DY[pd]*DY[d] == 1:
                fx, fy = nx + DX[d], ny + DY[d]
                if 0 <= fx < w and 0 <= fy < h and (fx, fy) in mask and not occ[fy][fx] and (fx, fy) not in used:
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
        cur = (nx, ny); used.add(cur); cells.append(cur)
    if len(cells) < 2: return None
    path = list(reversed(cells))  # tail -> head
    if not clearable_now(path, owner, w, h): return None
    for c in path[:-1]:
        if not cell_clearable(c[0], c[1], mask, owner, w, h): return None
    return path

def greedy_free(arrows, active, w, h):
    occ = {}
    for i in active:
        for (x, y) in arrows[i]:
            occ[(x, y)] = i
    best = None; bestCnt = 10**9
    for i in active:
        p = arrows[i]
        hx, hy = p[-1]; last = p[-2]
        dx, dy = sign(hx-last[0]), sign(hy-last[1])
        rx, ry = hx+dx, hy+dy
        ok = True; cnt = 0
        while 0 <= rx < w and 0 <= ry < h:
            j = occ.get((rx, ry))
            if j is not None and j != i: ok = False; break
            rx += dx; ry += dy
        if ok:
            # count how many cells this arrow's ray passes (proxy for future blocking power)
            if cnt < bestCnt: bestCnt = cnt; best = i
    return best

def sign(v): return (v > 0) - (v < 0)

def difficulty(arrows, w, h):
    """Blocking graph restricted to arrows placed EARLIER (which are removed LATER).
    That makes the graph a DAG by construction; longest dependency chain via memo DFS."""
    n = len(arrows)
    if n == 0: return 0.0
    occ = {}
    for i, p in enumerate(arrows):
        for c in p: occ[c] = i
    deps = [[] for _ in range(n)]
    for i, p in enumerate(arrows):
        hx, hy = p[-1]; last = p[-2]
        dx, dy = sign(hx-last[0]), sign(hy-last[1])
        rx, ry = hx+dx, hy+dy
        seen = set()
        while 0 <= rx < w and 0 <= ry < h:
            j = occ.get((rx, ry))
            if j is not None and j != i and j < i and j not in seen:
                seen.add(j); deps[i].append(j)
            rx += dx; ry += dy
    memo = [-1]*n
    def calc(i):
        if memo[i] >= 0: return memo[i]
        m = 0
        for j in deps[i]:
            v = calc(j) + 1
            if v > m: m = v
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

def content_hash(arrows, w, h):
    data = bytearray()
    data.append(w & 0xFF); data.append(h & 0xFF)
    for p in arrows:
        data.append(len(p))
        for (x, y) in p:
            data.append(x); data.append(y)
    import zlib
    return format(zlib.crc32(bytes(data)) & U, "08X")

if __name__ == "__main__":
    N = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    out = []
    pack_id = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    for n in range(1, N+1):
        s = (n * 2654435761 + pack_id * 974711) & U
        s ^= (s >> 15); s = (s * 2246822519) & U
        s ^= (s >> 13); s = (s * 3266489917) & U
        s ^= (s >> 16)
        rng = Mulberry32(s)
        rng = Mulberry32(s)
        arrows, ro, w, h = generate(n, rng)
        out.append("%d %dx%d %d %s" % (n, w, h, len(arrows), content_hash(arrows, w, h)))
    print("\n".join(out))
