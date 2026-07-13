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

    public static final androidx.room.migration.Migration MIGRATION_1_8 = new androidx.room.migration.Migration(1, 8) {
        @Override
        public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase database) {
            // 1. Add new columns to the existing 'notifications' table
            database.execSQL("ALTER TABLE `notifications` ADD COLUMN `duplicateCount` INTEGER NOT NULL DEFAULT 1");
            database.execSQL("ALTER TABLE `notifications` ADD COLUMN `imagePath` TEXT DEFAULT NULL");

            // 2. Create the missing indexes for the 'notifications' table
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_packageName` ON `notifications` (`packageName`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_timestamp` ON `notifications` (`timestamp`)");

            // 3. Create the new 'app_rules' table
            database.execSQL("CREATE TABLE IF NOT EXISTS `app_rules` (" +
                    "`packageName` TEXT NOT NULL, " +
                    "`appName` TEXT, " +
                    "`blockAll` INTEGER NOT NULL, " +
                    "`blockKeywords` TEXT, " +
                    "`allowKeywords` TEXT, " +
                    "`isRuleEnabled` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`packageName`))");

            // 4. Create the new 'toasts' table
            database.execSQL("CREATE TABLE IF NOT EXISTS `toasts` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`packageName` TEXT, " +
                    "`appName` TEXT, " +
                    "`text` TEXT, " +
                    "`timestamp` INTEGER NOT NULL)");

            // 5. Create the indexes for the 'toasts' table
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_toasts_packageName` ON `toasts` (`packageName`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_toasts_timestamp` ON `toasts` (`timestamp`)");
        }
    };

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
                    .addMigrations(MIGRATION_1_8, MIGRATION_7_8)
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
