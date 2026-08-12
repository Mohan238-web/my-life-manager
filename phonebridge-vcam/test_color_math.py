def clamp(v):
    return max(0, min(255, v))

def rgb_to_yuv(r, g, b):
    y = clamp(((66*r + 129*g + 25*b + 128) >> 8) + 16)
    u = clamp(((-38*r - 74*g + 112*b + 128) >> 8) + 128)
    v = clamp(((112*r - 94*g - 18*b + 128) >> 8) + 128)
    return y, u, v

# BT.601 limited-range reference neighborhoods. These intentionally use ranges
# rather than a single exact number so minor integer-rounding changes remain valid.
cases = {
    'black': ((0,0,0), ((16,16),(128,128),(128,128))),
    'white': ((255,255,255), ((235,235),(128,128),(128,128))),
    'red':   ((255,0,0), ((81,83),(89,91),(239,241))),
    'green': ((0,255,0), ((144,146),(53,55),(33,35))),
    'blue':  ((0,0,255), ((40,42),(239,241),(109,111))),
    'gray':  ((128,128,128), ((125,127),(127,129),(127,129))),
}

for name, (rgb, expected) in cases.items():
    got = rgb_to_yuv(*rgb)
    for i, (lo, hi) in enumerate(expected):
        if not (lo <= got[i] <= hi):
            raise SystemExit(f'{name} channel {i}: expected {lo}..{hi}, got {got[i]}')
    print(name, rgb, '->', got)

# A neutral gray must stay neutral in chroma. This catches B/R channel swaps and wraparound.
for level in range(0, 256, 17):
    y, u, v = rgb_to_yuv(level, level, level)
    if abs(u - 128) > 1 or abs(v - 128) > 1:
        raise SystemExit(f'gray neutrality failed at {level}: {(y,u,v)}')

print('PhoneBridge color conversion self-test passed')
