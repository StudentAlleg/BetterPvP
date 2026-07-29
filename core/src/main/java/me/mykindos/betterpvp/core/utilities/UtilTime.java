package me.mykindos.betterpvp.core.utilities;

import lombok.CustomLog;
import lombok.Getter;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;

@CustomLog
public class UtilTime {
    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    /**
     * Check if a specified amount of time has elapsed from a certain point
     *
     * @param from     Time you are checking from
     * @param required Amount of time elapsed
     * @return Returns true if the amount of time has elapsed since the defined point
     */
    public static boolean elapsed(long from, long required) {
        return System.currentTimeMillis() - from > required;
    }

    /** Milliseconds in one server tick at the target rate of 20 TPS. */
    public static final long MILLIS_PER_TICK = 50L;

    /** Server ticks in one second at the target rate of 20 TPS. */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * Converts a duration in seconds to whole server ticks.
     *
     * <p>Game-logic durations belong in ticks rather than wall clock. Anything that only advances
     * while a tick is being processed -- cooldowns, damage delays, out-of-combat windows, charge
     * accrual -- measured in milliseconds is really measured in "however many ticks fit in that
     * many milliseconds", which is a number that shrinks as the server falls behind. The same fight
     * then plays out differently at 20 TPS and at 18, and a simulation of it is only as accurate as
     * the tick rate it happened to run at. See {@code DelayData} for the case that first surfaced
     * this.
     *
     * <p>Rounded to nearest rather than up, so a converted duration stays as close as possible to
     * the value configured instead of systematically lengthening. Any positive duration floors at
     * one tick, so a heavily reduced duration still gates for a tick rather than vanishing.
     *
     * @param seconds the duration in seconds; zero or negative yields zero ticks
     * @return the duration in server ticks
     */
    public static long toTicks(double seconds) {
        if (seconds <= 0) {
            return 0L;
        }
        return Math.max(1L, Math.round(seconds * TICKS_PER_SECOND));
    }

    /**
     * Converts a duration in milliseconds to whole server ticks.
     *
     * @see #toTicks(double)
     */
    public static long millisToTicks(long millis) {
        if (millis <= 0) {
            return 0L;
        }
        return Math.max(1L, Math.round((double) millis / MILLIS_PER_TICK));
    }

    public static double trim(double untrimmed, int d) {
        // Create a NumberFormat instance for the default locale
        NumberFormat numberFormat = NumberFormat.getNumberInstance(Locale.getDefault());

        if (numberFormat instanceof DecimalFormat) {
            // If the number format is a DecimalFormat, set the desired number of decimal places
            numberFormat.setMaximumFractionDigits(d);
        }

        // Format the untrimmed value using NumberFormat
        String formattedValue = numberFormat.format(untrimmed);

        try {
            // Parse the formatted value back to a double
            return numberFormat.parse(formattedValue).doubleValue();
        } catch (Exception e) {
            log.warn("Failed to parse value: " + formattedValue, e).submit();
        }

        // Handle parsing errors gracefully
        return untrimmed;
    }

    public static double convert(double d, TimeUnit unit, int decPoint) {
        if (unit == TimeUnit.BEST) {
            if (d < 60000L) {
                unit = TimeUnit.SECONDS;
            } else if (d < 3600000L) {
                unit = TimeUnit.MINUTES;
            } else if (d < 86400000L) {
                unit = TimeUnit.HOURS;
            } else if (d < 31536000000L) {
                unit = TimeUnit.DAYS;
            } else {
                unit = TimeUnit.YEARS;
            }
        }
        if (unit == TimeUnit.SECONDS) {
            return trim(d / 1000.0D, decPoint);
        }
        if (unit == TimeUnit.MINUTES) {
            return trim(d / 60000.0D, decPoint);
        }
        if (unit == TimeUnit.HOURS) {
            return trim(d / 3600000.0D, decPoint);
        }
        if (unit == TimeUnit.DAYS) {
            return trim(d / 86400000.0D, decPoint);
        }
        if (unit == TimeUnit.YEARS) {
            return trim(d / 31536000000.0D, decPoint);
        }
        return trim(d, decPoint);
    }

    public static String getTimeUnit2(double d) {
        if (d < 60000L) {
            return "Seconds";
        } else if (d >= 60000L && d < 3600000L) {
            return "Minutes";
        } else if (d >= 3600000L && d < 86400000L) {
            return "Hours";
        } else if (d >= 86400000L && d < 31536000000L) {
            return "Days";
        }
        return "Years";
    }


