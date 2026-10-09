package ru.stef.pergament.client;

import net.minecraft.client.Minecraft;
import ru.stef.pergament.Pergament;

import java.lang.reflect.Method;
import java.time.LocalTime;

/**
 * Время под миникартой: игровое, настоящее и дата. С TerraFirmaCraft — его календарь (часы, день, месяц),
 * берётся отражением, без зависимости; без TFC — время суток и номер дня ванильные.
 */
public final class GameClock {
    private static boolean probed;
    private static Object calendar;
    private static Method ticks, hourOf, minuteOf, dayOfMonth, monthOfYear;

    private GameClock() {}

    private static void probe() {
        probed = true;
        try {
            Class<?> cals = Class.forName("net.dries007.tfc.util.calendar.Calendars");
            Class<?> ical = Class.forName("net.dries007.tfc.util.calendar.ICalendar");
            calendar = cals.getField("CLIENT").get(null);
            ticks = ical.getMethod("getCalendarTicks");
            hourOf = ical.getMethod("getHourOfDay", long.class);
            minuteOf = ical.getMethod("getMinuteOfHour", long.class);
            dayOfMonth = ical.getMethod("getCalendarDayOfMonth");
            monthOfYear = ical.getMethod("getCalendarMonthOfYear");
        } catch (ClassNotFoundException e) {
            calendar = null;                                     // TFC нет — ванильное время
        } catch (Throwable t) {
            calendar = null;
            Pergament.LOG.debug("Пергамент: календарь TFC не подошёл: {}", t.toString());
        }
    }

    private static boolean tfc() {
        if (!probed) probe();
        return calendar != null;
    }

    /** Строка «игра 14:32 · сейчас 23:41». */
    public static String timeLine(Minecraft mc) {
        String real = String.format("%02d:%02d", LocalTime.now().getHour(), LocalTime.now().getMinute());
        String game;
        try {
            if (tfc()) {
                long t = (long) ticks.invoke(calendar);
                game = String.format("%02d:%02d", (int) hourOf.invoke(null, t), (int) minuteOf.invoke(null, t));
            } else {
                game = vanillaTime(mc);
            }
        } catch (Throwable e) {
            calendar = null;
            game = vanillaTime(mc);
        }
        return T.t("clock.time", game, real);
    }

    /** Строка даты: «12 июня» (TFC) или «день 57». */
    public static String dateLine(Minecraft mc) {
        try {
            if (tfc()) {
                int day = (int) dayOfMonth.invoke(calendar);
                int month = ((Enum<?>) monthOfYear.invoke(calendar)).ordinal();
                return T.t("clock.date", day, T.t("month." + month));
            }
        } catch (Throwable e) {
            calendar = null;
        }
        long days = mc.level == null ? 0 : mc.level.getDayTime() / 24000L;
        return T.t("clock.day", days + 1);
    }

    private static String vanillaTime(Minecraft mc) {
        long t = mc.level == null ? 0 : mc.level.getDayTime() % 24000L;
        int h = (int) ((t / 1000 + 6) % 24), m = (int) (t % 1000 * 60 / 1000);
        return String.format("%02d:%02d", h, m);
    }
}
