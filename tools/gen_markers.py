"""Значки меток «Пергамента» (свои, пиксель-арт 16×16): рисуем формы цветом, потом обработка —
объём (светлый верх-лево, тёмный низ-право) и контур тушью в 1 пиксель. Плюс список групп и русских названий
для мода: src/main/resources/assets/pergament/marker_icons.json.

Запуск: python tools/gen_markers.py
"""
import json
import math
import os
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "pergament")
OUT = os.path.join(ROOT, "textures", "gui")

INK = (40, 28, 14, 255)
RED, DRED = (196, 52, 40, 255), (140, 32, 24, 255)
BLUE = (62, 110, 190, 255)
GREEN, DGREEN = (84, 150, 58, 255), (52, 104, 38, 255)
YELLOW = (232, 190, 52, 255)
PURPLE = (140, 72, 190, 255)
WHITE = (238, 234, 222, 255)
GRAY, DGRAY = (150, 146, 140, 255), (98, 94, 90, 255)
BROWN, DBROWN = (150, 102, 58, 255), (104, 68, 36, 255)
WOOD = (176, 128, 72, 255)
STONE = (130, 128, 124, 255)
WATER = (70, 130, 200, 255)
LAVA = (236, 110, 30, 255)
SAND = (222, 204, 150, 255)
SNOW = (240, 244, 248, 255)
GOLD = (240, 196, 60, 255)
IRON = (210, 206, 200, 255)
DIAMOND = (90, 220, 216, 255)
COAL = (54, 52, 50, 255)
EMERALD = (60, 200, 110, 255)
FIRE = (250, 160, 40, 255)


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + (255,)


