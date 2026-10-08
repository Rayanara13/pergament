"""Рисует GUI-ассеты «Пергамента» (свои, не из MapMinecraft): иконки панели 16×16 тушью и розу ветров 64×64.

Запуск: python tools/gen_gui.py  → src/main/resources/assets/pergament/textures/gui/
"""
import math
import os
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "pergament", "textures", "gui")
INK = (58, 40, 20, 255)
RED = (142, 42, 28, 255)
GOLD = (201, 162, 74, 255)
GOLD_D = (138, 106, 42, 255)
BLUE = (60, 96, 128, 255)
PAPER = (241, 228, 192, 255)


def icon(name, draw_fn):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    draw_fn(d)
    im.save(os.path.join(OUT, "icon_" + name + ".png"))


def magnifier(d, plus):
    d.ellipse((1, 1, 10, 10), outline=INK, width=2)
    d.line((9, 9, 14, 14), fill=INK, width=3)
    d.line((3, 6, 8, 6), fill=INK)
    if plus:
        d.line((5, 4, 5, 8), fill=INK)  # вертикаль плюса чуть смещена — читается в 16px


def target(d):
    d.ellipse((2, 2, 13, 13), outline=INK, width=1)
    d.ellipse((5, 5, 10, 10), outline=RED, width=1)
    for a, b in (((7, 0), (7, 3)), ((7, 12), (7, 15)), ((0, 7), (3, 7)), ((12, 7), (15, 7))):
        d.line((a, b), fill=INK)


def close(d):
    d.line((3, 3, 12, 12), fill=INK, width=2)
    d.line((12, 3, 3, 12), fill=INK, width=2)


def pin(d):
    d.ellipse((4, 1, 11, 8), fill=RED, outline=INK)
    d.polygon([(5, 7), (10, 7), (7, 14)], fill=RED, outline=INK)
    d.point((7, 4), fill=PAPER)


def ruler(d):
    d.polygon([(1, 11), (11, 1), (14, 4), (4, 14)], fill=GOLD, outline=INK)
    for k in range(3, 11, 2):
        d.line((k, 12 - k + 1, k + 1, 12 - k + 2), fill=INK)


def pen(d):
    d.line((3, 12, 12, 3), fill=INK, width=3)
    d.line((12, 3, 14, 1), fill=GOLD_D, width=2)
    d.polygon([(1, 14), (3, 10), (5, 12)], fill=INK)


def eye(d):
    d.ellipse((1, 4, 14, 11), outline=INK, width=1)
    d.ellipse((5, 5, 10, 10), fill=BLUE, outline=INK)
    d.point((7, 7), fill=INK)


def globe(d):
    d.ellipse((1, 1, 14, 14), fill=BLUE, outline=INK)
    d.line((1, 7, 14, 7), fill=INK)
    d.ellipse((5, 1, 10, 14), outline=INK)


def gear(d):
    c = 7.5
    pts = []
    for k in range(16):
        a = k * math.pi / 8
        r = 7 if k % 2 == 0 else 5
        pts.append((c + r * math.cos(a), c + r * math.sin(a)))
    d.polygon(pts, fill=GOLD_D, outline=INK)
    d.ellipse((5, 5, 10, 10), fill=PAPER, outline=INK)


def pickaxe(d):
    d.arc((1, 1, 14, 12), 200, 340, fill=INK, width=3)       # головка кирки
    d.line((7, 4, 7, 15), fill=GOLD_D, width=2)               # рукоять
    d.line((8, 4, 8, 15), fill=INK)


def path(d):
    for k, (x, y) in enumerate([(2, 13), (5, 10), (8, 8), (11, 5), (13, 2)]):
        d.rectangle((x - 1, y - 1, x + 1, y + 1), fill=RED if k in (0, 4) else INK)


def pin_of(color):
    def f(d):
        d.ellipse((4, 1, 11, 8), fill=color, outline=INK)
        d.polygon([(5, 7), (10, 7), (7, 14)], fill=color, outline=INK)
        d.point((7, 4), fill=PAPER)
    return f


def house(d):
    d.polygon([(1, 8), (7, 2), (14, 8)], fill=RED, outline=INK)
    d.rectangle((3, 8, 12, 14), fill=GOLD, outline=INK)
    d.rectangle((6, 10, 8, 14), fill=INK)


def flag(d):
    d.line((3, 1, 3, 15), fill=INK, width=2)
    d.polygon([(4, 2), (13, 4), (4, 8)], fill=RED, outline=INK)


def star(d):
    pts = []
    for k in range(10):
        a = -math.pi / 2 + k * math.pi / 5
        r = 7 if k % 2 == 0 else 3
        pts.append((7.5 + r * math.cos(a), 7.5 + r * math.sin(a)))
    d.polygon(pts, fill=GOLD, outline=INK)


