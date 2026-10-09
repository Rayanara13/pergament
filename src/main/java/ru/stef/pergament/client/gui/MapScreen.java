package ru.stef.pergament.client.gui;

import ru.stef.pergament.client.T;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;
import ru.stef.pergament.Pergament;
import ru.stef.pergament.client.ClientSetup;
import ru.stef.pergament.client.map.Drawings;
import ru.stef.pergament.client.map.LocalMap;
import ru.stef.pergament.client.map.Markers;
import ru.stef.pergament.client.map.MinerPlan;
import ru.stef.pergament.client.map.Veins;
import ru.stef.pergament.client.route.RoutePlan;
import ru.stef.pergament.store.RegionData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Большая карта на весь экран: разведанное игроком на пергаменте, пан мышью (правой — всегда),
 * зум колесом к курсору, пробел — к игроку. Слева панель инструментов; что делает клик — решает
 * текущий инструмент ({@link Tool}). Таблички: мир·измерение сверху, координаты и масштаб внизу,
 * роза ветров; панели: список меток слева, метка справа. Вид живёт между открытиями.
 */
public class MapScreen extends Screen {
    public enum Tool { NONE, MARKER, ROUTE, MINER, RULER, PEN }

    private static final ResourceLocation COMPASS = new ResourceLocation(Pergament.MOD_ID, "textures/gui/compass.png");
    private static final int[] SCALES = {1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000};
    private static final float MIN_ZOOM = 0.125f, MAX_ZOOM = 8f;
    private static final int PANEL_W = 168, LIST_W = 156, ROW = 18;

    private static double cx, cz;
    private static float zoom = 1f;          // пикселей GUI на блок
    private static boolean placed;
    private static String placedFor;          // профиль|измерение, для которого запомнен вид
    static boolean showVeins = true;
    public static Tool tool = Tool.NONE;
    static Markers.Marker editing;            // открытая в панели метка (копия)
    static boolean listOpen;
    private static String listQuery = "";
    private static double listScroll;
    private static final List<int[]> ruler = new ArrayList<>();
    private static int penColor = 1;
    private static boolean eraser;
    private static boolean toSettings;        // уходим в настройки — глобус не сбрасывать
    private static double iconScroll;         // прокрутка окна выбора значка
    private static boolean picker;            // открыто окно выбора значка
    private static final int CELL = 25;

    private Drawings.Stroke stroke;           // штрих, который рисуется сейчас
    private double pressX, pressY;
    private boolean dragged;
    private int[] testMouse;                  // самотест: «мышь» без мыши
    private int x0, y0, x1, y1;
    private int toolX, toolY, toolRight = 28;      // раскладка панели инструментов (переносится во 2-й столбец)
    private int LIST_X = 28;

    public MapScreen() {
        super(Component.translatable("pergament.screen.title"));
    }

    // ---------- раскладка ----------

