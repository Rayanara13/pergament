"""Логотип Пергамента: свёрнутая по краям карта на пергаменте, река, тропа пунктиром, булавка, роза ветров.
Рисуется в 64×64 «пикселях» (как текстуры игры) и увеличивается без сглаживания.

python tools/gen_logo.py  → src/main/resources/pergament_logo.png (256×256), docs/modrinth/icon.png (512×512)
"""
import math
import os
import random

from PIL import Image, ImageDraw

N = 64
PARCH, PARCH_D, PARCH_L = (236, 220, 178), (206, 184, 134), (246, 236, 206)
INK, INK_S = (58, 40, 20), (101, 75, 44)
WATER, WATER_D = (92, 130, 168), (66, 98, 134)
GREEN, GREEN_D = (110, 142, 74), (82, 110, 54)
RED, GOLD = (142, 42, 28), (201, 162, 74)

img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
rnd = random.Random(13)

# лист с завёрнутыми краями сверху и снизу
d.rectangle([6, 9, 57, 54], fill=PARCH)
for x in range(6, 58):
    for y in range(9, 55):
        if rnd.random() < 0.12:
            img.putpixel((x, y), PARCH_D if rnd.random() < 0.5 else PARCH_L)
for y0, y1 in ((5, 10), (53, 58)):                         # свитки
    d.rectangle([4, y0, 59, y1], fill=PARCH_D)
    d.line([4, y0, 59, y0], fill=INK_S)
    d.line([4, y1, 59, y1], fill=INK_S)
    d.line([4, y0, 4, y1], fill=INK)
    d.line([59, y0, 59, y1], fill=INK)
    d.line([6, (y0 + y1) // 2, 57, (y0 + y1) // 2], fill=PARCH_L)
d.line([6, 10, 6, 53], fill=INK_S)
d.line([57, 10, 57, 53], fill=INK_S)

# лес пятнами
for cx, cy, r in ((27, 19, 5), (20, 45, 5), (51, 23, 3), (47, 44, 6)):
    for x in range(cx - r, cx + r + 1):
        for y in range(cy - r, cy + r + 1):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r and 7 <= x <= 56 and 11 <= y <= 52:
                img.putpixel((x, y), GREEN_D if rnd.random() < 0.35 else GREEN)

# река
pts = [(int(8 + t * 48), int(31 + 6 * math.sin(t * 6.0))) for t in [i / 60 for i in range(61)]]
for x, y in pts:
    for dy in (-1, 0, 1, 2):
        if 11 <= y + dy <= 52:
            img.putpixel((x, y + dy), WATER if dy in (0, 1) else WATER_D)

# тропа пунктиром к булавке
path = [(12, 48), (18, 45), (24, 41), (30, 37), (34, 33), (38, 28), (41, 24)]
for i, (x, y) in enumerate(path):
    d.rectangle([x, y, x + 1, y + 1], fill=RED if i % 2 == 0 else INK)

# булавка
d.ellipse([39, 14, 45, 20], fill=RED, outline=INK)
d.line([42, 20, 42, 25], fill=INK)
img.putpixel((41, 16), (210, 120, 100))

# роза ветров в углу
cx, cy = 15, 15
d.polygon([(cx, cy - 5), (cx + 1, cy), (cx, cy + 5), (cx - 1, cy)], fill=INK)
d.polygon([(cx - 5, cy), (cx, cy - 1), (cx + 5, cy), (cx, cy + 1)], fill=INK_S)
img.putpixel((cx, cy - 5), RED)
img.putpixel((cx, cy), GOLD)

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
img.resize((256, 256), Image.NEAREST).save(os.path.join(root, "src/main/resources/pergament_logo.png"))
os.makedirs(os.path.join(root, "docs/modrinth"), exist_ok=True)
img.resize((512, 512), Image.NEAREST).save(os.path.join(root, "docs/modrinth/icon.png"))
print("ok")
