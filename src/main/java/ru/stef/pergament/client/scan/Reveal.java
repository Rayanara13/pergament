package ru.stef.pergament.client.scan;

/**
 * Что игрок «осмотрел»: круг вокруг него, вытянутый по взгляду. Спереди {@code front} чанков
 * (по умолчанию 8 — как прогрузка сервера), за спиной {@code back}, между ними — плавно по углу.
 * Чистая геометрия — тестируется без игры.
 */
public final class Reveal {
    private Reveal() {}

    /**
     * Входит ли чанк со смещением (dx, dz) от чанка игрока в обзор.
     * yawDeg — поворот головы Minecraft: 0 — на юг (+z), 90 — на запад (−x).
     */
    public static boolean inside(int dx, int dz, float yawDeg, int front, int back) {
        if (dx == 0 && dz == 0) return true;
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (dist <= 1.5) return true;                       // под ногами и вплотную — всегда
        double yaw = Math.toRadians(yawDeg);
        double lx = -Math.sin(yaw), lz = Math.cos(yaw);       // куда смотрит игрок
        double cos = (dx * lx + dz * lz) / dist;            // 1 — прямо по взгляду, −1 — за спиной
        double radius = back + (front - back) * (1 + cos) / 2;
        return dist <= radius + 0.5;
    }
}