    @Override
    protected void init() {
        x0 = 0; y0 = 0; x1 = width; y1 = height;
        String here = LocalMap.get().profile() + "|" + LocalMap.get().shownDimension();
        if (!here.equals(placedFor)) {                     // другой мир или измерение — вид от игрока, линейка не отсюда
            placed = false;
            ruler.clear();
        }
        if (!placed && minecraft.player != null) centerOnPlayer();
        placedFor = here;

        boolean gt = Veins.source() != null;
        toolX = 4;
        toolY = 4;
        toolRight = 4 + IconButton.SIZE;
        place(new IconButton(0, 0, "zoom_in", T.c("map.zoom_in"), () -> zoomAround(1)));
        place(new IconButton(0, 0, "zoom_out", T.c("map.zoom_out"), () -> zoomAround(-1)));
        place(new IconButton(0, 0, "center", T.c("map.center"), () -> {
            if (minecraft.player != null) centerOnPlayer();
        }));
        place(new IconButton(0, 0, "list", T.c("map.list"),
                () -> { listOpen = !listOpen; rebuildWidgets(); }).on(() -> listOpen));
        toolButton("marker", T.t("tool.marker"), Tool.MARKER);
        toolButton("path", T.t("tool.path"), Tool.ROUTE);
        toolButton("ruler", T.t("tool.ruler"), Tool.RULER);
        toolButton("pen", T.t("tool.pen"), Tool.PEN);
        if (gt) {                                           // жилы и шахтёр — только при GTCEu
            toolButton("pickaxe", T.t("tool.miner"), Tool.MINER);
            place(new IconButton(0, 0, "eye", T.c("map.veins"),
                    () -> showVeins = !showVeins).on(() -> showVeins));
        }
        if (LocalMap.get().knownDimensions().size() > 1 || LocalMap.get().viewingOther()) {
            place(new IconButton(0, 0, "globe", T.c("map.globe"), this::nextDimension)
                    .on(() -> LocalMap.get().viewingOther()));
        }
        place(new IconButton(0, 0, "mobs", T.c("map.mobs"), () -> {
            ru.stef.pergament.client.PergamentConfig.SHOW_MOBS.set(!ru.stef.pergament.client.PergamentConfig.SHOW_MOBS.get());
            ru.stef.pergament.client.PergamentConfig.SHOW_MOBS.save();
        }).on(() -> ru.stef.pergament.client.PergamentConfig.SHOW_MOBS.get()));
        place(new IconButton(0, 0, "gear", T.c("settings.title"),
                () -> { toSettings = true; minecraft.setScreen(new SettingsScreen(this)); }));
        place(new IconButton(0, 0, "close", T.c("map.close"), this::onClose));
        LIST_X = toolRight + 4;

        int subY = 3 + font.lineHeight + 11 + font.lineHeight + 9;
        switch (tool) {
            case ROUTE -> {
                RoutePlan plan = RoutePlan.get();
                addRenderableWidget(new PaperButton(width / 2 - 52, subY, 50, T.c("button.undo"), () -> false, plan::undo));
                addRenderableWidget(new PaperButton(width / 2 + 2, subY, 50, T.c("button.reset"), () -> false, plan::clear));
            }
            case RULER -> {
                addRenderableWidget(new PaperButton(width / 2 - 52, subY, 50, T.c("button.undo"), () -> false,
                        () -> { if (!ruler.isEmpty()) ruler.remove(ruler.size() - 1); }));
                addRenderableWidget(new PaperButton(width / 2 + 2, subY, 50, T.c("button.reset"), () -> false, ruler::clear));
            }
            case PEN -> {
                int n = Drawings.COLORS.length, sw = 16, total = n * (sw + 2) + 2 + IconButton.SIZE + 4 + 74;
                int x = (width - total) / 2;
                for (int k = 0; k < n; k++) {
                    int idx = k;
                    addRenderableWidget(new SwatchButton(x + k * (sw + 2), subY + 2, sw, Drawings.COLORS[k],
                            () -> penColor == idx && !eraser, () -> { penColor = idx; eraser = false; }));
                }
                int ex = x + n * (sw + 2) + 2;
                addRenderableWidget(new IconButton(ex, subY, "eraser", T.c("pen.eraser"),
                        () -> eraser = !eraser).on(() -> eraser));
                addRenderableWidget(new PaperButton(ex + IconButton.SIZE + 4, subY + 3, 74, T.c("pen.erase_all"),
                        () -> false, () -> {
                            var d = LocalMap.get().drawings();
                            if (d != null && hasShiftDown()) d.clear();
                        }));
            }
            default -> { }
        }
        if (listOpen) initList();
        if (editing != null) initPanel();
    }

    private void toolButton(String icon, String tip, Tool t) {
        place(new IconButton(0, 0, icon, Component.literal(tip), () -> {
            tool = tool == t ? Tool.NONE : t;
            rebuildWidgets();
        }).on(() -> tool == t));
    }

    /** Кнопку — в столбец панели; не влезает по высоте (над табличкой координат) — в следующий столбец. */
    private void place(IconButton b) {
        int step = IconButton.SIZE + 2, limit = height - 30;
        if (toolY + IconButton.SIZE > limit) {
            toolX += step;
            toolY = 4;
        }
        b.setX(toolX);
        b.setY(toolY);
        toolRight = Math.max(toolRight, toolX + IconButton.SIZE);
        toolY += step;
        addRenderableWidget(b);
    }

    private void centerOnPlayer() {
        if (LocalMap.get().viewingOther()) LocalMap.get().view(null);   // «к себе» — значит, и в своё измерение
        cx = minecraft.player.getX();
        cz = minecraft.player.getZ();
        placed = true;
        placedFor = LocalMap.get().profile() + "|" + LocalMap.get().shownDimension();
    }

    /** Глобус: следующее разведанное измерение; центр пересчитываем (Незер — восьмая часть верхнего мира). */
    private void nextDimension() {
        LocalMap map = LocalMap.get();
        List<String> dims = map.knownDimensions();
        if (dims.isEmpty()) return;
        String cur = map.shownDimension();
        String next = dims.get((Math.max(0, dims.indexOf(cur)) + 1) % dims.size());
        double k = scaleOf(cur) / scaleOf(next);
        cx *= k;
        cz *= k;
        map.view(next);
        placedFor = map.profile() + "|" + map.shownDimension();
        ruler.clear();
        rebuildWidgets();
    }

    private static double scaleOf(String dim) {
        return "minecraft:the_nether".equals(dim) ? 8 : 1;
    }

    public static void setZoom(float z) {
        zoom = Mth.clamp(z, MIN_ZOOM, MAX_ZOOM);
    }

    public static float zoom() {
        return zoom;
    }

    // ---- вид: мир <-> экран (GUI-координаты)
    double sx(double wx) { return (x0 + x1) / 2.0 + (wx - cx) * zoom; }
    double sy(double wz) { return (y0 + y1) / 2.0 + (wz - cz) * zoom; }
    double wx(double sx) { return cx + (sx - (x0 + x1) / 2.0) / zoom; }
    double wz(double sy) { return cz + (sy - (y0 + y1) / 2.0) / zoom; }
    double zoomGui() { return zoom; }

    /** Самотест: открыть метку в панели. */
    public void debugOpenMarker(Markers.Marker m) {
        openMarker(m);
    }

