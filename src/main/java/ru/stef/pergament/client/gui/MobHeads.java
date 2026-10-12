package ru.stef.pergament.client.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.joml.Vector4f;
import ru.stef.pergament.Pergament;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Голова моба — настоящая, из его модели и его текстуры (с ресурспаками и окрасами), анфас.
 * Деталь «голова» ищем так: HeadedModel (люди, жители) → headParts() у ванильных четвероногих →
 * деталь с именем «head» в иерархии модели (так устроены животные TFC и большинство модов).
 * Имена деталей — строки из описания слоя, они не шифруются, поэтому поиск работает и в боевой сборке.
 * Не нашли — возвращаем false, рисуется кружок по типу моба.
 */
final class MobHeads {
    /**
     * parts — детали головы. inner не пуст — у единственной детали есть свой куб-шея (лошадь, лама):
     * рисуем только её детей с этими именами (голова, морда, уши), без шеи, гривы и упряжи.
     */
    private record Head(List<ModelPart> parts, List<String> inner, boolean whole) {}

    private static final Map<EntityType<?>, Head> CACHE = new HashMap<>();
    private static final Head NONE = new Head(List.of(), List.of(), false);
    /** Голову в модели не нашли — портрет настоящим рендером игры (как в инвентаре). */
    private static final Head PORTRAIT = new Head(List.of(), List.of(), false);
    private static Method headParts, bodyParts, listParts;
    private static final String DEBUG = System.getProperty("pergament.selftest.headdebug");
    private static final java.util.Set<EntityType<?>> DEBUGGED = new java.util.HashSet<>();

    private MobHeads() {}

