package ru.stef.pergament.client.gui;

import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import ru.stef.pergament.client.geo.Names;
import ru.stef.pergament.client.geo.Toponyms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * Подписи местности — как названия на картах: вода — светлыми буквами в синей обводке, река — вдоль русла
 * (буквы по изгибам, у длинной — повтор); хребты и пустыни — прописными вразрядку; лес и болото — зелёным
 * и болотным; остров — тушью; вулкан и вершина — ▲ и имя. Размер постоянный (не растёт с зумом), крупные
 * объекты подписываются первыми, наложения пропускаются, подпись больше объекта — не рисуется.
 * Большая карта и миникарта (там мельче и только крупное).
 */
final class TopoOverlay {
    /** Вид: перевод мира в экран, пикселей на блок, рамка. */
    record View(DoubleUnaryOperator sx, DoubleUnaryOperator sy, double zoom, int x0, int y0, int x1, int y1) {}

    private static final int WATER = 0xFFF4ECD6, WATER_HALO = 0xE0173052, RANGE = 0xFF6B4A2B, VOLCANO = 0xFF8C2A1A,
            FOREST = 0xFF2E5A27, DESERT = 0xFF8A6420, SWAMP = 0xFF4F5A2A, ISLAND = 0xFF3A2814, PEAK = 0xFF5A4030,
            HALO = 0xD8F1E4C0;

    /** Подписи последнего кадра большой карты с их рамками — для наведения и клика (переименовать). */
    private record Hit(int[] box, Toponyms.Label label) {}
    private static final List<Hit> HITS = new ArrayList<>();

    private TopoOverlay() {}

    /** Подпись под курсором (по последнему кадру) или null. */
    static Toponyms.Label at(double mx, double my) {
        for (Hit h : HITS) if (mx >= h.box[0] && mx < h.box[2] && my >= h.box[1] && my < h.box[3]) return h.label;
        return null;
    }

    /** Имя, как его видит игрок: своё или выданное (на языке интерфейса). */
    static String name(Toponyms.Label l) {
        if (l.custom() != null && !l.custom().isBlank()) return l.custom();
        return Names.display(l.nameId(), Minecraft.getInstance().getLanguageManager().getSelected(), l.fallback());
    }

    private record Style(int color, int halo, boolean italic, boolean caps, float scale, String prefix) {}

    private static Style style(String type) {
        return switch (type) {
            case Toponyms.SEA -> new Style(WATER, WATER_HALO, false, true, 1.25f, "");
            case Toponyms.LAKE -> new Style(WATER, WATER_HALO, false, false, 1f, "");
            case Toponyms.RIVER -> new Style(WATER, WATER_HALO, false, false, 0.85f, "");
            case Toponyms.RANGE -> new Style(RANGE, HALO, false, true, 1f, "");
            case Toponyms.DESERT -> new Style(DESERT, HALO, false, true, 1f, "");
            case Toponyms.FOREST -> new Style(FOREST, HALO, false, false, 0.9f, "");
            case Toponyms.SWAMP -> new Style(SWAMP, HALO, false, false, 0.9f, "");
            case Toponyms.ISLAND -> new Style(ISLAND, HALO, false, false, 0.9f, "");
            case Toponyms.VOLCANO -> new Style(VOLCANO, HALO, false, false, 0.85f, "▲ ");
            default -> new Style(PEAK, HALO, false, false, 0.8f, "▲ ");          // вершина
        };
    }

    /** Большая карта. */
    static void draw(GuiGraphics g, Font font, MapScreen v, List<Toponyms.Label> labels) {
        HITS.clear();
        draw(g, font, new View(v::sx, v::sy, v.zoomGui(), 0, 0, v.width, v.height), labels, 1f, true);
    }

    /** Миникарта: мельче, только крупное, без вершин. */
    static void drawMini(GuiGraphics g, Font font, View view, List<Toponyms.Label> labels) {
        draw(g, font, view, labels, 1f, false);
    }