    /** Самотест: центр карты в точке. */
    public static void debugCenter(double x, double z) {
        cx = x;
        cz = z;
        placed = true;
        placedFor = LocalMap.get().profile() + "|" + LocalMap.get().shownDimension();
    }

    public static void debugPicker(boolean open) {
        picker = open;
    }

    /** Самотест: нажать глобус. */
    public void debugNextDimension() {
        nextDimension();
    }

    /** Самотест: навести «мышь» на точку мира. */
    public void debugHover(double worldX, double worldZ) {
        testMouse = new int[]{(int) Math.round(sx(worldX)), (int) Math.round(sy(worldZ))};
    }

    /** Витрина: «мышь» в точке экрана (за краем — никаких подсказок под курсором). */
    public void debugMouse(int x, int y) {
        testMouse = new int[]{x, y};
    }

    public static long rulerTotalForTest() {
        return rulerTotal(ruler);
    }

    public static void listOpenForTest(boolean open) {
        listOpen = open;
    }

    /** Самотест: линейка и штрих пером без рук. */
    public static void debugRuler(int[]... pts) {
        ruler.clear();
        for (int[] p : pts) ruler.add(p);
    }

    // ---------- отрисовка ----------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (testMouse != null) { mouseX = testMouse[0]; mouseY = testMouse[1]; }
        Paper.fill(g, x0, y0, x1, y1);                       // неразведанное — чистая бумага
        drawRegions(g);
        var drawings = LocalMap.get().drawings();
        if (drawings != null) for (var s : drawings.all()) drawStroke(g, s);
        if (stroke != null) drawStroke(g, stroke);
        var veins = showVeins ? VeinOverlay.visible(this) : List.<Veins.Vein>of();
        VeinOverlay.draw(g, this, veins);
        MinerPlan plan = Veins.source() != null && tool == Tool.MINER ? LocalMap.get().miner() : null;
        if (plan != null) MinerOverlay.draw(g, this, plan);              // сетка видна, только пока кирка включена
        var marks = LocalMap.get().markers();
        List<Markers.Marker> markList = marks == null ? List.of() : marks.all();
        RouteDraw.draw(g, font, RoutePlan.get(), this::sx, this::sy, zoom, true);
        drawRuler(g);
        MarkerOverlay.draw(g, font, this, markList, editing == null ? null : editing.id);
        EntityMarks.drawMobs(g, this::sx, this::sy, partial, zoom >= 2 ? 10 : 8, 0, 0, width, height);
        EntityMarks.drawPlayers(g, font, this::sx, this::sy, partial, zoom >= 2 ? 14 : 12, true);
        g.renderOutline(0, 0, width, height, Paper.INK);
        drawTitle(g);
        drawStatus(g, mouseX, mouseY);
        g.blit(COMPASS, width - 54, height - 54, 0, 0, 48, 48, 48, 48);
        if (listOpen) drawList(g, markList, mouseX, mouseY);
        if (editing != null) drawPanel(g);
        super.render(g, mouseX, mouseY, partial);

