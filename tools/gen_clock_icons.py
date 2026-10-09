"""Иконки таблички под миникартой (16×16, пиксель-арт в стиле значков мода): координаты, день, ночь, часы,
сезоны TFC (весна, лето, осень, зима) и листок календаря (без TFC).

python tools/gen_clock_icons.py  → assets/pergament/textures/gui/icon_ci_<имя>.png
"""
import math
import os

from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "pergament", "textures", "gui")
INK = (58, 40, 20, 255)
RED = (142, 42, 28, 255)


def save(name, im):
    im.save(os.path.join(OUT, "icon_ci_" + name + ".png"))


def new():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    return im, ImageDraw.Draw(im)


def coords():                      # роза-прицел: где я
    im, d = new()
    d.ellipse((2, 2, 13, 13), outline=INK)
    d.polygon([(8, 1), (9, 7), (8, 8), (7, 7)], fill=RED)
    d.polygon([(8, 15), (9, 9), (8, 8), (7, 9)], fill=INK)
    d.polygon([(1, 8), (7, 7), (8, 8), (7, 9)], fill=INK)
    d.polygon([(15, 8), (9, 7), (8, 8), (9, 9)], fill=INK)
    return im


def sun():                         # день
    im, d = new()
    core, edge = (250, 200, 60, 255), (196, 120, 20, 255)
    for k in range(8):
        a = k * math.pi / 4
        x0, y0 = 8 + math.cos(a) * 5.2, 8 + math.sin(a) * 5.2
        x1, y1 = 8 + math.cos(a) * 7.4, 8 + math.sin(a) * 7.4
        d.line((x0, y0, x1, y1), fill=edge, width=1)
    d.ellipse((4, 4, 11, 11), fill=core, outline=edge)
    d.point((6, 6), fill=(255, 240, 170, 255))
    return im


def moon():                        # ночь
    im, d = new()
    d.ellipse((2, 2, 13, 13), fill=(222, 226, 240, 255), outline=(70, 86, 140, 255))
    d.ellipse((6, 0, 16, 11), fill=(0, 0, 0, 0))
    d.arc((6, 0, 16, 11), 90, 210, fill=(70, 86, 140, 255))
    for x, y in ((12, 11), (14, 6), (11, 14)):
        d.point((x, y), fill=(250, 230, 150, 255))
    d.point((5, 8), fill=(190, 196, 214, 255))
    return im


def clock():                       # настоящее время
    im, d = new()
    d.ellipse((1, 1, 14, 14), fill=(246, 236, 206, 255), outline=INK)
    for k in range(4):
        a = k * math.pi / 2
        d.point((round(7.5 + math.cos(a) * 5), round(7.5 + math.sin(a) * 5)), fill=INK)
    d.line((8, 8, 8, 4), fill=INK)
    d.line((8, 8, 11, 9), fill=RED)
    return im


def spring():                      # росток с цветком
    im, d = new()
    g, gd = (96, 168, 64, 255), (52, 104, 38, 255)
    d.line((8, 15, 8, 7), fill=gd)
    d.polygon([(8, 11), (3, 8), (4, 12)], fill=g, outline=gd)
    d.polygon([(8, 10), (13, 7), (12, 11)], fill=g, outline=gd)
    pink, pinkd = (240, 150, 190, 255), (180, 80, 120, 255)
    for dx, dy in ((0, -3), (-3, 0), (3, 0), (0, 3)):
        d.ellipse((8 + dx - 2, 4 + dy - 2 + 1, 8 + dx + 1, 4 + dy + 1 + 1), fill=pink, outline=pinkd)
    d.point((8, 5), fill=(250, 220, 80, 255))
    return im


def summer():                      # колос пшеницы
    im, d = new()
    stem, grain, gd = (150, 120, 40, 255), (232, 190, 70, 255), (170, 120, 30, 255)
    d.line((8, 15, 8, 3), fill=stem)
    for y in range(3, 12, 2):
        d.ellipse((5, y, 8, y + 2), fill=grain, outline=gd)
        d.ellipse((8, y + 1, 11, y + 3), fill=grain, outline=gd)
    d.line((8, 3, 8, 0), fill=gd)
    return im


def autumn():                      # кленовый лист
    im, d = new()
    leaf, dark = (220, 110, 40, 255), (150, 60, 20, 255)
    pts = [(8, 1), (10, 5), (14, 4), (12, 8), (15, 10), (10, 11), (9, 14), (8, 12),
           (7, 14), (6, 11), (1, 10), (4, 8), (2, 4), (6, 5)]
    d.polygon(pts, fill=leaf, outline=dark)
    d.line((8, 3, 8, 15), fill=dark)
    d.line((8, 8, 12, 6), fill=dark)
    d.line((8, 8, 4, 6), fill=dark)
    return im


def winter():                      # снежинка
    im, d = new()
    ice, dark = (200, 228, 250, 255), (70, 120, 170, 255)
    for k in range(3):
        a = k * math.pi / 3
        x0, y0 = 8 + math.cos(a) * 7, 8 + math.sin(a) * 7
        x1, y1 = 8 - math.cos(a) * 7, 8 - math.sin(a) * 7
        d.line((x0, y0, x1, y1), fill=dark, width=1)
    for k in range(6):
        a = k * math.pi / 3
        bx, by = 8 + math.cos(a) * 4.5, 8 + math.sin(a) * 4.5
        for s in (-1, 1):
            b = a + s * 0.9
            d.line((bx, by, bx + math.cos(b) * 2.2, by + math.sin(b) * 2.2), fill=dark)
    d.ellipse((6, 6, 10, 10), fill=ice, outline=dark)
    return im


def calendar():                    # листок календаря (без TFC)
    im, d = new()
    d.rectangle((2, 3, 13, 14), fill=(246, 236, 206, 255), outline=INK)
    d.rectangle((2, 3, 13, 6), fill=RED, outline=INK)
    d.line((5, 1, 5, 4), fill=INK)
    d.line((10, 1, 10, 4), fill=INK)
    for y in (9, 12):
        for x in (4, 7, 10):
            d.point((x, y), fill=INK)
    return im


for name, fn in (("coords", coords), ("day", sun), ("night", moon), ("clock", clock), ("spring", spring),
                 ("summer", summer), ("autumn", autumn), ("winter", winter), ("calendar", calendar)):
    save(name, fn())
print("ok")