def cave(d):
    d.pieslice((1, 3, 14, 20), 180, 360, fill=(110, 104, 96, 255), outline=INK)
    d.pieslice((5, 8, 10, 18), 180, 360, fill=INK)


def tree(d):
    d.ellipse((2, 1, 13, 10), fill=(70, 120, 50, 255), outline=INK)
    d.rectangle((6, 9, 8, 15), fill=GOLD_D, outline=INK)


def skull(d):
    d.ellipse((2, 1, 13, 11), fill=PAPER, outline=INK)
    d.rectangle((5, 10, 10, 14), fill=PAPER, outline=INK)
    d.rectangle((4, 5, 6, 7), fill=INK)
    d.rectangle((9, 5, 11, 7), fill=INK)


def boat(d):
    d.polygon([(1, 10), (14, 10), (11, 14), (4, 14)], fill=GOLD_D, outline=INK)
    d.line((7, 2, 7, 10), fill=INK)
    d.polygon([(8, 2), (13, 8), (8, 8)], fill=PAPER, outline=INK)


def chest(d):
    d.rectangle((1, 4, 14, 14), fill=GOLD_D, outline=INK)
    d.line((1, 8, 14, 8), fill=INK)
    d.rectangle((6, 7, 9, 10), fill=GOLD, outline=INK)


def listing(d):
    d.rectangle((1, 1, 14, 14), fill=PAPER, outline=INK)
    for y in (4, 7, 10):
        d.point((3, y), fill=RED)
        d.line((5, y, 12, y), fill=INK)


def eraser(d):
    d.polygon([(1, 10), (8, 3), (14, 9), (7, 15)], fill=(214, 140, 150, 255), outline=INK)
    d.line((4, 7, 10, 13), fill=INK)


def mobs(d):
    d.rectangle((2, 2, 13, 13), fill=(96, 150, 70, 255), outline=INK)
    d.rectangle((4, 5, 6, 7), fill=INK)
    d.rectangle((9, 5, 11, 7), fill=INK)
    d.rectangle((6, 8, 9, 10), fill=INK)
    d.rectangle((5, 10, 6, 12), fill=INK)
    d.rectangle((9, 10, 10, 12), fill=INK)


def compass():
    s = 256  # рисуем крупно и уменьшаем — тушь без лесенки
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = s / 2
    d.ellipse((c - 92, c - 92, c + 92, c + 92), outline=INK, width=6)
    d.ellipse((c - 80, c - 80, c + 80, c + 80), outline=INK, width=3)
    for k in range(32):
        a = k * math.pi / 16
        l = 14 if k % 4 == 0 else 7
        d.line((c + 80 * math.sin(a), c - 80 * math.cos(a), c + (80 - l) * math.sin(a), c - (80 - l) * math.cos(a)),
               fill=INK, width=3)

    def ray(a, length, width, c1, c2):
        sx, sy = math.sin(a), -math.cos(a)
        px, py = -sy, sx
        tip = (c + sx * length, c + sy * length)
        d.polygon([(c, c), tip, (c + px * width, c + py * width)], fill=c1)
        d.polygon([(c, c), tip, (c - px * width, c - py * width)], fill=c2)

    for k in range(1, 8, 2):
        ray(k * math.pi / 4, 70, 16, GOLD, GOLD_D)
    for k in range(0, 8, 2):
        north = k == 0
        ray(k * math.pi / 4, 118 if north else 104, 22, RED if north else (90, 66, 38, 255),
            (122, 36, 20, 255) if north else (47, 34, 20, 255))
    d.ellipse((c - 9, c - 9, c + 9, c + 9), fill=GOLD, outline=INK, width=3)
    im.resize((64, 64), Image.LANCZOS).save(os.path.join(OUT, "compass.png"))


def main():
    os.makedirs(OUT, exist_ok=True)
    icon("zoom_in", lambda d: magnifier(d, True))
    icon("zoom_out", lambda d: magnifier(d, False))
    icon("center", target)
    icon("close", close)
    icon("marker", pin)
    icon("ruler", ruler)
    icon("pen", pen)
    icon("eye", eye)
    icon("globe", globe)
    icon("gear", gear)
    icon("pickaxe", pickaxe)
    icon("path", path)
    icon("list", listing)
    icon("mobs", mobs)
    icon("eraser", eraser)
    for name, col in (("red", RED), ("blue", BLUE), ("green", (70, 120, 50, 255)), ("gold", GOLD)):
        icon("mk_pin_" + name, pin_of(col))
    icon("mk_house", house)
    icon("mk_flag", flag)
    icon("mk_star", star)
    icon("mk_cave", cave)
    icon("mk_tree", tree)
    icon("mk_skull", skull)
    icon("mk_boat", boat)
    icon("mk_chest", chest)
    compass()
    print("ok", OUT)


if __name__ == "__main__":
    main()
