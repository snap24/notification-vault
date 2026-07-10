package com.zygisk_enc.notivault.database;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {NotificationEntity.class, AppRuleEntity.class, ToastEntity.class}, version = 8, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase INSTANCE;

    public abstract NotificationDao notificationDao();
    public abstract AppRuleDao appRuleDao();
    public abstract ToastDao toastDao();

    public static final androidx.room.migration.Migration MIGRATION_7_8 = new androidx.room.migration.Migration(7, 8) {
        @Override
        public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase database) {
            // Create indexes for notifications
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_packageName` ON `notifications` (`packageName`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_timestamp` ON `notifications` (`timestamp`)");

            // Create indexes for toasts
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_toasts_packageName` ON `toasts` (`packageName`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_toasts_timestamp` ON `toasts` (`timestamp`)");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "notivault_database"
                    )
                    .addMigrations(MIGRATION_7_8)
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