        switch (tool) {
            case MINER -> drawHint(g, plan == null || !plan.hasOrigin() ? T.t("hint.miner_place")
                    : T.t("hint.miner"));
            case MARKER -> drawHint(g, T.t("hint.marker"));
            case ROUTE -> drawHint(g, routeHint(RoutePlan.get()));
            case RULER -> drawHint(g, rulerHint());
            case PEN -> drawHint(g, eraser ? T.t("hint.eraser") : T.t("hint.pen"));
            default -> { }
        }
        if (picker) {
            drawPicker(g, mouseX, mouseY);
            return;
        }
        boolean free = overMap(mouseX, mouseY) && getChildAt(mouseX, mouseY).isEmpty();
        var mk = free ? MarkerOverlay.hovered(this, markList, mouseX, mouseY) : null;
        if (mk != null) {
            MarkerOverlay.drawTip(g, font, this, mk, mouseX, mouseY);
            return;
        }
        int[] cell = free && tool == Tool.MINER && plan != null ? MinerOverlay.hovered(this, plan, mouseX, mouseY) : null;
        var hv = free && cell == null && tool != Tool.PEN ? VeinOverlay.hovered(this, veins, mouseX, mouseY) : null;
        if (cell != null) MinerOverlay.drawTip(g, font, this, plan, cell, mouseX, mouseY);
        else if (hv != null) VeinOverlay.drawTip(g, font, this, hv, mouseX, mouseY);
    }

    private void drawRegions(GuiGraphics g) {
        LocalMap map = LocalMap.get();
        int t = RegionData.SIZE;
        int rx0 = Math.floorDiv((int) Math.floor(wx(x0)), t), rx1 = Math.floorDiv((int) Math.floor(wx(x1)), t);
        int rz0 = Math.floorDiv((int) Math.floor(wz(y0)), t), rz1 = Math.floorDiv((int) Math.floor(wz(y1)), t);
        PoseStack pose = g.pose();
        for (int rz = rz0; rz <= rz1; rz++) {
            for (int rx = rx0; rx <= rx1; rx++) {
                ResourceLocation tex = map.texture(rx, rz);
                if (tex == null) continue;
                pose.pushPose();
                pose.translate(sx((double) rx * t), sy((double) rz * t), 0);
                pose.scale(zoom, zoom, 1);
                g.blit(tex, 0, 0, 0, 0, t, t, t, t);
                pose.popPose();
            }
        }
    }

    /** Штрих пером: толщина в пикселях экрана (не растёт с зумом — это пометка, а не стена). */
    private void drawStroke(GuiGraphics g, Drawings.Stroke s) {
        int half = Math.max(1, s.width / 2 + 1);
        int px = Integer.MIN_VALUE, py = 0;
        for (int[] p : s.pts) {
            int x = (int) Math.round(sx(p[0] + 0.5)), y = (int) Math.round(sy(p[1] + 0.5));
            if (px != Integer.MIN_VALUE) line(g, px, py, x, y, half, s.color);
            px = x;
            py = y;
        }
    }

    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int half, int color) {
        int n = Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
        if (n > 4000) return;
        for (int k = 0; k <= n; k++) {
            int x = x0 + (x1 - x0) * k / n, y = y0 + (y1 - y0) * k / n;
            g.fill(x - half + 1, y - half + 1, x + half, y + half, color);
        }
    }

    /** Линейка: пунктир тушью, на каждом отрезке ярлычок с длиной в блоках. */
    private void drawRuler(GuiGraphics g) {
        for (int k = 0; k < ruler.size(); k++) {
            int[] p = ruler.get(k);
            int x = (int) Math.round(sx(p[0] + 0.5)), y = (int) Math.round(sy(p[1] + 0.5));
            if (k > 0) {
                int[] q = ruler.get(k - 1);
                int qx = (int) Math.round(sx(q[0] + 0.5)), qy = (int) Math.round(sy(q[1] + 0.5));
                int n = Math.max(1, Math.max(Math.abs(x - qx), Math.abs(y - qy)));
                for (int t = 0; t <= n; t++) {
                    if ((t / 4) % 2 == 1) continue;           // пунктир
                    int lx = qx + (x - qx) * t / n, ly = qy + (y - qy) * t / n;
                    g.fill(lx, ly, lx + 2, ly + 2, Paper.INK);
                }
                String len = T.t("unit.blocks", Math.round(Math.hypot(p[0] - q[0], p[1] - q[1])));
                int mx = (x + qx) / 2, my = (y + qy) / 2, w = font.width(len) + 6;
                g.fill(mx - w / 2, my - 6, mx + w / 2, my + 6, 0xEEF1E4C0);
                g.renderOutline(mx - w / 2, my - 6, w, 12, Paper.INK);
                g.drawString(font, len, mx - w / 2 + 3, my - 4, Paper.INK, false);
            }
            g.fill(x - 3, y - 3, x + 4, y + 4, Paper.INK);
            g.fill(x - 2, y - 2, x + 3, y + 3, 0xFFF1E4C0);
        }
    }

    static long rulerTotal(List<int[]> pts) {
        double s = 0;
        for (int k = 1; k < pts.size(); k++) s += Math.hypot(pts.get(k)[0] - pts.get(k - 1)[0], pts.get(k)[1] - pts.get(k - 1)[1]);
        return Math.round(s);
    }

    private String rulerHint() {
        if (ruler.isEmpty()) return T.t("ruler.start");
        if (ruler.size() == 1) return T.t("ruler.next");
        return T.t("ruler.total", ruler.size() - 1, rulerTotal(ruler));
    }

    private static String routeHint(RoutePlan p) {
        return switch (p.status()) {
            case EMPTY -> T.t("route.empty");
            case PICKING -> T.t("route.picking");
            case LOADING -> T.t("route.loading");
            case BUILDING -> T.t("route.building");
            case READY -> T.t("route.ready", p.stats());
            case NO_PATH -> T.t("route.no_path");
            case TOO_FAR -> T.t("route.too_far");
        };
    }

    /** Под заголовком — что сейчас делает клик. */
    private void drawHint(GuiGraphics g, String t) {
        int w = font.width(t) + 16, x = (width - w) / 2, y = 3 + font.lineHeight + 11;
        Paper.plaque(g, x, y, x + w, y + font.lineHeight + 6);
        g.drawString(font, t, x + 8, y + 4, Paper.RED, false);
    }

    /** Табличка сверху: мир · измерение. */
    private void drawTitle(GuiGraphics g) {
        String t = worldName() + "  ·  " + dimName();
        int w = font.width(t) + 20, x = (width - w) / 2;
        Paper.plaque(g, x, 3, x + w, 3 + font.lineHeight + 8);
        g.drawString(font, t, x + 10, 8, Paper.INK, false);
    }

    /** Табличка внизу слева: координаты под курсором (или игрока) и масштабная линейка. */
    private void drawStatus(GuiGraphics g, int mouseX, int mouseY) {
        boolean over = overMap(mouseX, mouseY) && getChildAt(mouseX, mouseY).isEmpty();
        int bx = over ? (int) Math.floor(wx(mouseX)) : minecraft.player == null ? 0 : minecraft.player.getBlockX();
        int bz = over ? (int) Math.floor(wz(mouseY)) : minecraft.player == null ? 0 : minecraft.player.getBlockZ();
        String co = "X " + bx + "   Z " + bz;
        int len = SCALES[SCALES.length - 1];
        for (int s : SCALES) if (s * zoom >= 40) { len = s; break; }
        int barPx = Math.round(len * zoom);
        String km = String.valueOf(len / 1000.0);
        if (km.endsWith(".0")) km = km.substring(0, km.length() - 2);
        String lbl = len >= 1000 ? T.t("unit.km", km) : T.t("unit.blocks", len);
        LocalMap m = LocalMap.get();
        String busy = m.queued() > 0 ? "   " + T.t("map.scanning", m.queued()) : "";
        int w = font.width(co) + 14 + barPx + 6 + font.width(lbl) + font.width(busy) + 14;
        int y0p = height - 4 - font.lineHeight - 8;
        Paper.plaque(g, 4, y0p, 4 + w, height - 4);
        int x = 11, ty = y0p + 5;
        g.drawString(font, co, x, ty, Paper.INK, false);
        x += font.width(co) + 14;
        int by = ty + font.lineHeight / 2;
        g.fill(x, by, x + barPx, by + 1, Paper.INK);
        g.fill(x, by - 3, x + 1, by + 3, Paper.INK);
        g.fill(x + barPx - 1, by - 3, x + barPx, by + 3, Paper.INK);
        g.fill(x + barPx / 2, by - 2, x + barPx / 2 + 1, by + 2, Paper.INK);
        x += barPx + 6;
        g.drawString(font, lbl, x, ty, Paper.INK, false);
        if (!busy.isEmpty()) g.drawString(font, busy, x + font.width(lbl), ty, Paper.INK_SOFT, false);
    }

    private String worldName() {
        if (minecraft.getSingleplayerServer() != null) return minecraft.getSingleplayerServer().getWorldData().getLevelName();
        return minecraft.getCurrentServer() != null ? minecraft.getCurrentServer().name : "?";
    }

    String dimName() {
        return dimLabel(LocalMap.get().shownDimension());
    }

    /** Название измерения из переводов игры (в TFG — «Земля», «Бездна», «Луна», «Орбита Марса»…). */
    static String dimLabel(String id) {
        if (id == null) return "";
        var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
        if (rl != null) {
            String key = "dimension." + rl.getNamespace() + "." + rl.getPath().replace('/', '.');
            if (net.minecraft.client.resources.language.I18n.exists(key)) return net.minecraft.client.resources.language.I18n.get(key);
        }
        return switch (id) {
            case "minecraft:overworld" -> T.t("dim.overworld");
            case "minecraft:the_nether" -> T.t("dim.the_nether");
            case "minecraft:the_end" -> T.t("dim.the_end");
            default -> id;
        };
    }

    private void zoomAround(int dir) {
        zoom = Mth.clamp(zoom * (float) Math.pow(2, dir * 0.5), MIN_ZOOM, MAX_ZOOM);
    }

    /** Курсор над самой картой, а не над панелями. */
    private boolean overMap(double mx, double my) {
        if (mx <= x0 || mx >= x1 || my <= y0 || my >= y1) return false;
        if (listOpen && mx >= LIST_X && mx <= LIST_X + LIST_W) return false;
        return editing == null || mx < width - PANEL_W - 4;
    }

    // ---------- список меток (слева) ----------

    private void initList() {
        var search = new EditBox(font, LIST_X + 10, 30, LIST_W - 20, 12, T.c("list.search"));
        search.setBordered(false);
        search.setTextColor(0x3A2814);
        search.setMaxLength(40);
        search.setValue(listQuery);
        search.setResponder(s -> { listQuery = s; listScroll = 0; });
        addRenderableWidget(search);
    }

    /** Отфильтрованные и упорядоченные по расстоянию метки — так же, как в списке. */
    private List<Markers.Marker> listed(List<Markers.Marker> all) {
        String q = listQuery.toLowerCase(Locale.ROOT).trim();
        Player p = minecraft.player;
        List<Markers.Marker> out = new ArrayList<>();
        for (Markers.Marker m : all) {
            if (q.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(q) || m.desc.toLowerCase(Locale.ROOT).contains(q)) out.add(m);
        }
        if (p != null) out.sort(Comparator.comparingDouble(m -> Math.hypot(m.x - p.getX(), m.z - p.getZ())));
        return out;
    }

    private void drawList(GuiGraphics g, List<Markers.Marker> all, int mouseX, int mouseY) {
        int top = 4, bottom = height - 30;
        Paper.plaque(g, LIST_X, top, LIST_X + LIST_W, bottom);
        List<Markers.Marker> items = listed(all);
        g.drawString(font, T.t("list.title", items.size()), LIST_X + 8, top + 8, Paper.INK, false);
        g.fill(LIST_X + 8, 26, LIST_X + LIST_W - 8, 42, 0xFFF8EED4);
        g.renderOutline(LIST_X + 8, 26, LIST_W - 16, 16, Paper.INK);
        if (listQuery.isEmpty()) g.drawString(font, T.t("list.search_hint"), LIST_X + 12, 30, Paper.INK_FAINT, false);
        int y = 48 - (int) listScroll;
        g.enableScissor(LIST_X + 2, 46, LIST_X + LIST_W - 2, bottom - 2);
        for (Markers.Marker m : items) {
            if (y > 30 && y < bottom) {
                boolean hover = mouseX >= LIST_X + 4 && mouseX < LIST_X + LIST_W - 4 && mouseY >= y && mouseY < y + ROW
                        && mouseY > 46 && mouseY < bottom;
                if (hover) g.fill(LIST_X + 4, y, LIST_X + LIST_W - 4, y + ROW, 0x553A2814);
                g.blit(MarkerOverlay.icon(m.icon), LIST_X + 8, y + 1, 0, 0, 16, 16, 16, 16);
                String name = m.name.isEmpty() ? T.t("marker.unnamed") : m.name;
                String dist = MarkerOverlay.distance(m).replace("  ·  ", "");
                int dw = font.width(dist);
                g.drawString(font, font.plainSubstrByWidth(name, LIST_W - 40 - dw), LIST_X + 28, y + 5, Paper.INK, false);
                g.drawString(font, dist, LIST_X + LIST_W - 10 - dw, y + 5, Paper.INK_SOFT, false);
            }
            y += ROW;
        }
        g.disableScissor();
    }

    private Markers.Marker listRowAt(double mx, double my) {
        if (!listOpen || mx < LIST_X + 4 || mx >= LIST_X + LIST_W - 4 || my < 46 || my > height - 32) return null;
        var marks = LocalMap.get().markers();
        if (marks == null) return null;
        int k = (int) Math.floor((my - 48 + listScroll) / ROW);
        List<Markers.Marker> items = listed(marks.all());
        return k >= 0 && k < items.size() ? items.get(k) : null;
    }

    // ---------- панель метки (справа) ----------

    @Override
    public void removed() {
        editing = null;                                  // закрыли карту — панель метки не всплывёт при следующем открытии
        if (!toSettings) LocalMap.get().view(null);      // глобус — только пока карта открыта
        toSettings = false;
        super.removed();
    }

    private void openMarker(Markers.Marker m) {
        editing = m;
        iconScroll = 0;
        rebuildWidgets();
    }

    private void closePanel() {
        editing = null;
        picker = false;
        rebuildWidgets();
    }

    private void initPanel() {
        int px = width - PANEL_W - 4 + 8, w = PANEL_W - 16;
        var name = new EditBox(font, px + 4, 64, w - 8, 12, T.c("marker.name"));
        name.setBordered(false);
        name.setTextColor(0x3A2814);
        name.setMaxLength(40);
        name.setValue(editing.name);
        name.setResponder(s -> editing.name = s);
        addRenderableWidget(name);
        var desc = new EditBox(font, px + 4, 100, w - 8, 12, T.c("marker.desc"));
        desc.setBordered(false);
        desc.setTextColor(0x3A2814);
        desc.setMaxLength(160);
        desc.setValue(editing.desc);
        desc.setResponder(s -> editing.desc = s);
        addRenderableWidget(desc);
        addRenderableWidget(new PaperButton(px, 134, w, T.c("marker.choose_icon"), () -> picker,
                () -> { picker = true; iconScroll = 0; }));
        addRenderableWidget(new PaperButton(px, 156, w, Component.literal(""), () -> editing != null && editing.world,
                () -> { if (editing != null) editing.world = !editing.world; }) {
            @Override
            public Component getMessage() {                       // надпись — по текущему состоянию
                return T.c(editing != null && editing.world ? "marker.world_on" : "marker.world_off");
            }
        });
        int by = height - 30, bw = (w - 6) / 2;
        addRenderableWidget(new PaperButton(px, by - 18, bw, T.c("button.save"), () -> false, () -> {
            var ms = LocalMap.get().markers();
            if (ms != null) {
                var m = editing.copy();
                m.kind = "";                                  // сохранил сам — метка твоя, лимит гибелей её не тронет
                ms.put(m);
            }
            closePanel();
        }));
        addRenderableWidget(new PaperButton(px + bw + 6, by - 18, bw, T.c("button.close"), () -> false, this::closePanel));
        var ms = LocalMap.get().markers();
        if (ms != null && ms.byId(editing.id) != null) {
            addRenderableWidget(new PaperButton(px, by, bw, T.c("button.delete"), () -> false, () -> {
                ms.remove(editing.id);
                closePanel();
            }));
            addRenderableWidget(new PaperButton(px + bw + 6, by, bw, T.c("marker.show"), () -> false, () -> {
                cx = editing.x + 0.5;
                cz = editing.z + 0.5;
            }));
        }
        setInitialFocus(name);
    }

    private void drawPanel(GuiGraphics g) {
        int x0p = width - PANEL_W - 4, px = x0p + 8;
        Paper.plaque(g, x0p, 4, width - 4, height - 4);
        g.blit(MarkerOverlay.icon(editing.icon), px, 12, 0, 0, 16, 16, 16, 16);
        g.drawString(font, editing.name.isEmpty() ? T.t("marker.new") : editing.name, px + 22, 12, Paper.INK, false);
        g.drawString(font, "X " + editing.x + "  Y " + editing.y + "  Z " + editing.z, px + 22, 24, Paper.INK_SOFT, false);
        g.drawString(font, dimName() + MarkerOverlay.distance(editing), px, 36, Paper.INK_SOFT, false);
        int w = PANEL_W - 16;
        g.drawString(font, T.t("marker.name"), px, 50, Paper.INK, false);
        field(g, px, 60, w);
        g.drawString(font, T.t("marker.desc"), px, 86, Paper.INK, false);
        field(g, px, 96, w);
        g.drawString(font, T.t("marker.icon", Markers.iconName(editing.icon)), px, 122, Paper.INK, false);
    }

    // ---------- окно выбора значка (поверх карты) ----------

    /** Ячейка: заголовок группы (icon == null) или значок; координаты — без прокрутки. */
    private record GridItem(int x, int y, Markers.Icon icon, String header) {}

    private int[] pickerRect() {
        int w = Math.min(width - 60, 12 * CELL + 24), h = height - 40;
        int x = (width - w) / 2, y = 20;
        return new int[]{x, y, x + w, y + h};
    }

    private List<GridItem> iconLayout(int gx, int gy, int w) {
        List<GridItem> out = new ArrayList<>();
        int perRow = Math.max(1, w / CELL), y = gy, col = 0;
        String group = null;
        for (Markers.Icon ic : Markers.ICONS) {
            if (!ic.group().equals(group)) {
                if (col != 0) y += CELL;
                group = ic.group();
                out.add(new GridItem(gx, y, null, ic.groupName()));
                y += 14;
                col = 0;
            }
            out.add(new GridItem(gx + col * CELL, y, ic, null));
            if (++col == perRow) {
                col = 0;
                y += CELL;
            }
        }
        return out;
    }

    private void drawPicker(GuiGraphics g, int mouseX, int mouseY) {
        int[] r = pickerRect();
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(0, 0, width, height, 0x66000000);
        Paper.plaque(g, r[0], r[1], r[2], r[3]);
        g.drawCenteredString(font, T.t("picker.title", Markers.ICONS.size()), (r[0] + r[2]) / 2, r[1] + 7, Paper.INK);
        int gx = r[0] + 12, gy = r[1] + 22, gy1 = r[3] - 8;
        g.enableScissor(r[0] + 4, gy - 2, r[2] - 4, gy1);
        for (GridItem it : iconLayout(gx, gy, r[2] - r[0] - 24)) {
            int y = it.y - (int) iconScroll;
            if (y + CELL < gy - 2 || y > gy1) continue;
            if (it.icon == null) {
                g.drawString(font, it.header, gx, y + 3, Paper.INK_SOFT, false);
                g.fill(gx + font.width(it.header) + 4, y + 7, r[2] - 12, y + 8, Paper.INK_FAINT);
                continue;
            }
            boolean sel = editing != null && it.icon.id().equals(editing.icon);
            boolean hov = mouseX >= it.x && mouseX < it.x + 22 && mouseY >= y && mouseY < y + 22;
            g.fill(it.x, y, it.x + 22, y + 22, sel ? 0xFFD9B98A : hov ? 0xFFE3D09E : 0xFFF1E4C0);
            g.renderOutline(it.x, y, 22, 22, sel ? Paper.RED : Paper.INK_FAINT);
            g.blit(MarkerOverlay.icon(it.icon.id()), it.x + 3, y + 3, 0, 0, 16, 16, 16, 16);
        }
        g.disableScissor();
        g.pose().popPose();
        Markers.Icon hi = iconAt(mouseX, mouseY);
        if (hi != null) Tip.draw(g, font, List.of(hi.name(), hi.groupName()), mouseX, mouseY, width, height);
    }

    private Markers.Icon iconAt(double mx, double my) {
        if (!picker) return null;
        int[] r = pickerRect();
        int gx = r[0] + 12, gy = r[1] + 22;
        if (mx < r[0] || mx > r[2] || my < gy - 2 || my > r[3] - 8) return null;
        for (GridItem it : iconLayout(gx, gy, r[2] - r[0] - 24)) {
            int y = it.y - (int) iconScroll;
            if (it.icon != null && mx >= it.x && mx < it.x + 22 && my >= y && my < y + 22) return it.icon;
        }
        return null;
    }

    /** Поле ввода на бумаге: светлая подложка и рамка тушью вместо ванильного чёрного. */
    private static void field(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 16, 0xFFF8EED4);
        g.renderOutline(x, y, w, 16, Paper.INK);
    }

    // ---------- мышь и клавиши ----------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (picker) {
            Markers.Icon ic = button == 0 ? iconAt(mx, my) : null;
            if (ic != null && editing != null) editing.icon = ic.id();
            int[] r = pickerRect();
            if (ic != null || mx < r[0] || mx > r[2] || my < r[1] || my > r[3]) picker = false;  // выбрал или мимо окна
            return true;
        }
        if (super.mouseClicked(mx, my, button)) return true;      // кнопки и поля
        pressX = mx;
        pressY = my;
        dragged = false;
        if (button == 0 && tool == Tool.PEN && overMap(mx, my)) {
            if (eraser) {
                erase(mx, my);
            } else {
                stroke = new Drawings.Stroke();
                stroke.color = Drawings.COLORS[penColor];
                stroke.pts.add(new int[]{(int) Math.floor(wx(mx)), (int) Math.floor(wz(my))});
            }
        }
        return true;
    }

    private void erase(double mx, double my) {
        var d = LocalMap.get().drawings();
        if (d != null) d.eraseNear(wx(mx), wz(my), 6 / zoom);              // 6 пикселей экрана вокруг курсора
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (Math.abs(mx - pressX) >= 3 || Math.abs(my - pressY) >= 3) dragged = true;
        if (button == 0 && tool == Tool.PEN) {
            if (eraser) {
                erase(mx, my);
            } else if (stroke != null) {
                int[] last = stroke.pts.get(stroke.pts.size() - 1);
                int bx = (int) Math.floor(wx(mx)), bz = (int) Math.floor(wz(my));
                if (Math.hypot(bx - last[0], bz - last[1]) >= Math.max(1, 2 / zoom)) stroke.pts.add(new int[]{bx, bz});
            }
            return true;
        }
        if (button == 0 || button == 1) {
            cx -= dx / zoom;
            cz -= dy / zoom;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (picker) return true;
        if (tool == Tool.PEN && button == 0) {
            var d = LocalMap.get().drawings();
            if (stroke != null && d != null) d.add(stroke);
            stroke = null;
            return true;
        }
        if (button == 1 && !dragged) {                                     // правый клик — убрать последнюю точку
            if (tool == Tool.RULER && !ruler.isEmpty()) { ruler.remove(ruler.size() - 1); return true; }
            if (tool == Tool.ROUTE) { RoutePlan.get().undo(); return true; }
        }
        boolean click = button == 0 && !dragged;
        Markers.Marker row = click ? listRowAt(mx, my) : null;
        if (row != null) {
            cx = row.x + 0.5;
            cz = row.z + 0.5;
            openMarker(row.copy());
            return true;
        }
        if (!click || !overMap(mx, my)) return super.mouseReleased(mx, my, button);
        int bx = (int) Math.floor(wx(mx)), bz = (int) Math.floor(wz(my));
        var marks = LocalMap.get().markers();
        if (marks != null && tool != Tool.RULER) {
            var hit = MarkerOverlay.hovered(this, marks.all(), mx, my);
            if (hit != null) {
                openMarker(hit.copy());
                return true;
            }
        }
        switch (tool) {
            case ROUTE -> RoutePlan.get().add(bx, bz);
            case RULER -> ruler.add(new int[]{bx, bz});
            case MARKER -> {
                var m = new Markers.Marker();
                m.x = bx;
                m.z = bz;
                Integer h = LocalMap.get().heightAt(bx, bz);
                m.y = h != null ? h : minecraft.player == null ? 64 : minecraft.player.getBlockY();
                openMarker(m);
            }
            case MINER -> {
                MinerPlan plan = LocalMap.get().miner();
                if (plan != null && Veins.source() != null) {
                    if (!plan.hasOrigin() || hasShiftDown()) plan.setOrigin(bx, bz);
                    else if (plan.inReach(plan.cellI(bx), plan.cellJ(bz))) plan.cycle(plan.cellI(bx), plan.cellJ(bz));
                }
            }
            default -> { return super.mouseReleased(mx, my, button); }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (picker) {
            int[] r = pickerRect();
            var items = iconLayout(0, 0, r[2] - r[0] - 24);
            int content = items.isEmpty() ? 0 : items.get(items.size() - 1).y + CELL;
            int view = r[3] - 8 - (r[1] + 22);
            iconScroll = Math.max(0, Math.min(Math.max(0, content - view), iconScroll - delta * CELL));
            return true;
        }
        if (listOpen && mx >= LIST_X && mx <= LIST_X + LIST_W) {
            listScroll = Math.max(0, listScroll - delta * ROW * 2);
            return true;
        }
        double bx = wx(mx), bz = wz(my);
        zoom = Mth.clamp(zoom * (float) Math.pow(2, delta * 0.5), MIN_ZOOM, MAX_ZOOM);
        cx = bx - (mx - (x0 + x1) / 2.0) / zoom;     // точка под курсором остаётся на месте
        cz = bz - (my - (y0 + y1) / 2.0) / zoom;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (getFocused() instanceof EditBox eb && eb.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) {
            return super.keyPressed(key, scan, mods);              // печатаем — J и пробел не наши
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && picker) {
            picker = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && editing != null) {
            closePanel();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && tool != Tool.NONE) {
            tool = Tool.NONE;
            rebuildWidgets();
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE && minecraft.player != null) {
            centerOnPlayer();
            return true;
        }
        if (ClientSetup.OPEN_MAP.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