    public static String getTimeUnit(String unit) {
        return getTimeUnit(TimeUnit.getByShortVersion(unit));

    }

    public static String getTimeUnit(TimeUnit unit) {
        return switch (unit) {
            case SECONDS -> "Seconds";
            case MINUTES -> "Minutes";
            case HOURS -> "Hours";
            case DAYS -> "Days";
            case YEARS -> "Years";
            default -> "";
        };

    }

    public static String humanReadableFormat(Duration duration) {
        if (duration.isZero()) {
            return "0 seconds";
        }
        long days = duration.toDays();
        duration = duration.minus(days, ChronoUnit.DAYS);
        long hours = duration.toHours();
        duration = duration.minus(hours, ChronoUnit.HOURS);
        long minutes = duration.toMinutes();
        duration = duration.minus(minutes, ChronoUnit.MINUTES);
        long seconds = duration.getSeconds();

        StringBuilder result = new StringBuilder();
        if (days > 0) {
            result.append(days).append(days == 1 ? " day " : " days ");
        }
        if (hours > 0) {
            result.append(hours).append(hours == 1 ? " hour " : " hours ");
        }
        if (minutes > 0) {
            result.append(minutes).append(minutes == 1 ? " minute " : " minutes ");
        }
        if (seconds > 0) {
            result.append(seconds).append(seconds == 1 ? " second " : " seconds ");
        }

        String resString = result.toString().trim();
        if (resString.isBlank()) {
            return "under 1 second";
        }
        return result.toString();
    }

    public static String getTime(double d, int decPoint) {
        return UtilTime.convert(d, TimeUnit.BEST, decPoint) + " "
                + UtilTime.getTimeUnit2(d);
    }

    public static String getTime2(double d, TimeUnit unit, int decPoint) {
        if (TimeUnit.BEST.equals(unit)) {
            return getTime(d, decPoint);
        }
        return UtilTime.convert(d, unit, decPoint) + " "
                + UtilTime.getTimeUnit(unit);
    }

    /**
     * perm for -1, time unit otherwise
     * @param str "time unit"
     * @return the length in ms
     * @throws NumberFormatException see {@link Long#parseLong(String)}
     */
    public static long parseTimeString(String str) throws NumberFormatException {
        if (str.equalsIgnoreCase("perm")) {
            return -1;
        }
        return parseTimeString(str.split("\\s"));
    }

    /**
     *
     * @param str [time, unit]
     * @return the length in ms
     * @throws NumberFormatException see {@link Long#parseLong(String)}
     * @throws IllegalArgumentException see {@link TimeUnit#valueOf(String)}
     */
    public static long parseTimeString(String[] str) throws NumberFormatException, IllegalArgumentException {
        return parseTimeString(str[0], str[1]);
    }

    public static long parseTimeString(String timeStr, String unitStr) throws NumberFormatException, IllegalArgumentException {
        long time = Long.parseLong(timeStr);
        TimeUnit unit = TimeUnit.getByShortVersion(unitStr);
        if (unit == null) {
            unit  = TimeUnit.valueOf(unitStr.toUpperCase());
        }
        return applyTimeUnit(time, unit);
    }

    private static long applyTimeUnit(long time, TimeUnit unit) {
        return switch (unit) {
            case SECONDS -> time * 1000;
            case MINUTES -> time * 1000 * 60;
            case HOURS -> time * 1000 * 60 * 60;
            case DAYS -> time * 1000 * 60 * 60 * 24;
            case YEARS -> time * 1000 * 60 * 60 * 24 * 365;
            default -> time;
        };
    }

    public static String getDateTime(LocalDateTime time) {
        return dateTimeFormatter.format(time);
    }

    public static String getDateTime(long time) {
        LocalDateTime localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault());

        return dateTimeFormatter.format(localDateTime);
    }

    public enum TimeUnit {

        BEST("b"),
        YEARS("y"),
        DAYS("d"),
        HOURS("h"),
        MINUTES("m"),
        SECONDS("s");

        @Getter
        private final String shortVersion;

        TimeUnit(String shortVersion) {
            this.shortVersion = shortVersion;
        }

        public static TimeUnit getByShortVersion(String shortVersion) {
            return Arrays.stream(values()).filter(u -> u.shortVersion.equalsIgnoreCase(shortVersion)).findFirst().orElse(null);
        }

    }
}
