package org.telegram.messenger;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Created by gzq on 15/11/24.
 */
public class SPAPollingUtils {

    public static void startPollingService(Context context,
                                           int seconds,
                                           Class<?> cls,
                                           String action) {
        AlarmManager alarmManager = (AlarmManager) context
                .getSystemService(Context.ALARM_SERVICE);

        Intent intent = new Intent(context, cls);
        intent.setAction(action);
        PendingIntent pendingIntent = PendingIntent.getService(context, 0,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);

        long triggerAtTime = SystemClock.elapsedRealtime();

        alarmManager.setRepeating(AlarmManager.ELAPSED_REALTIME, triggerAtTime,
                seconds * 1000, pendingIntent);
    }

    public static void stopPollingService(Context context, Class<?> cls, String action) {
        AlarmManager alarmManager = (AlarmManager) context
                .getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, cls);
        intent.setAction(action);
        PendingIntent pendingIntent = PendingIntent.getService(context, 0,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);
        alarmManager.cancel(pendingIntent);
    }
}
