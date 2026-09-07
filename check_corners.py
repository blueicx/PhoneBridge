from PIL import Image
import sys

path = sys.argv[1]
img = Image.open(path).convert("RGB")
W, H = img.size
px = img.load()
print("size", W, H)

scan_x0, scan_y0, scan_x1, scan_y1 = (
    int(value)
    for value in sys.argv[2:6]
) if len(sys.argv) >= 6 else (760, 168, 1024, 516)

# Detect teal-ish stroke pixels (R low-mid, G high, B low-mid) typical of #8FF0C4
minx = W; miny = H; maxx = 0; maxy = 0
teal = 0
for y in range(scan_y0, min(scan_y1, H), 1):
    for x in range(scan_x0, min(scan_x1, W), 1):
        r, g, b = px[x, y]
        if g > 150 and r < 180 and b < 200 and g > r + 30 and g > b + 20:
            teal += 1
            if x < minx: minx = x
            if y < miny: miny = y
            if x > maxx: maxx = x
            if y > maxy: maxy = y
print(
    "teal pixels", teal,
    "bbox", minx, miny, maxx, maxy,
    "scan", scan_x0, scan_y0, scan_x1, scan_y1,
)

def avg(region):
    rs = gs = bs = n = 0
    for (x0, y0, x1, y1) in region:
        for x in range(x0, x1):
            for y in range(y0, y1):
                r, g, b = px[x, y]
                rs += r; gs += g; bs += b; n += 1
    return (rs // n, gs // n, bs // n)

# Use detected bbox or fallback estimate
if teal < 20:
    minx, miny, maxx, maxy = scan_x0, scan_y0, scan_x1, scan_y1
bx0, by0, bx1, by1 = minx, miny, maxx, maxy
cx = (bx0 + bx1) // 2
cy = (by0 + by1) // 2
inset = 12
# edge midpoints (should be camera/stroke, brighter)
edge_top = [(cx - 20, by0 + inset, cx + 20, by0 + inset + 16)]
edge_bot = [(cx - 20, by1 - inset - 16, cx + 20, by1 - inset)]
edge_left = [(bx0 + inset, cy - 20, bx0 + inset + 16, cy + 20)]
edge_right = [(bx1 - inset - 16, cy - 20, bx1 - inset, cy + 20)]
# corners (should be background if rounded)
corner = 28
corner_tl = [(bx0, by0, bx0 + corner, by0 + corner)]
corner_tr = [(bx1 - corner, by0, bx1, by0 + corner)]
corner_bl = [(bx0, by1 - corner, bx0 + corner, by1)]
corner_br = [(bx1 - corner, by1 - corner, bx1, by1)]

print("edge_top", avg(edge_top))
print("edge_bot", avg(edge_bot))
print("edge_left", avg(edge_left))
print("edge_right", avg(edge_right))
print("corner_tl", avg(corner_tl))
print("corner_tr", avg(corner_tr))
print("corner_bl", avg(corner_bl))
print("corner_br", avg(corner_br))