def finish(im):
    """Объём и контур: светлее у верхнего/левого края формы, темнее у нижнего/правого, тушь вокруг."""
    px = im.load()
    w, h = im.size
    a = [[px[x, y][3] > 0 for y in range(h)] for x in range(w)]
    out = im.copy()
    po = out.load()
    for x in range(w):
        for y in range(h):
            if not a[x][y]:
                continue
            c = px[x, y]
            up = y == 0 or not a[x][y - 1]
            left = x == 0 or not a[x - 1][y]
            down = y == h - 1 or not a[x][y + 1]
            right = x == w - 1 or not a[x + 1][y]
            if (down or right) and not (up or left):
                po[x, y] = shade(c, 0.72)
            elif (up or left) and not (down or right):
                po[x, y] = shade(c, 1.22)
    for x in range(w):
        for y in range(h):
            if a[x][y]:
                continue
            if any(0 <= x + dx < w and 0 <= y + dy < h and a[x + dx][y + dy] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                po[x, y] = INK
    return out


ICONS = []          # (id, ru, group)
# ключ группы для перевода (assets/pergament/lang: pergament.icon_group.<ключ>)
GKEY = {"Основные": "basic", "Постройки": "buildings", "Природа": "nature", "Ресурсы": "resources",
        "Опасности": "danger", "Транспорт и вещи": "transport"}


HAND = os.path.join(os.path.dirname(__file__), "hand_icons")   # нарисованные руками — поверх сгенерированных


def icon(gid, ru, group, fn, raw=False):
    hand = os.path.join(HAND, gid + ".png")
    if os.path.isfile(hand):                                        # рисунок от руки — как есть, без обводки
        im = Image.open(hand).convert("RGBA")
        if im.size != (16, 16):
            raise SystemExit("hand_icons/%s.png: %sx%s, нужен 16x16" % (gid, im.size[0], im.size[1]))
        # пиксель-арт: пиксель есть или нет — следы ластика (почти прозрачное) убрать, иначе игра их проявит
        px = im.load()
        for y in range(16):
            for x in range(16):
                r, g, b, a = px[x, y]
                px[x, y] = (r, g, b, 255) if a >= 128 else (0, 0, 0, 0)
    else:
        if fn is None:
            raise SystemExit("нет рисунка hand_icons/%s.png" % gid)
        im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        fn(ImageDraw.Draw(im), im)
        if not raw:
            im = finish(im)
    im.save(os.path.join(OUT, "icon_mk_" + gid + ".png"))
    ICONS.append({"id": gid, "ru": ru, "group": group, "gkey": GKEY[group]})


# ---------- формы ----------
def pin(col):
    def f(d, im):
        d.ellipse((4, 1, 11, 8), fill=col)
        d.polygon([(5, 6), (10, 6), (7, 13)], fill=col)
        d.point((6, 3), fill=WHITE)
        d.point((7, 3), fill=WHITE)
        d.point((6, 4), fill=WHITE)
    return f


def flag(d, im):
    d.rectangle((3, 1, 3, 14), fill=DBROWN)
    d.polygon([(4, 2), (13, 4), (4, 7)], fill=RED)


def star(d, im):
    pts = []
    for k in range(10):
        a = -math.pi / 2 + k * math.pi / 5
        r = 6.5 if k % 2 == 0 else 2.8
        pts.append((7.5 + r * math.cos(a), 7.8 + r * math.sin(a)))
    d.polygon(pts, fill=GOLD)


def cross(d, im):
    d.line((3, 3, 12, 12), fill=RED, width=3)
    d.line((12, 3, 3, 12), fill=RED, width=3)


def question(d, im):
    d.ellipse((2, 1, 13, 13), fill=BLUE)
    d.arc((5, 3, 10, 8), 180, 90, fill=WHITE, width=2)
    d.line((8, 8, 8, 9), fill=WHITE, width=2)
    d.point((8, 11), fill=WHITE)


def exclaim(d, im):
    d.polygon([(7.5, 1), (14, 13), (1, 13)], fill=YELLOW)
    d.line((7, 5, 7, 9), fill=INK, width=2)
    d.rectangle((7, 11, 8, 11), fill=INK)


def heart(d, im):
    d.ellipse((1, 2, 8, 9), fill=RED)
    d.ellipse((7, 2, 14, 9), fill=RED)
    d.polygon([(2, 7), (13, 7), (7.5, 13)], fill=RED)


def house(d, im):
    d.polygon([(1, 8), (7.5, 2), (14, 8)], fill=DRED)
    d.rectangle((3, 8, 12, 14), fill=WOOD)
    d.rectangle((6, 10, 8, 14), fill=DBROWN)
    d.rectangle((10, 9, 11, 10), fill=WATER)


def castle(d, im):
    d.rectangle((2, 5, 13, 14), fill=STONE)
    for x in (2, 6, 10):
        d.rectangle((x, 2, x + 1, 5), fill=STONE)
    d.rectangle((13, 2, 13, 5), fill=STONE)
    d.rectangle((6, 9, 9, 14), fill=DBROWN)
    d.line((4, 2, 4, 0), fill=DBROWN)
    d.rectangle((5, 0, 6, 1), fill=RED)


def mine(d, im):
    d.rectangle((1, 5, 14, 14), fill=STONE)
    d.rectangle((4, 7, 11, 14), fill=COAL)
    d.rectangle((3, 6, 12, 6), fill=WOOD)
    d.rectangle((3, 6, 3, 14), fill=WOOD)
    d.rectangle((12, 6, 12, 14), fill=WOOD)


def portal(d, im):
    d.rectangle((2, 1, 13, 14), fill=COAL)
    d.rectangle((4, 3, 11, 12), fill=PURPLE)
    d.point((6, 5), fill=(200, 150, 240, 255))
    d.point((9, 8), fill=(200, 150, 240, 255))


def well(d, im):
    d.rectangle((3, 9, 12, 14), fill=STONE)
    d.rectangle((5, 10, 10, 11), fill=WATER)
    d.rectangle((3, 3, 3, 9), fill=WOOD)
    d.rectangle((12, 3, 12, 9), fill=WOOD)
    d.polygon([(1, 4), (7.5, 0), (14, 4)], fill=DRED)


def bridge(d, im):
    d.rectangle((0, 11, 15, 14), fill=WATER)
    d.rectangle((0, 7, 15, 9), fill=WOOD)
    for x in (2, 7, 12):
        d.rectangle((x, 9, x + 1, 13), fill=DBROWN)
    d.line((0, 5, 15, 5), fill=DBROWN)


def lighthouse(d, im):
    d.polygon([(5, 14), (10, 14), (9, 4), (6, 4)], fill=WHITE)
    d.rectangle((6, 7, 9, 8), fill=RED)
    d.rectangle((6, 11, 9, 12), fill=RED)
    d.rectangle((5, 2, 10, 4), fill=DGRAY)
    d.rectangle((6, 1, 9, 2), fill=GOLD)


def tent(d, im):
    d.polygon([(1, 14), (7.5, 2), (14, 14)], fill=(196, 160, 96, 255))
    d.polygon([(6, 14), (7.5, 9), (9, 14)], fill=DBROWN)


def anvil(d, im):
    d.rectangle((2, 4, 13, 7), fill=DGRAY)
    d.rectangle((5, 7, 10, 11), fill=DGRAY)
    d.rectangle((3, 11, 12, 14), fill=DGRAY)
    d.rectangle((1, 4, 2, 5), fill=DGRAY)


def farm(d, im):
    d.rectangle((1, 6, 14, 14), fill=(120, 80, 44, 255))
    for x in (2, 6, 10):
        d.rectangle((x, 2, x + 2, 7), fill=GREEN)
        d.point((x + 1, 1), fill=YELLOW)


def tree(d, im):
    d.ellipse((2, 1, 13, 10), fill=GREEN)
    d.rectangle((6, 9, 9, 14), fill=DBROWN)


def spruce(d, im):
    d.polygon([(7.5, 0), (13, 7), (2, 7)], fill=DGREEN)
    d.polygon([(7.5, 3), (14, 11), (1, 11)], fill=DGREEN)
    d.rectangle((6, 11, 9, 14), fill=DBROWN)


def mountain(d, im):
    d.polygon([(0, 14), (6, 3), (10, 9), (11, 7), (15, 14)], fill=STONE)
    d.polygon([(4, 6), (6, 3), (8, 6), (7, 7), (6, 6), (5, 7)], fill=SNOW)


def cave(d, im):
    d.pieslice((0, 2, 15, 26), 180, 360, fill=STONE)
    d.pieslice((4, 7, 11, 21), 180, 360, fill=COAL)


def lake(d, im):
    d.ellipse((1, 4, 14, 13), fill=WATER)
    d.line((4, 7, 7, 7), fill=(150, 200, 240, 255))
    d.line((8, 10, 11, 10), fill=(150, 200, 240, 255))


def volcano(d, im):
    d.polygon([(0, 14), (5, 5), (10, 5), (15, 14)], fill=DGRAY)
    d.polygon([(5, 5), (10, 5), (9, 8), (6, 8)], fill=LAVA)
    d.rectangle((7, 1, 8, 4), fill=FIRE)


def flower(d, im):
    d.rectangle((7, 8, 8, 14), fill=DGREEN)
    d.polygon([(8, 11), (12, 9), (11, 12)], fill=GREEN)
    for dx, dy in ((0, -3), (3, 0), (0, 3), (-3, 0)):
        d.ellipse((6 + dx, 4 + dy, 9 + dx, 7 + dy), fill=RED)
    d.ellipse((6, 4, 9, 7), fill=YELLOW)


def mushroom(d, im):
    d.pieslice((1, 2, 14, 14), 180, 360, fill=RED)
    d.rectangle((6, 8, 9, 14), fill=WHITE)
    d.point((4, 5), fill=WHITE)
    d.point((10, 4), fill=WHITE)
    d.point((7, 3), fill=WHITE)


def desert(d, im):
    d.polygon([(0, 14), (5, 8), (9, 11), (12, 9), (15, 14)], fill=SAND)
    d.rectangle((10, 2, 11, 9), fill=GREEN)
    d.rectangle((8, 4, 9, 5), fill=GREEN)
    d.rectangle((12, 5, 13, 6), fill=GREEN)


def snowflake(d, im):
    for a in range(3):
        ang = a * math.pi / 3
        dx, dy = 6 * math.cos(ang), 6 * math.sin(ang)
        d.line((7.5 - dx, 7.5 - dy, 7.5 + dx, 7.5 + dy), fill=(150, 200, 240, 255), width=2)


def pickaxe(d, im):
    d.arc((1, 1, 14, 12), 200, 340, fill=IRON, width=3)
    d.rectangle((7, 4, 8, 15), fill=WOOD)


def ore(col):
    def f(d, im):
        d.rectangle((1, 1, 14, 14), fill=STONE)
        for x, y in ((3, 3), (9, 4), (5, 9), (11, 10), (4, 12)):
            d.rectangle((x, y, x + 2, y + 1), fill=col)
    return f


def gem(col):
    def f(d, im):
        d.polygon([(4, 3), (11, 3), (14, 6), (7.5, 14), (1, 6)], fill=col)
        d.line((1, 6, 14, 6), fill=shade(col, 0.75))
        d.line((4, 3, 7, 6), fill=shade(col, 1.25))
    return f


def ingot(col):
    def f(d, im):
        d.polygon([(1, 11), (4, 6), (14, 6), (11, 11)], fill=col)
        d.polygon([(1, 11), (11, 11), (11, 13), (1, 13)], fill=shade(col, 0.8))
        d.polygon([(11, 11), (14, 6), (14, 8), (11, 13)], fill=shade(col, 0.65))
    return f


def coal(d, im):
    d.polygon([(3, 4), (9, 2), (13, 6), (12, 12), (6, 14), (2, 10)], fill=COAL)
    d.point((6, 6), fill=DGRAY)
    d.point((9, 9), fill=DGRAY)


def crystal(d, im):
    d.polygon([(7, 1), (10, 6), (9, 14), (5, 14), (4, 6)], fill=PURPLE)
    d.polygon([(11, 6), (14, 9), (13, 14), (10, 14)], fill=(170, 110, 220, 255))


def skull(d, im):
    d.ellipse((2, 1, 13, 11), fill=WHITE)
    d.rectangle((5, 10, 10, 14), fill=WHITE)
    d.rectangle((4, 5, 6, 7), fill=INK)
    d.rectangle((9, 5, 11, 7), fill=INK)
    d.point((7, 9), fill=INK)
    d.point((8, 9), fill=INK)


def sword(d, im):
    d.line((3, 12, 13, 2), fill=IRON, width=2)
    d.line((2, 9, 6, 13), fill=DBROWN, width=2)
    d.line((1, 14, 3, 12), fill=DBROWN, width=2)


def monster(d, im):
    d.rectangle((2, 1, 13, 14), fill=(100, 170, 80, 255))
    d.rectangle((4, 4, 6, 6), fill=COAL)
    d.rectangle((9, 4, 11, 6), fill=COAL)
    d.rectangle((6, 7, 9, 10), fill=COAL)
    d.rectangle((5, 9, 6, 12), fill=COAL)
    d.rectangle((9, 9, 10, 12), fill=COAL)


def spider(d, im):
    d.ellipse((5, 4, 10, 11), fill=COAL)
    for y in (5, 7, 9):
        d.line((5, y, 1, y - 2), fill=COAL)
        d.line((10, y, 14, y - 2), fill=COAL)
    d.point((6, 6), fill=RED)
    d.point((9, 6), fill=RED)


def fire(d, im):
    d.polygon([(7.5, 0), (13, 8), (12, 13), (3, 13), (2, 8), (5, 5)], fill=FIRE)
    d.polygon([(7.5, 5), (10, 10), (9, 13), (6, 13), (5, 10)], fill=YELLOW)


def lava(d, im):
    d.rectangle((1, 5, 14, 14), fill=LAVA)
    d.line((3, 8, 7, 8), fill=YELLOW)
    d.line((8, 11, 12, 11), fill=YELLOW)
    d.polygon([(4, 5), (6, 2), (8, 5)], fill=FIRE)


def trap(d, im):
    d.polygon([(1, 14), (4, 6), (7, 14)], fill=IRON)
    d.polygon([(5, 14), (8, 3), (11, 14)], fill=IRON)
    d.polygon([(9, 14), (12, 6), (15, 14)], fill=IRON)


def boat(d, im):
    d.polygon([(1, 10), (14, 10), (11, 14), (4, 14)], fill=WOOD)
    d.rectangle((7, 2, 7, 10), fill=DBROWN)
    d.polygon([(8, 2), (13, 8), (8, 8)], fill=WHITE)


def cart(d, im):
    d.rectangle((2, 5, 13, 10), fill=WOOD)
    d.ellipse((2, 9, 6, 13), fill=DBROWN)
    d.ellipse((9, 9, 13, 13), fill=DBROWN)
    d.line((13, 6, 15, 4), fill=DBROWN)


def chest(d, im):
    d.rectangle((1, 4, 14, 14), fill=WOOD)
    d.rectangle((1, 4, 14, 7), fill=DBROWN)
    d.rectangle((6, 6, 9, 9), fill=GOLD)


def book(d, im):
    d.rectangle((2, 2, 13, 13), fill=DRED)
    d.rectangle((4, 2, 13, 11), fill=WHITE)
    d.line((6, 5, 11, 5), fill=GRAY)
    d.line((6, 7, 11, 7), fill=GRAY)
    d.line((6, 9, 10, 9), fill=GRAY)


def torch(d, im):
    d.rectangle((7, 6, 8, 15), fill=WOOD)
    d.ellipse((5, 1, 10, 7), fill=FIRE)
    d.ellipse((6, 3, 9, 6), fill=YELLOW)


def key(d, im):
    d.ellipse((1, 1, 7, 7), fill=GOLD)
    d.ellipse((3, 3, 5, 5), fill=(0, 0, 0, 0))
    d.line((6, 6, 13, 13), fill=GOLD, width=2)
    d.line((10, 12, 12, 10), fill=GOLD, width=2)


def anchor(d, im):
    d.ellipse((6, 0, 9, 3), fill=DGRAY)
    d.rectangle((7, 3, 8, 13), fill=DGRAY)
    d.rectangle((4, 5, 11, 6), fill=DGRAY)
    d.arc((1, 6, 14, 15), 20, 160, fill=DGRAY, width=2)


def fish(d, im):
    d.ellipse((2, 4, 11, 11), fill=(110, 160, 200, 255))
    d.polygon([(10, 7.5), (15, 3), (15, 12)], fill=(110, 160, 200, 255))
    d.point((5, 6), fill=INK)


def bed(d, im):
    d.rectangle((1, 7, 14, 11), fill=RED)
    d.rectangle((1, 6, 5, 9), fill=WHITE)
    d.rectangle((1, 11, 2, 14), fill=WOOD)
    d.rectangle((13, 11, 14, 14), fill=WOOD)


def trade(d, im):
    d.ellipse((1, 3, 10, 12), fill=GOLD)
    d.ellipse((5, 3, 14, 12), fill=GOLD)
    d.line((9, 5, 9, 10), fill=DBROWN)


def main():
    os.makedirs(OUT, exist_ok=True)
    G1, G2, G3, G4, G5, G6 = "Основные", "Постройки", "Природа", "Ресурсы", "Опасности", "Транспорт и вещи"
    for gid, ru, col in (("pin_red", "Красная булавка", RED), ("pin_blue", "Синяя булавка", BLUE),
                         ("pin_green", "Зелёная булавка", GREEN), ("pin_gold", "Жёлтая булавка", YELLOW),
                         ("pin_purple", "Фиолетовая булавка", PURPLE), ("pin_white", "Белая булавка", WHITE)):
        icon(gid, ru, G1, pin(col))
    icon("flag", "Флаг", G1, flag)
    icon("star", "Звезда", G1, star)
    icon("cross", "Крестик", G1, cross)
    icon("question", "Вопрос", G1, question)
    icon("exclaim", "Внимание", G1, exclaim)
    icon("heart", "Сердце", G1, heart)

    icon("house", "Дом", G2, house)
    icon("castle", "Крепость", G2, castle)
    icon("mine", "Шахта", G2, mine)
    icon("portal", "Портал", G2, portal)
    icon("well", "Колодец", G2, well)
    icon("bridge", "Мост", G2, bridge)
    icon("lighthouse", "Маяк", G2, lighthouse)
    icon("tent", "Лагерь", G2, tent)
    icon("anvil", "Кузница", G2, anvil)
    icon("farm", "Ферма", G2, farm)
    icon("bed", "Ночлег", G2, bed)
    icon("trade", "Торговля", G2, trade)

    icon("tree", "Дерево", G3, tree)
    icon("spruce", "Ель", G3, spruce)
    icon("mountain", "Гора", G3, mountain)
    icon("cave", "Пещера", G3, cave)
    icon("lake", "Озеро", G3, lake)
    icon("volcano", "Вулкан", G3, volcano)
    icon("flower", "Цветы", G3, flower)
    icon("mushroom", "Грибы", G3, mushroom)
    icon("berries", "Ягоды", G3, None)
    icon("desert", "Пустыня", G3, desert)
    icon("snow", "Снега", G3, snowflake)

    icon("pickaxe", "Копать тут", G4, pickaxe)
    icon("ore_iron", "Железная руда", G4, ore((200, 160, 130, 255)))
    icon("ore_copper", "Медная руда", G4, ore((220, 120, 70, 255)))
    icon("ore_tin", "Оловянная руда", G4, ore((210, 210, 214, 255)))
    icon("ore_gold", "Золотая руда", G4, ore(GOLD))
    icon("ore_redstone", "Редстоун", G4, None)
    icon("ore_blue", "Синяя руда", G4, None)
    icon("ore_pink", "Розовая руда", G4, None)
    icon("ore_darkgreen", "Тёмно-зелёная руда", G4, None)
    icon("diamond", "Алмаз", G4, gem(DIAMOND))
    icon("emerald", "Изумруд", G4, gem(EMERALD))
    icon("ingot_gold", "Слиток золота", G4, ingot(GOLD))
    icon("ingot_iron", "Слиток железа", G4, ingot(IRON))
    icon("coal", "Уголь", G4, coal)
    icon("crystal", "Кристаллы", G4, crystal)
    icon("clay", "Глина", G4, None)

    icon("skull", "Смерть", G5, skull)
    icon("sword", "Бой", G5, sword)
    icon("monster", "Монстры", G5, monster)
    icon("raiders", "Разбойники", G5, None)
    icon("spider", "Пауки", G5, spider)
    icon("fire", "Огонь", G5, fire)
    icon("lava", "Лава", G5, lava)
    icon("trap", "Ловушка", G5, trap)

    icon("boat", "Лодка", G6, boat)
    icon("cart", "Повозка", G6, cart)
    icon("chest", "Сундук", G6, chest)
    icon("book", "Записки", G6, book)
    icon("torch", "Факел", G6, torch)
    icon("key", "Ключ", G6, key)
    icon("anchor", "Пристань", G6, anchor)
    icon("fish", "Рыбалка", G6, fish)

    with open(os.path.join(ROOT, "marker_icons.json"), "w", encoding="utf-8") as f:
        json.dump(ICONS, f, ensure_ascii=False, indent=1)
    print("ok", len(ICONS))


if __name__ == "__main__":
    main()
