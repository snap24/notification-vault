package com.zygisk_enc.notivault.database;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {NotificationEntity.class, AppRuleEntity.class, ToastEntity.class, SearchTokenEntity.class}, version = 12, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase INSTANCE;

    public abstract NotificationDao notificationDao();
    public abstract AppRuleDao appRuleDao();
    public abstract ToastDao toastDao();
    public abstract SearchTokenDao searchTokenDao();

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
                    "`timestamp` INTEGER NOT NULL, " +
                    "`duplicateCount` INTEGER NOT NULL DEFAULT 1)");

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

    public static final androidx.room.migration.Migration MIGRATION_8_9 = new androidx.room.migration.Migration(8, 9) {
        @Override
        public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `search_tokens` (" +
                    "`tokenHash` INTEGER NOT NULL, " +
                    "`notificationId` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`tokenHash`, `notificationId`), " +
                    "FOREIGN KEY(`notificationId`) REFERENCES `notifications`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_search_tokens_tokenHash` ON `search_tokens` (`tokenHash`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_search_tokens_notificationId` ON `search_tokens` (`notificationId`)");
        }
    };

    public static final androidx.room.migration.Migration MIGRATION_9_10 = new androidx.room.migration.Migration(9, 10) {
        @Override
        public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `toasts` ADD COLUMN `duplicateCount` INTEGER NOT NULL DEFAULT 1");
        }
    };

    public static final androidx.room.migration.Migration MIGRATION_10_11 = new androidx.room.migration.Migration(10, 11) {
        @Override
        public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `notifications` ADD COLUMN `bundleId` TEXT DEFAULT NULL");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_bundleId` ON `notifications` (`bundleId`)");
        }
    };

    public static final androidx.room.migration.Migration MIGRATION_11_12 = new androidx.room.migration.Migration(11, 12) {
        @Override
        public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `notifications` ADD COLUMN `userId` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `toasts` ADD COLUMN `userId` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_userId` ON `notifications` (`userId`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_toasts_userId` ON `toasts` (`userId`)");
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
                    .addMigrations(MIGRATION_1_8, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
