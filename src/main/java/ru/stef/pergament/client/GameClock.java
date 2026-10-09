package ru.stef.pergament.client;

import net.minecraft.client.Minecraft;
import ru.stef.pergament.Pergament;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Табличка под миникартой: игровое время (день/ночь), настоящее время с поясом, дата и сезон.
 * С TerraFirmaCraft — его календарь (часы, день, месяц, сезон), берётся отражением, без зависимости;
 * без TFC — ванильное время суток и номер дня.
 */
public final class GameClock {
    /** Что показать: игровое время, день ли сейчас, настоящее время с поясом, дата, иконка даты. */
    public record Info(String game, boolean day, String real, String date, String dateIcon) {}

    private static boolean probed;
    private static Object calendar;
    private static Method ticks, hourOf, minuteOf, dayOfMonth, monthOfYear, seasonOf;

    /** Города для поясов вне России (ru); остальное — город из id пояса. */
    private static final Map<String, String> RU_CITY = Map.ofEntries(
            Map.entry("London", "Лондон"), Map.entry("Berlin", "Берлин"), Map.entry("Paris", "Париж"),
            Map.entry("Istanbul", "Стамбул"), Map.entry("Warsaw", "Варшава"), Map.entry("Kiev", "Киев"),
            Map.entry("Kyiv", "Киев"), Map.entry("Minsk", "Минск"), Map.entry("Riga", "Рига"),
            Map.entry("Tbilisi", "Тбилиси"), Map.entry("Yerevan", "Ереван"), Map.entry("Baku", "Баку"),
            Map.entry("Almaty", "Алматы"), Map.entry("Tashkent", "Ташкент"), Map.entry("New_York", "Нью-Йорк"),
            Map.entry("Los_Angeles", "Лос-Анджелес"), Map.entry("Chicago", "Чикаго"), Map.entry("Tokyo", "Токио"),
            Map.entry("Shanghai", "Шанхай"), Map.entry("Dubai", "Дубай"));

    private GameClock() {}

    private static void probe() {
        probed = true;
        try {
            Class<?> cals = Class.forName("net.dries007.tfc.util.calendar.Calendars");
            Class<?> ical = Class.forName("net.dries007.tfc.util.calendar.ICalendar");
            Class<?> month = Class.forName("net.dries007.tfc.util.calendar.Month");
            calendar = cals.getField("CLIENT").get(null);
            ticks = ical.getMethod("getCalendarTicks");
            hourOf = ical.getMethod("getHourOfDay", long.class);
            minuteOf = ical.getMethod("getMinuteOfHour", long.class);
            dayOfMonth = ical.getMethod("getCalendarDayOfMonth");
            monthOfYear = ical.getMethod("getCalendarMonthOfYear");
            seasonOf = month.getMethod("getSeason");
        } catch (ClassNotFoundException e) {
            calendar = null;                                     // TFC нет — ванильное время
        } catch (Throwable t) {
            calendar = null;
            Pergament.LOG.debug("Пергамент: календарь TFC не подошёл: {}", t.toString());
        }
    }

    public static Info info(Minecraft mc) {
        if (!probed) probe();
        int h, m;
        String date, icon;
        try {
            if (calendar == null) throw new IllegalStateException();
            long t = (long) ticks.invoke(calendar);
            h = (int) hourOf.invoke(null, t);
            m = (int) minuteOf.invoke(null, t);
            Object month = monthOfYear.invoke(calendar);
            date = T.t("clock.date", (int) dayOfMonth.invoke(calendar), T.t("month." + ((Enum<?>) month).ordinal()));
            icon = switch (((Enum<?>) seasonOf.invoke(month)).name()) {
                case "SPRING" -> "spring";
                case "SUMMER" -> "summer";
                case "FALL" -> "autumn";
                default -> "winter";
            };
        } catch (Throwable e) {
            if (calendar != null && !(e instanceof IllegalStateException)) calendar = null;   // TFC сломался — ваниль
            long dt = mc.level == null ? 0 : mc.level.getDayTime();
            long td = dt % 24000L;
            h = (int) ((td / 1000 + 6) % 24);
            m = (int) (td % 1000 * 60 / 1000);
            date = T.t("clock.day", dt / 24000L + 1);
            icon = "calendar";
        }
        ZonedDateTime now = ZonedDateTime.now();
        String real = String.format("%02d:%02d %s", now.getHour(), now.getMinute(), zone(now));
        return new Info(String.format("%02d:%02d", h, m), h >= 6 && h < 19, real, date, icon);
    }

    /** Пояс по-людски: в России — «МСК», «МСК+2»; иначе — город. */
    static String zone(ZonedDateTime now) {
        int sec = now.getOffset().getTotalSeconds();
        boolean ru = Minecraft.getInstance().getLanguageManager().getSelected().startsWith("ru");
        ZoneId id = now.getZone();
        String region = id.getId().contains("/") ? id.getId().substring(id.getId().lastIndexOf('/') + 1) : id.getId();
        if (sec % 3600 == 0) {
            int h = sec / 3600;
            boolean russian = id.getId().equals("Europe/Moscow") || (ru && h >= 2 && h <= 12);
            if (russian) {
                int d = h - 3;
                String msk = ru ? "МСК" : "MSK";
                return d == 0 ? msk : msk + (d > 0 ? "+" + d : "−" + (-d));
            }
        }
        return ru ? RU_CITY.getOrDefault(region, region.replace('_', ' ')) : region.replace('_', ' ');
    }
}
