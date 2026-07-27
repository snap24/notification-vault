package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.text.format.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class DateUtils {

    public static String getRelativeTimeLabel(long timestamp) {
        Calendar notifCal = Calendar.getInstance();
        notifCal.setTimeInMillis(timestamp);

        Calendar today = Calendar.getInstance();
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);

        if (isSameDay(notifCal, today)) {
            return "Today";
        } else if (isSameDay(notifCal, yesterday)) {
            return "Yesterday";
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault());
            return sdf.format(new Date(timestamp));
        }
    }

    public static String getTimeString(Context context, long timestamp) {
        boolean is24Hour = DateFormat.is24HourFormat(context);
        SimpleDateFormat sdf;
        if (is24Hour) {
            sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        } else {
            sdf = new SimpleDateFormat("h:mm:ss a", Locale.getDefault());
        }
        return sdf.format(new Date(timestamp));
    }

    public static String getDateGroupKey(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    private static boolean isSameDay(Calendar cal1, Calendar cal2) {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }
}
