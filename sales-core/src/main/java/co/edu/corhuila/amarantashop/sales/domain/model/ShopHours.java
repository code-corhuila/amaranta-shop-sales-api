package co.edu.corhuila.amarantashop.sales.domain.model;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The shop is open Monday to Saturday, 08:00-12:00 and 14:00-18:00 in Bogota time, and closed on Sunday (spec
 * DP-4, rule RN-07). The start of a window is inclusive and its end exclusive. The hours are those of
 * Bogota whatever the zone of the server: this closes the monolith's gap G-3, which read the server's
 * local time.
 */
public final class ShopHours {

    public static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private static final int MORNING_START = 8 * 60;
    private static final int MORNING_END = 12 * 60;
    private static final int AFTERNOON_START = 14 * 60;
    private static final int AFTERNOON_END = 18 * 60;

    public boolean isOpenAt(Instant at) {
        ZonedDateTime local = at.atZone(ZONE);
        if (local.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        int minuteOfDay = local.getHour() * 60 + local.getMinute();
        return (minuteOfDay >= MORNING_START && minuteOfDay < MORNING_END)
                || (minuteOfDay >= AFTERNOON_START && minuteOfDay < AFTERNOON_END);
    }
}
