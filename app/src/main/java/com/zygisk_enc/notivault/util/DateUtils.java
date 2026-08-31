package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.text.format.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import com.zygisk_enc.notivault.R;

public class DateUtils {

    private static final ThreadLocal<SimpleDateFormat> DATE_GROUP_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd", Locale.US));

    private static final ThreadLocal<SimpleDateFormat> RELATIVE_DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()));

    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT_24 =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss", Locale.getDefault()));

    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT_12 =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("h:mm:ss a", Locale.getDefault()));

    private static final ThreadLocal<Date> REUSABLE_DATE =
            ThreadLocal.withInitial(Date::new);

    public static String getRelativeTimeLabel(Context context, long timestamp) {
        Calendar notifCal = Calendar.getInstance();
        notifCal.setTimeInMillis(timestamp);

        Calendar today = Calendar.getInstance();
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);

        if (isSameDay(notifCal, today)) {
            return context != null ? context.getString(R.string.today) : "Today";
        } else if (isSameDay(notifCal, yesterday)) {
            return context != null ? context.getString(R.string.yesterday) : "Yesterday";
        } else {
            Date d = REUSABLE_DATE.get();
            d.setTime(timestamp);
            return RELATIVE_DATE_FORMAT.get().format(d);
        }
    }

    public static String getRelativeTimeLabel(long timestamp) {
        return getRelativeTimeLabel(null, timestamp);
    }

    public static String getTimeString(Context context, long timestamp) {
        boolean is24Hour = DateFormat.is24HourFormat(context);
        Date d = REUSABLE_DATE.get();
        d.setTime(timestamp);
        if (is24Hour) {
            return TIME_FORMAT_24.get().format(d);
        } else {
            return TIME_FORMAT_12.get().format(d);
        }
    }

    public static String getDateGroupKey(long timestamp) {
        Date d = REUSABLE_DATE.get();
        d.setTime(timestamp);
        return DATE_GROUP_FORMAT.get().format(d);
    }

    private static boolean isSameDay(Calendar cal1, Calendar cal2) {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }
}