    private static void draw(GuiGraphics g, Font font, View v, List<Toponyms.Label> labels, float k, boolean big) {
        if (labels.isEmpty()) return;
        List<Toponyms.Label> order = new ArrayList<>(labels);
        order.sort(Comparator.comparingInt((Toponyms.Label l) -> point(l) ? 30 : l.chunks()).reversed());
        List<int[]> placed = new ArrayList<>();
        for (Toponyms.Label l : order) {
            if (!big && (point(l) || l.chunks() < 6)) continue;                // на миникарте — только крупное
            Style st = style(l.type());
            String name = name(l);
            if (name.isEmpty()) continue;
            if (st.caps) name = name.toUpperCase(Locale.ROOT);
            name = st.prefix + name;
            float scale = crisp(st.scale * k);
            int spacing = st.caps ? 1 : 0;
            int tw = Math.round(width(font, name, st.italic, spacing) * scale), th = Math.round(9 * scale);
            double extent = l.sizeBlocks() * v.zoom();
            if (!point(l) && extent < tw * 0.5) continue;                        // объект мельче подписи — не подписываем
            if (point(l) && v.zoom() < (l.type().equals(Toponyms.PEAK) ? 0.7 : 0.5)) continue;
            if (l.path() != null && along(g, font, v, l, name, st, scale, placed, big)) continue;
            int cx = (int) Math.round(v.sx().applyAsDouble(l.x() + 0.5)), cy = (int) Math.round(v.sy().applyAsDouble(l.z() + 0.5));
            int x0 = cx - tw / 2, y0 = cy - th / 2;
            if (x0 + tw < v.x0() || y0 + th < v.y0() || x0 > v.x1() || y0 > v.y1()) continue;
            int[] box = {x0 - 3, y0 - 2, x0 + tw + 3, y0 + th + 2};
            if (hits(placed, box)) continue;
            placed.add(box);
            if (big) HITS.add(new Hit(box, l));
            var pose = g.pose();
            pose.pushPose();
            pose.translate(x0, y0, 0);
            pose.scale(scale, scale, 1f);
            haloed(g, font, name, st, spacing);
            pose.popPose();
        }
    }

    /**
     * Масштаб — кратный пикселю экрана: пиксельный шрифт при дробном масштабе (0.85, 1.25) расплывается.
     * Не мельче одного пикселя экрана на пиксель шрифта.
     */
    private static float crisp(float scale) {
        double gs = Minecraft.getInstance().getWindow().getGuiScale();
        return (float) (Math.max(1, Math.round(scale * gs)) / gs);
    }

    private static boolean point(Toponyms.Label l) {
        return l.type().equals(Toponyms.VOLCANO) || l.type().equals(Toponyms.PEAK);
    }

    private static boolean hits(List<int[]> placed, int[] box) {
        for (int[] p : placed) if (box[0] < p[2] && p[0] < box[2] && box[1] < p[3] && p[1] < box[3]) return true;
        return false;
    }