    /** Нарисовать голову размером size с центром (cx, cy). false — головы у модели не нашлось. */
    static boolean draw(GuiGraphics g, LivingEntity e, int cx, int cy, int size) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.getEntityRenderDispatcher().getRenderer(e) instanceof LivingEntityRenderer<?, ?> lr)) {
            return portrait(g, e, cx, cy, size);                  // GeckoLib и прочие свои рендеры модов
        }
        EntityModel<?> model = lr.getModel();
        Head head = CACHE.computeIfAbsent(e.getType(), t -> choose(model, t));
        if (head == PORTRAIT) return portrait(g, e, cx, cy, size);
        List<ModelPart> parts = head.parts;
        if (parts.isEmpty()) return false;
        List<String> inner = new ArrayList<>();
        for (String n : head.inner) if (parts.get(0).getChild(n).visible) inner.add(n);   // упряжь без седла скрыта
        @SuppressWarnings({"unchecked", "rawtypes"})
        ResourceLocation tex = ((LivingEntityRenderer) lr).getTextureLocation(e);

        // Модель одна на весь тип: в ней поза того моба, кого игра рисовала последним (жуёт, пасётся, уши
        // хлопают; у анимированных моделей TFC — ещё и масштаб деталей). Всё дерево головы — в исходную позу
        // из описания модели, масштаб 1; после рисования — вернуть как было.
        List<ModelPart> tree = new ArrayList<>();
        for (ModelPart p : parts) p.getAllParts().forEach(tree::add);
        List<PartPose> saved = new ArrayList<>(tree.size());
        float[] scales = new float[tree.size() * 3];
        for (int i = 0; i < tree.size(); i++) {
            ModelPart p = tree.get(i);
            saved.add(p.storePose());
            scales[i * 3] = p.xScale; scales[i * 3 + 1] = p.yScale; scales[i * 3 + 2] = p.zScale;
            p.resetPose();
            p.xScale = p.yScale = p.zScale = 1f;
        }
        // главную деталь — в начало координат без поворота, остальные — на тех же местах относительно неё
        // (целиком — как есть: позу задаёт сама модель)
        ModelPart first = parts.get(0);
        float ox = first.x, oy = first.y, oz = first.z;
        if (!head.whole) for (ModelPart p : parts) {
            p.x -= ox; p.y -= oy; p.z -= oz;
            p.xRot = 0; p.yRot = 0; p.zRot = 0;
        }
        int half = size / 2;
        g.enableScissor(cx - half, cy - half, cx + half, cy + half);   // поля шляпы, рога — обрезаются рамкой
        try {
            // кадр — по морде: самый крупный куб головы (рога, уши, гребни, клювы, бороды — мельче и плоские),
            // с запасом; что торчит дальше — срежет рамка, как на портрете
            float[] all = bounds(parts, inner, false);               // minX, minY, minZ, maxX, maxY, maxZ — в блоках
            float[] b = head.whole ? all : face(parts, inner);
            if (b[0] > b[3]) b = all;
            float w = b[3] - b[0], h = b[4] - b[1], d = b[5] - b[2];
            if (DEBUG != null && DEBUG.contains(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())
                    && DEBUGGED.add(e.getType())) {
                Pergament.LOG.info("HEADDRAW {} face={} all={}", e.getType(), java.util.Arrays.toString(b), java.util.Arrays.toString(all));
            }
            // морда — длинная шея (верблюд) или вырожденная — портрет настоящим рендером
            if (!head.whole && (h > 3f * w || Math.max(w, h) < 1 / 16f)) {
                CACHE.put(e.getType(), PORTRAIT);
                return portrait(g, e, cx, cy, size);
            }
            boolean profile = head.whole;                      // рыба — узнаётся сбоку; голова — анфас
            float span = Math.max(profile ? d : w, h);
            if (!head.whole) span = Math.min(span * 1.25f, Math.max(all[3] - all[0], all[4] - all[1]));
            if (span <= 0) return false;
            float k = crisp(size / span);
            PoseStack ps = g.pose();
            ps.pushPose();
            ps.translate(cx, cy, 150);
            ps.scale(k, k, -k);                               // как в инвентаре: лицо (−Z модели) — к зрителю
            if (profile) ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90));
            ps.translate(-(b[0] + b[3]) / 2, -(b[1] + b[4]) / 2, -(b[2] + b[5]) / 2);
            Lighting.setupForFlatItems();
            var vc = g.bufferSource().getBuffer(model.renderType(tex));
            if (inner.isEmpty()) {
                for (ModelPart p : parts) p.render(ps, vc, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            } else {
                ModelPart base = parts.get(0);
                ps.pushPose();
                base.translateAndRotate(ps);
                for (String n : inner) base.getChild(n).render(ps, vc, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                ps.popPose();
            }
            g.flush();
            Lighting.setupFor3DItems();
            ps.popPose();
            return true;
        } catch (RuntimeException ex) {
            CACHE.put(e.getType(), NONE);                    // модель капризничает — дальше кружок
            return false;
        } finally {
            g.disableScissor();
            for (int i = 0; i < tree.size(); i++) {
                ModelPart p = tree.get(i);
                p.loadPose(saved.get(i));
                p.xScale = scales[i * 3]; p.yScale = scales[i * 3 + 1]; p.zScale = scales[i * 3 + 2];
            }
        }
    }

    /**
     * Масштаб (пикселей GUI на блок) так, чтобы пиксель текстуры моба (1/16 блока) лёг в целое число пикселей
     * экрана: иначе соседние «пиксели» морды разной ширины, и при плавном движении по карте они переливаются.
     * Чуть крупнее (край срежет рамка) или чуть мельче значка; если до целого далеко или тексель меньше
     * пикселя — как есть.
     */
    private static float crisp(float k) {
        double gs = Minecraft.getInstance().getWindow().getGuiScale();
        double texel = k * gs / 16;                              // пикселей экрана на пиксель текстуры
        if (texel < 1) return k;
        double up = Math.ceil(texel), down = Math.floor(texel);
        double t = up / texel <= 1.12 ? up : down / texel >= 0.75 ? down : texel;
        return (float) (t * 16 / gs);
    }

    /**
     * Что рисовать: голову, а водных (рыбы, кальмары, дельфины) и безголовых — целиком сбоку: у рыбы «лица» нет,
     * узнаётся она по силуэту.
     */
    private static Head choose(EntityModel<?> model, EntityType<?> type) {
        boolean water = switch (type.getCategory()) {
            case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> true;
            default -> false;
        };
        if (!water) {
            Head h = trim(find(model));
            return h.parts.isEmpty() ? PORTRAIT : h;
        }
        List<ModelPart> all = whole(model);
        return all.isEmpty() ? PORTRAIT : new Head(all, List.of(), true);
    }

    /** Все детали модели верхнего уровня. */
    private static List<ModelPart> whole(EntityModel<?> model) {
        try {
            if (model instanceof HierarchicalModel<?> hm) return List.of(hm.root());
            if (model instanceof AgeableListModel<?> am) {
                if (headParts == null) headParts = ObfuscationReflectionHelper.findMethod(AgeableListModel.class, "m_5607_");
                if (bodyParts == null) bodyParts = ObfuscationReflectionHelper.findMethod(AgeableListModel.class, "m_5608_");
                List<ModelPart> l = new ArrayList<>();
                for (Object o : (Iterable<?>) headParts.invoke(am)) l.add((ModelPart) o);
                for (Object o : (Iterable<?>) bodyParts.invoke(am)) l.add((ModelPart) o);
                return l;
            }
            if (model instanceof net.minecraft.client.model.ListModel<?> lm) {
                if (listParts == null) listParts = ObfuscationReflectionHelper.findMethod(net.minecraft.client.model.ListModel.class, "m_6195_");
                List<ModelPart> l = new ArrayList<>();
                for (Object o : (Iterable<?>) listParts.invoke(lm)) l.add((ModelPart) o);
                return l;
            }
        } catch (Throwable t) {
            Pergament.LOG.debug("Пергамент: модель {} целиком не взять: {}", model.getClass().getName(), t.toString());
        }
        return List.of();
    }

    /**
     * Портрет: моб целиком настоящим рендером игры (слои, окрасы, сёдла — всё как в мире), лицом к зрителю,
     * кадр — на уровне глаз, обрезан рамкой значка. Для моделей, где голову не найти, и для своих рендеров модов.
     */
    static boolean portrait(GuiGraphics g, LivingEntity e, int cx, int cy, int size) {
        Minecraft mc = Minecraft.getInstance();
        var disp = mc.getEntityRenderDispatcher();
        float span = Math.max(0.4f, Math.min(e.getBbWidth(), e.getBbHeight()) * 0.8f);   // сколько блоков в значке
        float k = size / span, eye = e.getEyeHeight();
        float yBody = e.yBodyRot, yBodyO = e.yBodyRotO, yRot = e.getYRot(), yRotO = e.yRotO, xRot = e.getXRot(),
                xRotO = e.xRotO, yHead = e.yHeadRot, yHeadO = e.yHeadRotO;
        int half = size / 2;
        g.enableScissor(cx - half, cy - half, cx + half, cy + half);
        PoseStack ps = g.pose();
        ps.pushPose();
        try {
            e.yBodyRot = e.yBodyRotO = 180;                      // лицом к зрителю, как в инвентаре
            e.setYRot(180);
            e.yRotO = 180;
            e.setXRot(0);
            e.xRotO = 0;
            e.yHeadRot = e.yHeadRotO = 180;
            ps.translate(cx, cy, 150);
            ps.mulPoseMatrix(new org.joml.Matrix4f().scaling(k, k, -k));
            ps.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180));
            ps.translate(0, -eye, 0);                            // глаза — в середину значка
            Lighting.setupForEntityInInventory();
            disp.setRenderShadow(false);
            com.mojang.blaze3d.systems.RenderSystem.runAsFancy(() ->
                    disp.render(e, 0, 0, 0, 0, 1f, ps, g.bufferSource(), LightTexture.FULL_BRIGHT));
            g.flush();
            return true;
        } catch (RuntimeException ex) {
            return false;
        } finally {
            disp.setRenderShadow(true);
            Lighting.setupFor3DItems();
            ps.popPose();
            g.disableScissor();
            e.yBodyRot = yBody;
            e.yBodyRotO = yBodyO;
            e.setYRot(yRot);
            e.yRotO = yRotO;
            e.setXRot(xRot);
            e.xRotO = xRotO;
            e.yHeadRot = yHead;
            e.yHeadRotO = yHeadO;
        }
    }

    /** Самотест: что выбрано головой у моба — детали, видимость, кубы, габариты (в лог). */
    static String describe(LivingEntity e) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.getEntityRenderDispatcher().getRenderer(e) instanceof LivingEntityRenderer<?, ?> lr)) return "не LivingEntityRenderer";
        EntityModel<?> model = lr.getModel();
        StringBuilder sb = new StringBuilder(model.getClass().getName());
        Head h = choose(model, e.getType());
        if (h == PORTRAIT) return sb.append(" → PORTRAIT").toString();
        sb.append(h.whole ? " whole" : "").append(" inner=").append(h.inner);
        for (ModelPart p : h.parts) {
            sb.append("\n   part visible=").append(p.visible).append(" skip=").append(p.skipDraw)
                    .append(" pose=").append(p.x).append(',').append(p.y).append(',').append(p.z);
            p.visit(new PoseStack(), (pose, path, idx, cube) -> sb.append("\n     [").append(path).append("] cube")
                    .append(String.format(" %.1f..%.1f %.1f..%.1f %.1f..%.1f", cube.minX, cube.maxX, cube.minY, cube.maxY, cube.minZ, cube.maxZ)));
        }
        float[] all = bounds(h.parts, h.inner, false), own = bounds(h.parts, h.inner, true);
        sb.append(String.format("\n   all=%s own=%s", java.util.Arrays.toString(all), java.util.Arrays.toString(own)));
        return sb.toString();
    }

    /** Деталь со своим кубом (шеей) и ребёнком «head» — оставить детей-голову, без шеи/гривы/упряжи. */
    private static Head trim(List<ModelPart> parts) {
        if (parts.size() != 1) return new Head(parts, List.of(), false);
        boolean ownCubes = false;
        java.util.Set<String> kids = new java.util.LinkedHashSet<>();
        for (String path : paths(parts.get(0))) {
            if (path.isEmpty()) ownCubes = true;
            else kids.add(path.split("/")[1]);
        }
        if (!ownCubes || !kids.contains("head")) return new Head(parts, List.of(), false);
        List<String> inner = new ArrayList<>();
        for (String k : kids) {
            String n = k.toLowerCase(java.util.Locale.ROOT);
            if (!n.contains("mane") && !n.contains("neck") && !n.contains("saddle") && !n.contains("line")) inner.add(k);
        }
        return new Head(parts, inner, false);
    }

    private static List<String> paths(ModelPart part) {
        List<String> out = new ArrayList<>();
        part.visit(new PoseStack(), (pose, path, idx, cube) -> out.add(path));
        return out;
    }

    private static List<ModelPart> find(EntityModel<?> model) {
        try {
            if (model instanceof HeadedModel hm) return List.of(hm.getHead());
            if (model instanceof AgeableListModel<?> am) {
                if (headParts == null) headParts = ObfuscationReflectionHelper.findMethod(AgeableListModel.class, "m_5607_");
                List<ModelPart> l = new ArrayList<>();
                for (Object o : (Iterable<?>) headParts.invoke(am)) l.add((ModelPart) o);
                if (!l.isEmpty()) return l;
            }
            // голова — полем модели (свинья TFC прячет её в «тело» вместе с клыками; у кролика уши — отдельно)
            if (model instanceof net.minecraft.client.model.QuadrupedModel<?>) {
                ModelPart head = field(net.minecraft.client.model.QuadrupedModel.class, "f_103492_", model);
                if (head != null) return List.of(head);
            }
            if (model instanceof net.minecraft.client.model.RabbitModel<?>) {
                List<ModelPart> l = new ArrayList<>();
                for (String f : new String[]{"f_103523_", "f_103527_", "f_170877_", "f_170878_"}) {   // голова, нос, уши
                    ModelPart p = field(net.minecraft.client.model.RabbitModel.class, f, model);
                    if (p != null) l.add(p);
                }
                if (!l.isEmpty()) return l;
            }
            if (model instanceof HierarchicalModel<?> hm) {
                ModelPart head = byName(hm.root());
                if (head != null) return List.of(head);
            }
            for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != ModelPart.class) continue;
                    f.setAccessible(true);
                    ModelPart p = (ModelPart) f.get(model);
                    ModelPart head = p == null ? null : byName(p);
                    if (head != null) return List.of(head);
                }
            }
        } catch (Throwable t) {
            Pergament.LOG.debug("Пергамент: голова модели {} не найдена: {}", model.getClass().getName(), t.toString());
        }
        return List.of();
    }

    /** Деталь-поле модели по SRG-имени (в dev Forge сам переводит в официальное); нет — null. */
    private static ModelPart field(Class<?> owner, String srg, Object model) {
        try {
            Field f = ObfuscationReflectionHelper.findField(owner, srg);
            return (ModelPart) f.get(model);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ближайшая к корню деталь с именем «head»: путь узнаём из visit (он даёт пути к кубам), идём getChild. */
    private static ModelPart byName(ModelPart root) {
        String[] best = {null}, loose = {null};
        root.visit(new PoseStack(), (pose, path, idx, cube) -> {
            String[] seg = path.split("/");
            for (int i = 0; i < seg.length; i++) {
                if (seg[i].equalsIgnoreCase("head")) {                // у крысы TFC — «Head»
                    String p = String.join("/", java.util.Arrays.copyOfRange(seg, 0, i + 1));
                    if (best[0] == null || p.length() < best[0].length()) best[0] = p;
                    break;
                }
                String low = seg[i].toLowerCase(java.util.Locale.ROOT);
                if (low.endsWith("_head") || low.startsWith("head_")) {
                    String p = String.join("/", java.util.Arrays.copyOfRange(seg, 0, i + 1));
                    if (loose[0] == null || p.length() < loose[0].length() || (p.length() == loose[0].length() && p.compareTo(loose[0]) < 0)) {
                        loose[0] = p;
                    }
                }
            }
        });
        if (best[0] == null) best[0] = loose[0];
        if (best[0] == null) return null;
        ModelPart p = root;
        for (String s : best[0].split("/")) {
            if (s.isEmpty()) continue;
            if (!p.hasChild(s)) return null;
            p = p.getChild(s);
        }
        return p == root ? null : p;
    }

    /** Морда: габариты самого объёмного куба рисуемых деталей (плоские кубы — гребни, бороды — не в счёт). */
    private static float[] face(List<ModelPart> parts, List<String> inner) {
        float[] best = {Float.MAX_VALUE, 0, 0, -Float.MAX_VALUE, 0, 0};
        float[] vol = {0};
        for (ModelPart part : parts) {
            part.visit(new PoseStack(), (pose, path, idx, cube) -> {
                if (!inner.isEmpty() && (path.isEmpty() || !inner.contains(path.split("/")[1]))) return;
                float v = (cube.maxX - cube.minX) * (cube.maxY - cube.minY) * (cube.maxZ - cube.minZ);
                if (v <= vol[0]) return;
                vol[0] = v;
                float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                for (int c = 0; c < 8; c++) {
                    Vector4f p = new Vector4f(((c & 1) == 0 ? cube.minX : cube.maxX) / 16f,
                            ((c & 2) == 0 ? cube.minY : cube.maxY) / 16f, ((c & 4) == 0 ? cube.minZ : cube.maxZ) / 16f, 1f);
                    pose.pose().transform(p);
                    b[0] = Math.min(b[0], p.x()); b[1] = Math.min(b[1], p.y()); b[2] = Math.min(b[2], p.z());
                    b[3] = Math.max(b[3], p.x()); b[4] = Math.max(b[4], p.y()); b[5] = Math.max(b[5], p.z());
                }
                System.arraycopy(b, 0, best, 0, 6);
            });
        }
        return best;
    }

    /** Габариты кубов деталей в блоках (как их рисует render: позы деталей + координаты кубов / 16). */
    /** own — только собственные кубы рисуемых деталей (без их детей). */
    private static float[] bounds(List<ModelPart> parts, List<String> inner, boolean own) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (ModelPart part : parts) {
            part.visit(new PoseStack(), (pose, path, idx, cube) -> {
                if (!inner.isEmpty() && (path.isEmpty() || !inner.contains(path.split("/")[1]))) return;
                if (own && (inner.isEmpty() ? !path.isEmpty() : path.split("/").length != 2)) return;
                for (int c = 0; c < 8; c++) {
                    Vector4f v = new Vector4f(((c & 1) == 0 ? cube.minX : cube.maxX) / 16f,
                            ((c & 2) == 0 ? cube.minY : cube.maxY) / 16f, ((c & 4) == 0 ? cube.minZ : cube.maxZ) / 16f, 1f);
                    pose.pose().transform(v);
                    b[0] = Math.min(b[0], v.x()); b[1] = Math.min(b[1], v.y()); b[2] = Math.min(b[2], v.z());
                    b[3] = Math.max(b[3], v.x()); b[4] = Math.max(b[4], v.y()); b[5] = Math.max(b[5], v.z());
                }
            });
        }
        return b;
    }
}