    /**
     * Река — по руслу: буквы по одной вдоль ломаной (поворот по касательной), середина подписи — на середине русла,
     * у длинной реки — повтор. false — русло на экране короче подписи (тогда — прямо, по центру).
     */
    private static boolean along(GuiGraphics g, Font font, View v, Toponyms.Label l, String name, Style st, float scale,
                                 List<int[]> placed, boolean big) {
        int[] p = l.path();
        int n = p.length / 2;
        double[] xs = new double[n], ys = new double[n], acc = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = v.sx().applyAsDouble(p[i * 2] + 0.5);
            ys[i] = v.sy().applyAsDouble(p[i * 2 + 1] + 0.5);
            if (i > 0) acc[i] = acc[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        }
        double len = acc[n - 1];
        List<String> chars = new ArrayList<>();
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            chars.add(new String(Character.toChars(cp)));
            i += Character.charCount(cp);
        }
        double[] cw = new double[chars.size()];
        double tw = 0;
        for (int i = 0; i < cw.length; i++) {
            cw[i] = font.width(styled(chars.get(i), st.italic)) * scale;
            tw += cw[i];
        }
        if (len < tw * 1.15) return false;
        // одна копия — у точки русла, ближайшей к центру вида (у длинной реки середина бывает далеко за краем),
        // остальные — с шагом от неё в обе стороны
        double vx = (v.x0() + v.x1()) / 2.0, vy = (v.y0() + v.y1()) / 2.0, best = Double.MAX_VALUE, sc = len / 2;
        for (int i = 0; i < n; i++) {
            double d = Math.hypot(xs[i] - vx, ys[i] - vy);
            if (d < best) { best = d; sc = acc[i]; }
        }
        double step = tw + (big ? 260 : 120);
        sc = Math.max(tw / 2, Math.min(len - tw / 2, sc));
        List<Double> centers = new ArrayList<>();
        for (double c = sc; c >= tw / 2; c -= step) centers.add(c);
        for (double c = sc + step; c <= len - tw / 2; c += step) centers.add(c);
        for (double center : centers) {
            double s = center - tw / 2;
            double[][] at = new double[cw.length][];                 // x, y, угол
            int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int i = 0; i < cw.length; i++) {
                double mid = s + cw[i] / 2;
                double[] a = pointAt(xs, ys, acc, mid - 3), b = pointAt(xs, ys, acc, mid + 3), m = pointAt(xs, ys, acc, mid);
                double ang = Math.atan2(b[1] - a[1], b[0] - a[0]);
                if (Math.abs(ang) < Math.toRadians(12)) ang = 0;          // почти ровно — ровно: поворот размывает буквы
                at[i] = new double[]{Math.round(m[0]), Math.round(m[1]), ang};
                box[0] = Math.min(box[0], (int) m[0] - 5); box[1] = Math.min(box[1], (int) m[1] - 5);
                box[2] = Math.max(box[2], (int) m[0] + 5); box[3] = Math.max(box[3], (int) m[1] + 5);
                s += cw[i];
            }
            if (box[2] < v.x0() || box[3] < v.y0() || box[0] > v.x1() || box[1] > v.y1() || hits(placed, box)) continue;
            placed.add(box);
            if (big) HITS.add(new Hit(box, l));
            var pose = g.pose();
            for (int i = 0; i < cw.length; i++) {
                pose.pushPose();
                pose.translate(at[i][0], at[i][1], 0);
                pose.mulPose(Axis.ZP.rotation((float) at[i][2]));
                pose.scale(scale, scale, 1f);
                pose.translate(-cw[i] / scale / 2, -4.5, 0);
                haloed(g, font, chars.get(i), st, 0);
                pose.popPose();
            }
        }
        return true;
    }

    /** Точка ломаной на расстоянии s от начала (за краями — продолжение крайнего отрезка). */
    private static double[] pointAt(double[] xs, double[] ys, double[] acc, double s) {
        int n = xs.length, i = 1;
        while (i < n - 1 && acc[i] < s) i++;
        double seg = acc[i] - acc[i - 1], t = seg <= 0 ? 0 : (s - acc[i - 1]) / seg;
        return new double[]{xs[i - 1] + (xs[i] - xs[i - 1]) * t, ys[i - 1] + (ys[i] - ys[i - 1]) * t};
    }

    private static void haloed(GuiGraphics g, Font font, String s, Style st, int spacing) {
        for (int[] o : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, 1}, {-1, 1}, {1, -1}}) {
            text(g, font, s, o[0], o[1], st.halo, st.italic, spacing);
        }
        text(g, font, s, 0, 0, st.color, st.italic, spacing);
    }

    private static int width(Font font, String s, boolean italic, int spacing) {
        if (spacing == 0) return font.width(styled(s, italic));
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += font.width(styled(new String(Character.toChars(cp)), italic)) + spacing;
            i += Character.charCount(cp);
        }
        return Math.max(0, w - spacing);
    }

    private static void text(GuiGraphics g, Font font, String s, int x, int y, int color, boolean italic, int spacing) {
        if (spacing == 0) {
            g.drawString(font, styled(s, italic), x, y, color, false);
            return;
        }
        for (int i = 0; i < s.length(); ) {                                    // вразрядку — по букве
            int cp = s.codePointAt(i);
            Component ch = styled(new String(Character.toChars(cp)), italic);
            g.drawString(font, ch, x, y, color, false);
            x += font.width(ch) + spacing;
            i += Character.charCount(cp);
        }
    }

    private static Component styled(String s, boolean italic) {
        return italic ? Component.literal(s).withStyle(ChatFormatting.ITALIC) : Component.literal(s);
    }
}
