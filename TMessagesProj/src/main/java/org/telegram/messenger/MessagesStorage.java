/*
 * This is the source code of Telegram for Android v. 1.3.2.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.SparseArray;
import android.util.SparseIntArray;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.query.BotQuery;
import org.telegram.messenger.query.SharedMediaQuery;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLClassStore;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

public class MessagesStorage {
    private DispatchQueue storageQueue = new DispatchQueue("storageQueue");
    private SQLiteDatabase database;
    private File cacheFile;
    public static int lastDateValue = 0;
    public static int lastPtsValue = 0;
    public static int lastQtsValue = 0;
    public static int lastSeqValue = 0;
    public static int lastSecretVersion = 0;
    public static byte[] secretPBytes = null;
    public static int secretG = 0;

    private int lastSavedSeq = 0;
    private int lastSavedPts = 0;
    private int lastSavedDate = 0;
    private int lastSavedQts = 0;

    private static volatile MessagesStorage Instance = null;
    public static MessagesStorage getInstance() {
        MessagesStorage localInstance = Instance;
        if (localInstance == null) {
            synchronized (MessagesStorage.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new MessagesStorage();
                }
            }
        }
        return localInstance;
    }

    public MessagesStorage() {
        storageQueue.setPriority(Thread.MAX_PRIORITY);
        openDatabase();
    }

    public SQLiteDatabase getDatabase() {
        return database;
    }

    public DispatchQueue getStorageQueue() {
        return storageQueue;
    }

    public void openDatabase() {
        cacheFile = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache4.db");

        boolean createTable = false;
        //cacheFile.delete();
        if (!cacheFile.exists()) {
            createTable = true;
        }
        try {
            database = new SQLiteDatabase(cacheFile.getPath());
            database.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();
            database.executeFast("PRAGMA temp_store = 1").stepThis().dispose();
            if (createTable) {
                database.executeFast("CREATE TABLE channel_group(uid INTEGER, start INTEGER, end INTEGER, count INTEGER, PRIMARY KEY(uid, start));").stepThis().dispose();

                database.executeFast("CREATE TABLE messages_holes(uid INTEGER, start INTEGER, end INTEGER, PRIMARY KEY(uid, start));").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS uid_end_messages_holes ON messages_holes(uid, end);").stepThis().dispose();
                database.executeFast("CREATE TABLE messages_imp_holes(uid INTEGER, start INTEGER, end INTEGER, PRIMARY KEY(uid, start));").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS uid_end_messages_imp_holes ON messages_imp_holes(uid, end);").stepThis().dispose();

                database.executeFast("CREATE TABLE media_holes_v2(uid INTEGER, type INTEGER, start INTEGER, end INTEGER, PRIMARY KEY(uid, type, start));").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS uid_end_media_holes_v2 ON media_holes_v2(uid, type, end);").stepThis().dispose();

                database.executeFast("CREATE TABLE messages(mid INTEGER PRIMARY KEY, uid INTEGER, read_state INTEGER, send_state INTEGER, date INTEGER, data BLOB, out INTEGER, ttl INTEGER, media INTEGER, replydata BLOB, imp INTEGER)").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS uid_mid_idx_messages ON messages(uid, mid);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS uid_date_mid_idx_messages ON messages(uid, date, mid);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS uid_mid_idx_imp_messages ON messages(uid, mid, imp) WHERE imp = 1;").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS uid_date_mid_imp_idx_messages ON messages(uid, date, mid, imp) WHERE imp = 1;").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS mid_out_idx_messages ON messages(mid, out);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS task_idx_messages ON messages(uid, out, read_state, ttl, date, send_state);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS send_state_idx_messages ON messages(mid, send_state, date) WHERE mid < 0 AND send_state = 1;").stepThis().dispose();

                database.executeFast("CREATE TABLE download_queue(uid INTEGER, type INTEGER, date INTEGER, data BLOB, PRIMARY KEY (uid, type));").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS type_date_idx_download_queue ON download_queue(type, date);").stepThis().dispose();

                database.executeFast("CREATE TABLE user_phones_v6(uid INTEGER, phone TEXT, sphone TEXT, deleted INTEGER, PRIMARY KEY (uid, phone))").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS sphone_deleted_idx_user_phones ON user_phones_v6(sphone, deleted);").stepThis().dispose();

                database.executeFast("CREATE TABLE dialogs(did INTEGER PRIMARY KEY, date INTEGER, unread_count INTEGER, last_mid INTEGER, inbox_max INTEGER, outbox_max INTEGER, last_mid_i INTEGER, unread_count_i INTEGER, pts INTEGER, date_i INTEGER)").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS date_idx_dialogs ON dialogs(date);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS last_mid_idx_dialogs ON dialogs(last_mid);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS unread_count_idx_dialogs ON dialogs(unread_count);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS last_mid_i_idx_dialogs ON dialogs(last_mid_i);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS unread_count_i_idx_dialogs ON dialogs(unread_count_i);").stepThis().dispose();

                database.executeFast("CREATE TABLE randoms(random_id INTEGER, mid INTEGER, PRIMARY KEY (random_id, mid))").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS mid_idx_randoms ON randoms(mid);").stepThis().dispose();

                database.executeFast("CREATE TABLE enc_tasks_v2(mid INTEGER PRIMARY KEY, date INTEGER)").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS date_idx_enc_tasks_v2 ON enc_tasks_v2(date);").stepThis().dispose();

                database.executeFast("CREATE TABLE messages_seq(mid INTEGER PRIMARY KEY, seq_in INTEGER, seq_out INTEGER);").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS seq_idx_messages_seq ON messages_seq(seq_in, seq_out);").stepThis().dispose();

                database.executeFast("CREATE TABLE params(id INTEGER PRIMARY KEY, seq INTEGER, pts INTEGER, date INTEGER, qts INTEGER, lsv INTEGER, sg INTEGER, pbytes BLOB)").stepThis().dispose();
                database.executeFast("INSERT INTO params VALUES(1, 0, 0, 0, 0, 0, 0, NULL)").stepThis().dispose();

                database.executeFast("CREATE TABLE media_v2(mid INTEGER PRIMARY KEY, uid INTEGER, date INTEGER, type INTEGER, data BLOB)").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS uid_mid_type_date_idx_media ON media_v2(uid, mid, type, date);").stepThis().dispose();

                database.executeFast("CREATE TABLE bot_keyboard(uid INTEGER PRIMARY KEY, mid INTEGER, info BLOB)").stepThis().dispose();
                database.executeFast("CREATE INDEX IF NOT EXISTS bot_keyboard_idx_mid ON bot_keyboard(mid);").stepThis().dispose();

                database.executeFast("CREATE TABLE users(uid INTEGER PRIMARY KEY, name TEXT, status INTEGER, data BLOB)").stepThis().dispose();
                database.executeFast("CREATE TABLE chats(uid INTEGER PRIMARY KEY, name TEXT, data BLOB)").stepThis().dispose();
                database.executeFast("CREATE TABLE enc_chats(uid INTEGER PRIMARY KEY, user INTEGER, name TEXT, data BLOB, g BLOB, authkey BLOB, ttl INTEGER, layer INTEGER, seq_in INTEGER, seq_out INTEGER, use_count INTEGER, exchange_id INTEGER, key_date INTEGER, fprint INTEGER, fauthkey BLOB, khash BLOB)").stepThis().dispose();
                database.executeFast("CREATE TABLE chat_settings_v2(uid INTEGER PRIMARY KEY, info BLOB)").stepThis().dispose();
                database.executeFast("CREATE TABLE contacts(uid INTEGER PRIMARY KEY, mutual INTEGER)").stepThis().dispose();
                database.executeFast("CREATE TABLE pending_read(uid INTEGER PRIMARY KEY, max_id INTEGER)").stepThis().dispose();
                database.executeFast("CREATE TABLE wallpapers(uid INTEGER PRIMARY KEY, data BLOB)").stepThis().dispose();
                database.executeFast("CREATE TABLE user_photos(uid INTEGER, id INTEGER, data BLOB, PRIMARY KEY (uid, id))").stepThis().dispose();
                database.executeFast("CREATE TABLE blocked_users(uid INTEGER PRIMARY KEY)").stepThis().dispose();
                database.executeFast("CREATE TABLE dialog_settings(did INTEGER PRIMARY KEY, flags INTEGER);").stepThis().dispose();
                database.executeFast("CREATE TABLE web_recent_v3(id TEXT, type INTEGER, image_url TEXT, thumb_url TEXT, local_url TEXT, width INTEGER, height INTEGER, size INTEGER, date INTEGER, PRIMARY KEY (id, type));").stepThis().dispose();
                database.executeFast("CREATE TABLE stickers_v2(id INTEGER PRIMARY KEY, data BLOB, date INTEGER, hash TEXT);").stepThis().dispose();
                database.executeFast("CREATE TABLE hashtag_recent_v2(id TEXT PRIMARY KEY, date INTEGER);").stepThis().dispose();
                database.executeFast("CREATE TABLE webpage_pending(id INTEGER, mid INTEGER, PRIMARY KEY (id, mid));").stepThis().dispose();
                database.executeFast("CREATE TABLE user_contacts_v6(uid INTEGER PRIMARY KEY, fname TEXT, sname TEXT)").stepThis().dispose();
                database.executeFast("CREATE TABLE sent_files_v2(uid TEXT, type INTEGER, data BLOB, PRIMARY KEY (uid, type))").stepThis().dispose();
                database.executeFast("CREATE TABLE search_recent(did INTEGER PRIMARY KEY, date INTEGER);").stepThis().dispose();
                database.executeFast("CREATE TABLE media_counts_v2(uid INTEGER, type INTEGER, count INTEGER, PRIMARY KEY(uid, type))").stepThis().dispose();
                database.executeFast("CREATE TABLE keyvalue(id TEXT PRIMARY KEY, value TEXT)").stepThis().dispose();
                database.executeFast("CREATE TABLE bot_info(uid INTEGER PRIMARY KEY, info BLOB)").stepThis().dispose();

                // SPA
                database.executeFast("CREATE TABLE spa_user_with_weight(uid INTEGER PRIMARY KEY, weight INTEGER DEFAULT 5)").stepThis().dispose();

                //version
                database.executeFast("PRAGMA user_version = 23").stepThis().dispose();

                //database.executeFast("CREATE TABLE secret_holes(uid INTEGER, seq_in INTEGER, seq_out INTEGER, data BLOB, PRIMARY KEY (uid, seq_in, seq_out));").stepThis().dispose();
                //database.executeFast("CREATE TABLE attach_data(uid INTEGER, id INTEGER, data BLOB, PRIMARY KEY (uid, id))").stepThis().dispose();
            } else {
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT seq, pts, date, qts, lsv, sg, pbytes FROM params WHERE id = 1");
                    if (cursor.next()) {
                        lastSeqValue = cursor.intValue(0);
                        lastPtsValue = cursor.intValue(1);
                        lastDateValue = cursor.intValue(2);
                        lastQtsValue = cursor.intValue(3);
                        lastSecretVersion = cursor.intValue(4);
                        secretG = cursor.intValue(5);
                        if (cursor.isNull(6)) {
                            secretPBytes = null;
                        } else {
                            secretPBytes = cursor.byteArrayValue(6);
                            if (secretPBytes != null && secretPBytes.length == 1) {
                                secretPBytes = null;
                            }
                        }
                    }
                    cursor.dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                    try {
                        database.executeFast("CREATE TABLE IF NOT EXISTS params(id INTEGER PRIMARY KEY, seq INTEGER, pts INTEGER, date INTEGER, qts INTEGER, lsv INTEGER, sg INTEGER, pbytes BLOB)").stepThis().dispose();
                        database.executeFast("INSERT INTO params VALUES(1, 0, 0, 0, 0, 0, 0, NULL)").stepThis().dispose();
                    } catch (Exception e2) {
                        FileLog.e("tmessages", e2);
                    }
                }
                int version = database.executeInt("PRAGMA user_version");
                if (version < 23) {
                    updateDbToLastVersion(version);
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        loadUnreadMessages();
    }

    public void updateDbToLastVersion(final int currentVersion) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    int version = currentVersion;
                    if (version < 4) {
                        database.executeFast("CREATE TABLE IF NOT EXISTS user_photos(uid INTEGER, id INTEGER, data BLOB, PRIMARY KEY (uid, id))").stepThis().dispose();

                        database.executeFast("DROP INDEX IF EXISTS read_state_out_idx_messages;").stepThis().dispose();
                        database.executeFast("DROP INDEX IF EXISTS ttl_idx_messages;").stepThis().dispose();
                        database.executeFast("DROP INDEX IF EXISTS date_idx_messages;").stepThis().dispose();

                        database.executeFast("CREATE INDEX IF NOT EXISTS mid_out_idx_messages ON messages(mid, out);").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS task_idx_messages ON messages(uid, out, read_state, ttl, date, send_state);").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS uid_date_mid_idx_messages ON messages(uid, date, mid);").stepThis().dispose();

                        database.executeFast("CREATE TABLE IF NOT EXISTS user_contacts_v6(uid INTEGER PRIMARY KEY, fname TEXT, sname TEXT)").stepThis().dispose();
                        database.executeFast("CREATE TABLE IF NOT EXISTS user_phones_v6(uid INTEGER, phone TEXT, sphone TEXT, deleted INTEGER, PRIMARY KEY (uid, phone))").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS sphone_deleted_idx_user_phones ON user_phones_v6(sphone, deleted);").stepThis().dispose();

                        database.executeFast("CREATE INDEX IF NOT EXISTS mid_idx_randoms ON randoms(mid);").stepThis().dispose();

                        database.executeFast("CREATE TABLE IF NOT EXISTS sent_files_v2(uid TEXT, type INTEGER, data BLOB, PRIMARY KEY (uid, type))").stepThis().dispose();

                        database.executeFast("CREATE TABLE IF NOT EXISTS blocked_users(uid INTEGER PRIMARY KEY)").stepThis().dispose();

                        database.executeFast("CREATE TABLE IF NOT EXISTS download_queue(uid INTEGER, type INTEGER, date INTEGER, data BLOB, PRIMARY KEY (uid, type));").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS type_date_idx_download_queue ON download_queue(type, date);").stepThis().dispose();

                        database.executeFast("CREATE TABLE IF NOT EXISTS dialog_settings(did INTEGER PRIMARY KEY, flags INTEGER);").stepThis().dispose();

                        database.executeFast("CREATE INDEX IF NOT EXISTS send_state_idx_messages ON messages(mid, send_state, date) WHERE mid < 0 AND send_state = 1;").stepThis().dispose();

                        database.executeFast("CREATE INDEX IF NOT EXISTS unread_count_idx_dialogs ON dialogs(unread_count);").stepThis().dispose();

                        database.executeFast("UPDATE messages SET send_state = 2 WHERE mid < 0 AND send_state = 1").stepThis().dispose();

                        storageQueue.postRunnable(new Runnable() {
                            @Override
                            public void run() {
                                ArrayList<Integer> ids = new ArrayList<>();
                                SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
                                Map<String, ?> values = preferences.getAll();
                                for (Map.Entry<String, ?> entry : values.entrySet()) {
                                    String key = entry.getKey();
                                    if (key.startsWith("notify2_")) {
                                        Integer value = (Integer) entry.getValue();
                                        if (value == 2) {
                                            key = key.replace("notify2_", "");
                                            try {
                                                ids.add(Integer.parseInt(key));
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    }
                                }
                                try {
                                    database.beginTransaction();
                                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO dialog_settings VALUES(?, ?)");
                                    for (Integer id : ids) {
                                        state.requery();
                                        state.bindLong(1, id);
                                        state.bindInteger(2, 1);
                                        state.step();
                                    }
                                    state.dispose();
                                    database.commitTransaction();
                                } catch (Exception e) {
                                    FileLog.e("tmessages", e);
                                }
                            }
                        });
                        database.executeFast("PRAGMA user_version = 4").stepThis().dispose();
                        version = 4;
                    }
                    if (version == 4) {
                        database.executeFast("CREATE TABLE IF NOT EXISTS enc_tasks_v2(mid INTEGER PRIMARY KEY, date INTEGER)").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS date_idx_enc_tasks_v2 ON enc_tasks_v2(date);").stepThis().dispose();
                        database.beginTransaction();
                        SQLiteCursor cursor = database.queryFinalized("SELECT date, data FROM enc_tasks WHERE 1");
                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO enc_tasks_v2 VALUES(?, ?)");
                        if (cursor.next()) {
                            int date = cursor.intValue(0);
                            int length;
                            NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(1));
                            if ((length = cursor.byteBufferValue(1, data)) != 0) {
                                for (int a = 0; a < length / 4; a++) {
                                    state.requery();
                                    state.bindInteger(1, data.readInt32(false));
                                    state.bindInteger(2, date);
                                    state.step();
                                }
                            }
                            data.reuse();
                        }
                        state.dispose();
                        cursor.dispose();
                        database.commitTransaction();

                        database.executeFast("DROP INDEX IF EXISTS date_idx_enc_tasks;").stepThis().dispose();
                        database.executeFast("DROP TABLE IF EXISTS enc_tasks;").stepThis().dispose();

                        database.executeFast("ALTER TABLE messages ADD COLUMN media INTEGER default 0").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 6").stepThis().dispose();
                        version = 6;
                    }
                    if (version == 6) {
                        database.executeFast("CREATE TABLE IF NOT EXISTS messages_seq(mid INTEGER PRIMARY KEY, seq_in INTEGER, seq_out INTEGER);").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS seq_idx_messages_seq ON messages_seq(seq_in, seq_out);").stepThis().dispose();
                        database.executeFast("ALTER TABLE enc_chats ADD COLUMN layer INTEGER default 0").stepThis().dispose();
                        database.executeFast("ALTER TABLE enc_chats ADD COLUMN seq_in INTEGER default 0").stepThis().dispose();
                        database.executeFast("ALTER TABLE enc_chats ADD COLUMN seq_out INTEGER default 0").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 7").stepThis().dispose();
                        version = 7;
                    }
                    /*if (version == 7 && version < 8) {
                        database.executeFast("CREATE TABLE IF NOT EXISTS secret_holes(uid INTEGER, seq_in INTEGER, seq_out INTEGER, data BLOB, PRIMARY KEY (uid, seq_in, seq_out));").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 8").stepThis().dispose();
                        version = 8;
                    }*/
                    if (version == 7 || version == 8 || version == 9) {
                        database.executeFast("ALTER TABLE enc_chats ADD COLUMN use_count INTEGER default 0").stepThis().dispose();
                        database.executeFast("ALTER TABLE enc_chats ADD COLUMN exchange_id INTEGER default 0").stepThis().dispose();
                        database.executeFast("ALTER TABLE enc_chats ADD COLUMN key_date INTEGER default 0").stepThis().dispose();
                        database.executeFast("ALTER TABLE enc_chats ADD COLUMN fprint INTEGER default 0").stepThis().dispose();
                        database.executeFast("ALTER TABLE enc_chats ADD COLUMN fauthkey BLOB default NULL").stepThis().dispose();
                        database.executeFast("ALTER TABLE enc_chats ADD COLUMN khash BLOB default NULL").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 10").stepThis().dispose();
                        version = 10;
                    }
                    if (version == 10) {
                        database.executeFast("CREATE TABLE IF NOT EXISTS web_recent_v3(id TEXT, type INTEGER, image_url TEXT, thumb_url TEXT, local_url TEXT, width INTEGER, height INTEGER, size INTEGER, date INTEGER, PRIMARY KEY (id, type));").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 11").stepThis().dispose();
                        version = 11;
                    }
                    if (version == 11) {
                        version = 12;
                    }
                    if (version == 12) {
                        database.executeFast("DROP INDEX IF EXISTS uid_mid_idx_media;").stepThis().dispose();
                        database.executeFast("DROP INDEX IF EXISTS mid_idx_media;").stepThis().dispose();
                        database.executeFast("DROP INDEX IF EXISTS uid_date_mid_idx_media;").stepThis().dispose();
                        database.executeFast("DROP TABLE IF EXISTS media;").stepThis().dispose();
                        database.executeFast("DROP TABLE IF EXISTS media_counts;").stepThis().dispose();

                        database.executeFast("CREATE TABLE IF NOT EXISTS media_v2(mid INTEGER PRIMARY KEY, uid INTEGER, date INTEGER, type INTEGER, data BLOB)").stepThis().dispose();
                        database.executeFast("CREATE TABLE IF NOT EXISTS media_counts_v2(uid INTEGER, type INTEGER, count INTEGER, PRIMARY KEY(uid, type))").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS uid_mid_type_date_idx_media ON media_v2(uid, mid, type, date);").stepThis().dispose();

                        database.executeFast("CREATE TABLE IF NOT EXISTS keyvalue(id TEXT PRIMARY KEY, value TEXT)").stepThis().dispose();

                        database.executeFast("PRAGMA user_version = 13").stepThis().dispose();
                        version = 13;
                    }
                    if (version == 13) {
                        database.executeFast("ALTER TABLE messages ADD COLUMN replydata BLOB default NULL").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 14").stepThis().dispose();
                        version = 14;
                    }
                    if (version == 14) {
                        database.executeFast("CREATE TABLE IF NOT EXISTS hashtag_recent_v2(id TEXT PRIMARY KEY, date INTEGER);").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 15").stepThis().dispose();
                        version = 15;
                    }
                    if (version == 15) {
                        database.executeFast("CREATE TABLE IF NOT EXISTS webpage_pending(id INTEGER, mid INTEGER, PRIMARY KEY (id, mid));").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 16").stepThis().dispose();
                        version = 16;
                    }
                    if (version == 16) {
                        database.executeFast("ALTER TABLE dialogs ADD COLUMN inbox_max INTEGER default 0").stepThis().dispose();
                        database.executeFast("ALTER TABLE dialogs ADD COLUMN outbox_max INTEGER default 0").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 17").stepThis().dispose();
                        version = 17;
                    }
                    if (version == 17) {
                        database.executeFast("CREATE TABLE bot_info(uid INTEGER PRIMARY KEY, info BLOB)").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 18").stepThis().dispose();
                        version = 18;
                    }
                    if (version == 18) {
                        database.executeFast("DROP TABLE IF EXISTS stickers;").stepThis().dispose();
                        database.executeFast("CREATE TABLE IF NOT EXISTS stickers_v2(id INTEGER PRIMARY KEY, data BLOB, date INTEGER, hash TEXT);").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 19").stepThis().dispose();
                        version = 19;
                    }
                    if (version == 19) {
                        database.executeFast("CREATE TABLE IF NOT EXISTS bot_keyboard(uid INTEGER PRIMARY KEY, mid INTEGER, info BLOB)").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS bot_keyboard_idx_mid ON bot_keyboard(mid);").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 20").stepThis().dispose();
                        version = 20;
                    }
                    if (version == 20) {
                        database.executeFast("CREATE TABLE search_recent(did INTEGER PRIMARY KEY, date INTEGER);").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 21").stepThis().dispose();
                        version = 21;
                    }
                    if (version == 21) {
                        database.executeFast("CREATE TABLE IF NOT EXISTS chat_settings_v2(uid INTEGER PRIMARY KEY, info BLOB)").stepThis().dispose();

                        SQLiteCursor cursor = database.queryFinalized("SELECT uid, participants FROM chat_settings WHERE uid < 0");
                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO chat_settings_v2 VALUES(?, ?)");
                        while (cursor.next()) {
                            int chat_id = cursor.intValue(0);
                            NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(1));
                            if (data != null && cursor.byteBufferValue(1, data) != 0) {
                                TLRPC.ChatParticipants participants = TLRPC.ChatParticipants.TLdeserialize(data, data.readInt32(false), false);
                                if (participants != null) {
                                    TLRPC.TL_chatFull chatFull = new TLRPC.TL_chatFull();
                                    chatFull.id = chat_id;
                                    chatFull.chat_photo = new TLRPC.TL_photoEmpty();
                                    chatFull.notify_settings = new TLRPC.TL_peerNotifySettingsEmpty();
                                    chatFull.exported_invite = new TLRPC.TL_chatInviteEmpty();
                                    chatFull.participants = participants;
                                    NativeByteBuffer data2 = new NativeByteBuffer(chatFull.getObjectSize());
                                    chatFull.serializeToStream(data2);
                                    state.requery();
                                    state.bindInteger(1, chat_id);
                                    state.bindByteBuffer(2, data2);
                                    state.step();
                                    data2.reuse();
                                }
                            }
                            data.reuse();
                        }
                        state.dispose();
                        cursor.dispose();

                        database.executeFast("DROP TABLE IF EXISTS chat_settings;").stepThis().dispose();
                        database.executeFast("ALTER TABLE dialogs ADD COLUMN last_mid_i INTEGER default 0").stepThis().dispose();
                        database.executeFast("ALTER TABLE dialogs ADD COLUMN unread_count_i INTEGER default 0").stepThis().dispose();
                        database.executeFast("ALTER TABLE dialogs ADD COLUMN pts INTEGER default 0").stepThis().dispose();
                        database.executeFast("ALTER TABLE dialogs ADD COLUMN date_i INTEGER default 0").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS last_mid_i_idx_dialogs ON dialogs(last_mid_i);").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS unread_count_i_idx_dialogs ON dialogs(unread_count_i);").stepThis().dispose();
                        database.executeFast("ALTER TABLE messages ADD COLUMN imp INTEGER default 0").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS uid_mid_idx_imp_messages ON messages(uid, mid, imp) WHERE imp = 1;").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS uid_date_mid_imp_idx_messages ON messages(uid, date, mid, imp) WHERE imp = 1;").stepThis().dispose();
                        database.executeFast("CREATE TABLE IF NOT EXISTS channel_group(uid INTEGER, start INTEGER, end INTEGER, count INTEGER, PRIMARY KEY(uid, start));").stepThis().dispose();
                        database.executeFast("CREATE TABLE IF NOT EXISTS messages_holes(uid INTEGER, start INTEGER, end INTEGER, PRIMARY KEY(uid, start));").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS uid_end_messages_holes ON messages_holes(uid, end);").stepThis().dispose();
                        database.executeFast("CREATE TABLE IF NOT EXISTS messages_imp_holes(uid INTEGER, start INTEGER, end INTEGER, PRIMARY KEY(uid, start));").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS uid_end_messages_imp_holes ON messages_imp_holes(uid, end);").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 22").stepThis().dispose();
                        version = 22;
                    }
                    if (version == 22) {
                        database.executeFast("CREATE TABLE IF NOT EXISTS media_holes_v2(uid INTEGER, type INTEGER, start INTEGER, end INTEGER, PRIMARY KEY(uid, type, start));").stepThis().dispose();
                        database.executeFast("CREATE INDEX IF NOT EXISTS uid_end_media_holes_v2 ON media_holes_v2(uid, type, end);").stepThis().dispose();
                        database.executeFast("PRAGMA user_version = 23").stepThis().dispose();
                        //version = 23;
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void cleanUp(final boolean isLogin) {
        storageQueue.cleanupQueue();
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                lastDateValue = 0;
                lastSeqValue = 0;
                lastPtsValue = 0;
                lastQtsValue = 0;
                lastSecretVersion = 0;

                lastSavedSeq = 0;
                lastSavedPts = 0;
                lastSavedDate = 0;
                lastSavedQts = 0;

                secretPBytes = null;
                secretG = 0;
                if (database != null) {
                    database.close();
                    database = null;
                }
                if (cacheFile != null) {
                    cacheFile.delete();
                    cacheFile = null;
                }
                openDatabase();
                if (isLogin) {
                    Utilities.stageQueue.postRunnable(new Runnable() {
                        @Override
                        public void run() {
                            MessagesController.getInstance().getDifference();
                        }
                    });
                }
            }
        });
    }

    public void saveSecretParams(final int lsv, final int sg, final byte[] pbytes) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLitePreparedStatement state = database.executeFast("UPDATE params SET lsv = ?, sg = ?, pbytes = ? WHERE id = 1");
                    state.bindInteger(1, lsv);
                    state.bindInteger(2, sg);
                    NativeByteBuffer data = new NativeByteBuffer(pbytes != null ? pbytes.length : 1);
                    if (pbytes != null) {
                        data.writeBytes(pbytes);
                    }
                    state.bindByteBuffer(3, data);
                    state.step();
                    state.dispose();
                    data.reuse();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void saveChannelPts(final int channelId, final int pts) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLitePreparedStatement state = database.executeFast("UPDATE dialogs SET pts = ? WHERE did = ?");
                    state.bindInteger(1, pts);
                    state.bindInteger(2, -channelId);
                    state.step();
                    state.dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void saveDiffParams(final int seq, final int pts, final int date, final int qts) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (lastSavedSeq == seq && lastSavedPts == pts && lastSavedDate == date && lastQtsValue == qts) {
                        return;
                    }
                    SQLitePreparedStatement state = database.executeFast("UPDATE params SET seq = ?, pts = ?, date = ?, qts = ? WHERE id = 1");
                    state.bindInteger(1, seq);
                    state.bindInteger(2, pts);
                    state.bindInteger(3, date);
                    state.bindInteger(4, qts);
                    state.step();
                    state.dispose();
                    lastSavedSeq = seq;
                    lastSavedPts = pts;
                    lastSavedDate = date;
                    lastSavedQts = qts;
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void setDialogFlags(final long did, final long flags) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast(String.format(Locale.US, "REPLACE INTO dialog_settings VALUES(%d, %d)", did, flags)).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void loadUnreadMessages() {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    final HashMap<Long, Integer> pushDialogs = new HashMap<>();
                    SQLiteCursor cursor = database.queryFinalized("SELECT d.did, d.unread_count, s.flags FROM dialogs as d LEFT JOIN dialog_settings as s ON d.did = s.did WHERE d.unread_count != 0");
                    StringBuilder ids = new StringBuilder();
                    while (cursor.next()) {
                        if (cursor.isNull(2) || cursor.intValue(2) != 1) {
                            long did = cursor.longValue(0);
                            int count = cursor.intValue(1);
                            pushDialogs.put(did, count);
                            if (ids.length() != 0) {
                                ids.append(",");
                            }
                            ids.append(did);
                        }
                    }
                    cursor.dispose();

                    final ArrayList<TLRPC.Message> messages = new ArrayList<>();
                    final ArrayList<TLRPC.User> users = new ArrayList<>();
                    final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
                    final ArrayList<TLRPC.EncryptedChat> encryptedChats = new ArrayList<>();
                    if (ids.length() > 0) {
                        ArrayList<Integer> userIds = new ArrayList<>();
                        ArrayList<Integer> chatIds = new ArrayList<>();
                        ArrayList<Integer> encryptedChatIds = new ArrayList<>();

                        cursor = database.queryFinalized("SELECT read_state, data, send_state, mid, date, uid FROM messages WHERE uid IN (" + ids.toString() + ") AND out = 0 AND read_state IN(0,2) ORDER BY date DESC LIMIT 50");
                        while (cursor.next()) {
                            NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(1));
                            if (data != null && cursor.byteBufferValue(1, data) != 0) {
                                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                MessageObject.setUnreadFlags(message, cursor.intValue(0));
                                message.id = cursor.intValue(3);
                                message.date = cursor.intValue(4);
                                message.dialog_id = cursor.longValue(5);
                                messages.add(message);

                                int lower_id = (int)message.dialog_id;
                                int high_id = (int)(message.dialog_id >> 32);

                                if (lower_id != 0) {
                                    if (lower_id < 0) {
                                        if (!chatIds.contains(-lower_id)) {
                                            chatIds.add(-lower_id);
                                        }
                                    } else {
                                        if (!userIds.contains(lower_id)) {
                                            userIds.add(lower_id);
                                        }
                                    }
                                } else {
                                    if (!encryptedChatIds.contains(high_id)) {
                                        encryptedChatIds.add(high_id);
                                    }
                                }

                                if (!userIds.contains(message.from_id)) {
                                    userIds.add(message.from_id);
                                }
                                if (message.action != null && message.action.user_id != 0 && !userIds.contains(message.action.user_id)) {
                                    userIds.add(message.action.user_id);
                                }
                                if (message.media != null && message.media.user_id != 0 && !userIds.contains(message.media.user_id)) {
                                    userIds.add(message.media.user_id);
                                }
                                if (message.media != null && message.media.audio != null && message.media.audio.user_id != 0 && !userIds.contains(message.media.audio.user_id)) {
                                    userIds.add(message.media.audio.user_id);
                                }
                                if (message.fwd_from_id instanceof TLRPC.TL_peerUser) {
                                    if (!userIds.contains(message.fwd_from_id.user_id)) {
                                        userIds.add(message.fwd_from_id.user_id);
                                    }
                                } else if (message.fwd_from_id instanceof TLRPC.TL_peerChannel) {
                                    if (!chatIds.contains(message.fwd_from_id.channel_id)) {
                                        chatIds.add(message.fwd_from_id.channel_id);
                                    }
                                }
                                message.send_state = cursor.intValue(2);
                                if (message.to_id.channel_id == 0 && !MessageObject.isUnread(message) && lower_id != 0 || message.id > 0) {
                                    message.send_state = 0;
                                }
                                if (lower_id == 0 && !cursor.isNull(5)) {
                                    message.random_id = cursor.longValue(5);
                                }
                            }
                            data.reuse();
                        }
                        cursor.dispose();

                        if (!encryptedChatIds.isEmpty()) {
                            getEncryptedChatsInternal(TextUtils.join(",", encryptedChatIds), encryptedChats, userIds);
                        }

                        if (!userIds.isEmpty()) {
                            getUsersInternal(TextUtils.join(",", userIds), users);
                        }

                        if (!chatIds.isEmpty()) {
                            getChatsInternal(TextUtils.join(",", chatIds), chats);
                        }
                    }
                    Collections.reverse(messages);
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            NotificationsController.getInstance().processLoadedUnreadMessages(pushDialogs, messages, users, chats, encryptedChats);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void putWallpapers(final ArrayList<TLRPC.WallPaper> wallPapers) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    int num = 0;
                    database.executeFast("DELETE FROM wallpapers WHERE 1").stepThis().dispose();
                    database.beginTransaction();
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO wallpapers VALUES(?, ?)");
                    for (TLRPC.WallPaper wallPaper : wallPapers) {
                        state.requery();
                        NativeByteBuffer data = new NativeByteBuffer(wallPaper.getObjectSize());
                        wallPaper.serializeToStream(data);
                        state.bindInteger(1, num);
                        state.bindByteBuffer(2, data);
                        state.step();
                        num++;
                        data.reuse();
                    }
                    state.dispose();
                    database.commitTransaction();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void loadWebRecent(final int type) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT id, image_url, thumb_url, local_url, width, height, size, date FROM web_recent_v3 WHERE type = " + type);
                    final ArrayList<MediaController.SearchImage> arrayList = new ArrayList<>();
                    while (cursor.next()) {
                        MediaController.SearchImage searchImage = new MediaController.SearchImage();
                        searchImage.id = cursor.stringValue(0);
                        searchImage.imageUrl = cursor.stringValue(1);
                        searchImage.thumbUrl = cursor.stringValue(2);
                        searchImage.localUrl = cursor.stringValue(3);
                        searchImage.width = cursor.intValue(4);
                        searchImage.height = cursor.intValue(5);
                        searchImage.size = cursor.intValue(6);
                        searchImage.date = cursor.intValue(7);
                        searchImage.type = type;
                        arrayList.add(searchImage);
                    }
                    cursor.dispose();
                    Collections.sort(arrayList, new Comparator<MediaController.SearchImage>() {
                        @Override
                        public int compare(MediaController.SearchImage lhs, MediaController.SearchImage rhs) {
                            if (lhs.date < rhs.date) {
                                return 1;
                            } else if (lhs.date > rhs.date) {
                                return -1;
                            } else {
                                return 0;
                            }
                        }
                    });
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            NotificationCenter.getInstance().postNotificationName(NotificationCenter.recentImagesDidLoaded, type, arrayList);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void addRecentLocalFile(final String imageUrl, final String localUrl) {
        if (imageUrl == null || localUrl == null || imageUrl.length() == 0 || localUrl.length() == 0) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast("UPDATE web_recent_v3 SET local_url = '" + localUrl + "' WHERE image_url = '" + imageUrl + "'").stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void clearWebRecent(final int type) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast("DELETE FROM web_recent_v3 WHERE type = " + type).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void putWebRecent(final ArrayList<MediaController.SearchImage> arrayList) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.beginTransaction();
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO web_recent_v3 VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)");
                    for (int a = 0; a < arrayList.size(); a++) {
                        if (a == 100) {
                            break;
                        }
                        MediaController.SearchImage searchImage = arrayList.get(a);
                        if (searchImage.localUrl == null) {
                            searchImage.localUrl = "";
                        }
                        state.requery();
                        state.bindString(1, searchImage.id);
                        state.bindInteger(2, searchImage.type);
                        state.bindString(3, searchImage.imageUrl);
                        state.bindString(4, searchImage.thumbUrl);
                        state.bindString(5, searchImage.localUrl);
                        state.bindInteger(6, searchImage.width);
                        state.bindInteger(7, searchImage.height);
                        state.bindInteger(8, searchImage.size);
                        state.bindInteger(9, searchImage.date);
                        state.step();
                    }
                    state.dispose();
                    database.commitTransaction();
                    if (arrayList.size() >= 100) {
                        database.beginTransaction();
                        for (int a = 100; a < arrayList.size(); a++) {
                            database.executeFast("DELETE FROM web_recent_v3 WHERE id = '" + arrayList.get(a).id + "'").stepThis().dispose();
                        }
                        database.commitTransaction();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getWallpapers() {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT data FROM wallpapers WHERE 1");
                    final ArrayList<TLRPC.WallPaper> wallPapers = new ArrayList<>();
                    while (cursor.next()) {
                        NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(0));
                        if (data != null && cursor.byteBufferValue(0, data) != 0) {
                            TLRPC.WallPaper wallPaper = TLRPC.WallPaper.TLdeserialize(data, data.readInt32(false), false);
                            wallPapers.add(wallPaper);
                        }
                        data.reuse();
                    }
                    cursor.dispose();
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            NotificationCenter.getInstance().postNotificationName(NotificationCenter.wallpapersDidLoaded, wallPapers);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getBlockedUsers() {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    ArrayList<Integer> ids = new ArrayList<>();
                    ArrayList<TLRPC.User> users = new ArrayList<>();
                    SQLiteCursor cursor = database.queryFinalized("SELECT * FROM blocked_users WHERE 1");
                    StringBuilder usersToLoad = new StringBuilder();
                    while (cursor.next()) {
                        int user_id = cursor.intValue(0);
                        ids.add(user_id);
                        if (usersToLoad.length() != 0) {
                            usersToLoad.append(",");
                        }
                        usersToLoad.append(user_id);
                    }
                    cursor.dispose();

                    if (usersToLoad.length() != 0) {
                        getUsersInternal(usersToLoad.toString(), users);
                    }

                    MessagesController.getInstance().processLoadedBlockedUsers(ids, users, true);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void deleteBlockedUser(final int id) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast("DELETE FROM blocked_users WHERE uid = " + id).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void putBlockedUsers(final ArrayList<Integer> ids, final boolean replace) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (replace) {
                        database.executeFast("DELETE FROM blocked_users WHERE 1").stepThis().dispose();
                    }
                    database.beginTransaction();
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO blocked_users VALUES(?)");
                    for (Integer id : ids) {
                        state.requery();
                        state.bindInteger(1, id);
                        state.step();
                    }
                    state.dispose();
                    database.commitTransaction();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void deleteDialog(final long did, final boolean messagesOnly) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!messagesOnly) {
                        database.executeFast("DELETE FROM dialogs WHERE did = " + did).stepThis().dispose();
                        database.executeFast("DELETE FROM chat_settings_v2 WHERE uid = " + did).stepThis().dispose();
                        database.executeFast("DELETE FROM search_recent WHERE did = " + did).stepThis().dispose();
                        int lower_id = (int)did;
                        int high_id = (int)(did >> 32);
                        if (lower_id != 0) {
                            if (high_id == 1) {
                                database.executeFast("DELETE FROM chats WHERE uid = " + lower_id).stepThis().dispose();
                            } else if (lower_id < 0) {
                                //database.executeFast("DELETE FROM chats WHERE uid = " + (-lower_id)).stepThis().dispose();
                            }
                        } else {
                            database.executeFast("DELETE FROM enc_chats WHERE uid = " + high_id).stepThis().dispose();
                            //database.executeFast("DELETE FROM secret_holes WHERE uid = " + high_id).stepThis().dispose();
                        }
                    }

                    if ((int) did == 0) {
                        SQLiteCursor cursor = database.queryFinalized("SELECT data FROM messages WHERE uid = " + did);
                        ArrayList<File> filesToDelete = new ArrayList<>();
                        try {
                            while (cursor.next()) {
                                NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(0));
                                if (data != null && cursor.byteBufferValue(0, data) != 0) {
                                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                    if (message == null || message.media == null) {
                                        continue;
                                    }
                                    if (message.media instanceof TLRPC.TL_messageMediaAudio) {
                                        File file = FileLoader.getPathToAttach(message.media.audio);
                                        if (file != null && file.toString().length() > 0) {
                                            filesToDelete.add(file);
                                        }
                                    } else if (message.media instanceof TLRPC.TL_messageMediaPhoto) {
                                        for (TLRPC.PhotoSize photoSize : message.media.photo.sizes) {
                                            File file = FileLoader.getPathToAttach(photoSize);
                                            if (file != null && file.toString().length() > 0) {
                                                filesToDelete.add(file);
                                            }
                                        }
                                    } else if (message.media instanceof TLRPC.TL_messageMediaVideo) {
                                        File file = FileLoader.getPathToAttach(message.media.video);
                                        if (file != null && file.toString().length() > 0) {
                                            filesToDelete.add(file);
                                        }
                                        file = FileLoader.getPathToAttach(message.media.video.thumb);
                                        if (file != null && file.toString().length() > 0) {
                                            filesToDelete.add(file);
                                        }
                                    } else if (message.media instanceof TLRPC.TL_messageMediaDocument) {
                                        File file = FileLoader.getPathToAttach(message.media.document);
                                        if (file != null && file.toString().length() > 0) {
                                            filesToDelete.add(file);
                                        }
                                        file = FileLoader.getPathToAttach(message.media.document.thumb);
                                        if (file != null && file.toString().length() > 0) {
                                            filesToDelete.add(file);
                                        }
                                    }
                                }
                                data.reuse();
                            }
                        } catch (Exception e) {
                            FileLog.e("tmessages", e);
                        }
                        cursor.dispose();
                        FileLoader.getInstance().deleteFiles(filesToDelete);
                    }

                    database.executeFast("UPDATE dialogs SET unread_count = 0 WHERE did = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM messages WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM bot_keyboard WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM media_counts_v2 WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM media_v2 WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM messages_holes WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM messages_imp_holes WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM media_holes_v2 WHERE uid = " + did).stepThis().dispose();
                    BotQuery.clearBotKeyboard(did, null);
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            NotificationCenter.getInstance().postNotificationName(NotificationCenter.needReloadRecentDialogsSearch);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getUserPhotos(final int uid, final int offset, final int count, final long max_id, final int classGuid) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteCursor cursor;

                    if (max_id != 0) {
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM user_photos WHERE uid = %d AND id < %d ORDER BY id DESC LIMIT %d", uid, max_id, count));
                    } else {
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM user_photos WHERE uid = %d ORDER BY id DESC LIMIT %d,%d", uid, offset, count));
                    }

                    final TLRPC.photos_Photos res = new TLRPC.photos_Photos();

                    while (cursor.next()) {
                        NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(0));
                        if (data != null && cursor.byteBufferValue(0, data) != 0) {
                            TLRPC.Photo photo = TLRPC.Photo.TLdeserialize(data, data.readInt32(false), false);
                            res.photos.add(photo);
                        }
                        data.reuse();
                    }
                    cursor.dispose();

                    Utilities.stageQueue.postRunnable(new Runnable() {
                        @Override
                        public void run() {
                            MessagesController.getInstance().processLoadedUserPhotos(res, uid, offset, count, max_id, true, classGuid);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void clearUserPhotos(final int uid) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast("DELETE FROM user_photos WHERE uid = " + uid).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void clearUserPhoto(final int uid, final long pid) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast("DELETE FROM user_photos WHERE uid = " + uid + " AND id = " + pid).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void putUserPhotos(final int uid, final TLRPC.photos_Photos photos) {
        if (photos == null || photos.photos.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO user_photos VALUES(?, ?, ?)");
                    for (TLRPC.Photo photo : photos.photos) {
                        if (photo instanceof TLRPC.TL_photoEmpty) {
                            continue;
                        }
                        state.requery();
                        NativeByteBuffer data = new NativeByteBuffer(photo.getObjectSize());
                        photo.serializeToStream(data);
                        state.bindInteger(1, uid);
                        state.bindLong(2, photo.id);
                        state.bindByteBuffer(3, data);
                        state.step();
                        data.reuse();
                    }
                    state.dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getNewTask(final ArrayList<Integer> oldTask) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (oldTask != null) {
                        String ids = TextUtils.join(",", oldTask);
                        database.executeFast(String.format(Locale.US, "DELETE FROM enc_tasks_v2 WHERE mid IN(%s)", ids)).stepThis().dispose();
                    }
                    int date = 0;
                    ArrayList<Integer> arr = null;
                    SQLiteCursor cursor = database.queryFinalized("SELECT mid, date FROM enc_tasks_v2 WHERE date = (SELECT min(date) FROM enc_tasks_v2)");
                    while (cursor.next()) {
                        Integer mid = cursor.intValue(0);
                        date = cursor.intValue(1);
                        if (arr == null) {
                            arr = new ArrayList<>();
                        }
                        arr.add(mid);
                    }
                    cursor.dispose();
                    MessagesController.getInstance().processLoadedDeleteTask(date, arr);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void createTaskForSecretChat(final int chat_id, final int time, final int readTime, final int isOut, final ArrayList<Long> random_ids) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    int minDate = Integer.MAX_VALUE;
                    SparseArray<ArrayList<Integer>> messages = new SparseArray<>();
                    StringBuilder mids = new StringBuilder();
                    SQLiteCursor cursor;
                    if (random_ids == null) {
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT mid, ttl FROM messages WHERE uid = %d AND out = %d AND read_state != 0 AND ttl > 0 AND date <= %d AND send_state = 0 AND media != 1", ((long) chat_id) << 32, isOut, time));
                    } else {
                        String ids = TextUtils.join(",", random_ids);
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.mid, m.ttl FROM messages as m INNER JOIN randoms as r ON m.mid = r.mid WHERE r.random_id IN (%s)", ids));
                    }
                    while (cursor.next()) {
                        int ttl = cursor.intValue(1);
                        if (ttl <= 0) {
                            continue;
                        }
                        int mid = cursor.intValue(0);
                        int date = Math.min(readTime, time) + ttl;
                        minDate = Math.min(minDate, date);
                        ArrayList<Integer> arr = messages.get(date);
                        if (arr == null) {
                            arr = new ArrayList<>();
                            messages.put(date, arr);
                        }
                        if (mids.length() != 0) {
                            mids.append(",");
                        }
                        mids.append(mid);
                        arr.add(mid);
                    }
                    cursor.dispose();
                    if (messages.size() != 0) {
                        database.beginTransaction();
                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO enc_tasks_v2 VALUES(?, ?)");
                        for (int a = 0; a < messages.size(); a++) {
                            int key = messages.keyAt(a);
                            ArrayList<Integer> arr = messages.get(key);
                            for (Integer mid : arr) {
                                state.requery();
                                state.bindInteger(1, mid);
                                state.bindInteger(2, key);
                                state.step();
                            }
                        }
                        state.dispose();
                        database.commitTransaction();
                        database.executeFast(String.format(Locale.US, "UPDATE messages SET ttl = 0 WHERE mid IN(%s)", mids.toString())).stepThis().dispose();
                        MessagesController.getInstance().didAddedNewTask(minDate, messages);
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    private void updateDialogsWithReadMessagesInternal(final ArrayList<Integer> messages, final SparseArray<Long> inbox) {
        try {
            HashMap<Long, Integer> dialogsToUpdate = new HashMap<>();
            StringBuilder dialogsToReload = new StringBuilder();

            if (messages != null && !messages.isEmpty()) {
                String ids = TextUtils.join(",", messages);
                SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT uid, read_state, out FROM messages WHERE mid IN(%s)", ids));
                while (cursor.next()) {
                    int out = cursor.intValue(2);
                    if (out != 0) {
                        continue;
                    }
                    int read_state = cursor.intValue(1);
                    if (read_state != 0) {
                        continue;
                    }
                    long uid = cursor.longValue(0);
                    Integer currentCount = dialogsToUpdate.get(uid);
                    if (currentCount == null) {
                        dialogsToUpdate.put(uid, 1);
                        if (dialogsToReload.length() != 0) {
                            dialogsToReload.append(",");
                        }
                        dialogsToReload.append(uid);
                    } else {
                        dialogsToUpdate.put(uid, currentCount + 1);
                    }
                }
                cursor.dispose();
            } else if (inbox != null && inbox.size() != 0) {
                for (int b = 0; b < inbox.size(); b++) {
                    int key = inbox.keyAt(b);
                    long messageId = inbox.get(key);
                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT COUNT(mid) FROM messages WHERE uid = %d AND mid <= %d AND read_state IN(0,2) AND out = 0", key, messageId));
                    if (cursor.next()) {
                        int count = cursor.intValue(0);
                        if (count != 0) {
                            dialogsToUpdate.put((long) key, count);
                            if (dialogsToReload.length() != 0) {
                                dialogsToReload.append(",");
                            }
                            dialogsToReload.append(key);
                        }
                    }
                    cursor.dispose();
                }
            }

            if (dialogsToReload.length() > 0) {
                SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT did, unread_count FROM dialogs WHERE did IN(%s)", dialogsToReload.toString()));
                while (cursor.next()) {
                    long did = cursor.longValue(0);
                    int count = cursor.intValue(1);
                    Integer currentCount = dialogsToUpdate.get(did);
                    if (currentCount != null) {
                        dialogsToUpdate.put(did, Math.max(0, count - currentCount));
                    } else {
                        dialogsToUpdate.remove(did);
                    }
                }
                cursor.dispose();

                database.beginTransaction();
                SQLitePreparedStatement state = database.executeFast("UPDATE dialogs SET unread_count = ? WHERE did = ?");
                for (HashMap.Entry<Long, Integer> entry : dialogsToUpdate.entrySet()) {
                    state.requery();
                    state.bindInteger(1, entry.getValue());
                    state.bindLong(2, entry.getKey());
                    state.step();
                }
                state.dispose();
                database.commitTransaction();
            }

            if (!dialogsToUpdate.isEmpty()) {
                MessagesController.getInstance().processDialogsUpdateRead(dialogsToUpdate);
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void updateDialogsWithReadMessages(final SparseArray<Long> inbox, boolean useQueue) {
        if (inbox.size() == 0) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    updateDialogsWithReadMessagesInternal(null, inbox);
                }
            });
        } else {
            updateDialogsWithReadMessagesInternal(null, inbox);
        }
    }

    public void updateChatParticipants(final TLRPC.ChatParticipants participants) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT info FROM chat_settings_v2 WHERE uid = " + participants.chat_id);
                    TLRPC.ChatFull info = null;
                    ArrayList<TLRPC.User> loadedUsers = new ArrayList<>();
                    if (cursor.next()) {
                        NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(0));
                        if (data != null && cursor.byteBufferValue(0, data) != 0) {
                            info = TLRPC.ChatFull.TLdeserialize(data, data.readInt32(false), false);
                        }
                        data.reuse();
                    }
                    cursor.dispose();
                    if (info instanceof TLRPC.TL_chatFull) {
                        info.participants = participants;
                        final TLRPC.ChatFull finalInfo = info;
                        AndroidUtilities.runOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                NotificationCenter.getInstance().postNotificationName(NotificationCenter.chatInfoDidLoaded, finalInfo);
                            }
                        });

                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO chat_settings_v2 VALUES(?, ?)");
                        NativeByteBuffer data = new NativeByteBuffer(info.getObjectSize());
                        info.serializeToStream(data);
                        state.bindInteger(1, info.id);
                        state.bindByteBuffer(2, data);
                        state.step();
                        state.dispose();
                        data.reuse();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void updateChatInfo(final TLRPC.ChatFull info, final boolean ifExist) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ifExist) {
                        boolean dontExist = true;
                        SQLiteCursor cursor = database.queryFinalized("SELECT uid FROM chat_settings_v2 WHERE uid = " + info.id);
                        if (cursor.next()) {
                            dontExist = false;
                        }
                        cursor.dispose();
                        if (dontExist) {
                            return;
                        }
                    }
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO chat_settings_v2 VALUES(?, ?)");
                    NativeByteBuffer data = new NativeByteBuffer(info.getObjectSize());
                    info.serializeToStream(data);
                    state.bindInteger(1, info.id);
                    state.bindByteBuffer(2, data);
                    state.step();
                    state.dispose();
                    data.reuse();

                    if (info instanceof TLRPC.TL_channelFull) {
                        SQLiteCursor cursor = database.queryFinalized("SELECT date, last_mid_i, pts, date_i, last_mid FROM dialogs WHERE did = " + (-info.id));
                        if (cursor.next()) {
                            int dialog_date = cursor.intValue(0);
                            long last_mid_i = cursor.longValue(1);
                            int pts = cursor.intValue(2);
                            int dialog_date_i = cursor.intValue(3);
                            long last_mid = cursor.longValue(4);

                            state = database.executeFast("REPLACE INTO dialogs VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                            state.bindLong(1, -info.id);
                            state.bindInteger(2, dialog_date);
                            state.bindInteger(3, info.unread_important_count);
                            state.bindLong(4, last_mid);
                            state.bindInteger(5, info.read_inbox_max_id);
                            state.bindInteger(6, 0);
                            state.bindLong(7, last_mid_i);
                            state.bindInteger(8, info.unread_count);
                            state.bindInteger(9, pts);
                            state.bindInteger(10, dialog_date_i);
                            state.step();
                            state.dispose();
                        }
                        cursor.dispose();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void updateChatInfo(final int chat_id, final int user_id, final boolean deleted, final int invited_id, final int version) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT info FROM chat_settings_v2 WHERE uid = " + chat_id);
                    TLRPC.ChatFull info = null;
                    ArrayList<TLRPC.User> loadedUsers = new ArrayList<>();
                    if (cursor.next()) {
                        NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(0));
                        if (data != null && cursor.byteBufferValue(0, data) != 0) {
                            info = TLRPC.ChatFull.TLdeserialize(data, data.readInt32(false), false);
                        }
                        data.reuse();
                    }
                    cursor.dispose();
                    if (info instanceof TLRPC.TL_chatFull) {
                        if (deleted) {
                            for (int a = 0; a < info.participants.participants.size(); a++) {
                                TLRPC.TL_chatParticipant participant = info.participants.participants.get(a);
                                if (participant.user_id == user_id) {
                                    info.participants.participants.remove(a);
                                    break;
                                }
                            }
                        } else {
                            for (TLRPC.TL_chatParticipant part : info.participants.participants) {
                                if (part.user_id == user_id) {
                                    return;
                                }
                            }
                            TLRPC.TL_chatParticipant participant = new TLRPC.TL_chatParticipant();
                            participant.user_id = user_id;
                            participant.inviter_id = invited_id;
                            participant.date = ConnectionsManager.getInstance().getCurrentTime();
                            info.participants.participants.add(participant);
                        }
                        info.participants.version = version;

                        final TLRPC.ChatFull finalInfo = info;
                        AndroidUtilities.runOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                NotificationCenter.getInstance().postNotificationName(NotificationCenter.chatInfoDidLoaded, finalInfo);
                            }
                        });

                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO chat_settings_v2 VALUES(?, ?)");
                        NativeByteBuffer data = new NativeByteBuffer(info.getObjectSize());
                        info.serializeToStream(data);
                        state.bindInteger(1, chat_id);
                        state.bindByteBuffer(2, data);
                        state.step();
                        state.dispose();
                        data.reuse();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void loadChatInfo(final int chat_id, final Semaphore semaphore, final boolean force) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT info FROM chat_settings_v2 WHERE uid = " + chat_id);
                    TLRPC.ChatFull info = null;
                    ArrayList<TLRPC.User> loadedUsers = new ArrayList<>();
                    if (cursor.next()) {
                        NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(0));
                        if (data != null && cursor.byteBufferValue(0, data) != 0) {
                            info = TLRPC.ChatFull.TLdeserialize(data, data.readInt32(false), false);
                        }
                        data.reuse();
                    }
                    cursor.dispose();

                    if (info instanceof TLRPC.TL_chatFull) {
                        StringBuilder usersToLoad = new StringBuilder();
                        for (int a = 0; a < info.participants.participants.size(); a++) {
                            TLRPC.TL_chatParticipant c = info.participants.participants.get(a);
                            if (usersToLoad.length() != 0) {
                                usersToLoad.append(",");
                            }
                            usersToLoad.append(c.user_id);
                        }
                        if (usersToLoad.length() != 0) {
                            getUsersInternal(usersToLoad.toString(), loadedUsers);
                        }
                    }
                    if (semaphore != null) {
                        semaphore.release();
                    }
                    MessagesController.getInstance().processChatInfo(chat_id, info, loadedUsers, true, force);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    if (semaphore != null) {
                        semaphore.release();
                    }
                }
            }
        });
    }

    public void processPendingRead(final long dialog_id, final long max_id, final int max_date, final boolean delete) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (delete) {
                        //database.executeFast("DELETE FROM pending_read WHERE uid = " + dialog_id).stepThis().dispose();
                    } else {
                        database.beginTransaction();
                        SQLitePreparedStatement state;/* = database.executeFast("REPLACE INTO pending_read VALUES(?, ?)");
                        state.requery();
                        state.bindLong(1, dialog_id);
                        state.bindInteger(2, max_id);
                        state.step();
                        state.dispose();*/

                        int lower_id = (int) dialog_id;

                        if (lower_id != 0) {
                            state = database.executeFast("UPDATE messages SET read_state = read_state | 1 WHERE uid = ? AND mid > 0 AND mid <= ? AND read_state IN(0,2) AND out = 0");
                            state.requery();
                            state.bindLong(1, dialog_id);
                            state.bindLong(2, max_id);
                            state.step();
                            state.dispose();
                        } else {
                            state = database.executeFast("UPDATE messages SET read_state = read_state | 1 WHERE uid = ? AND date <= ? AND read_state IN(0,2) AND out = 0");
                            state.requery();
                            state.bindLong(1, dialog_id);
                            state.bindInteger(2, max_date);
                            state.step();
                            state.dispose();
                        }

                        state = database.executeFast("UPDATE dialogs SET unread_count = 0 WHERE did = ?");
                        state.requery();
                        state.bindLong(1, dialog_id);
                        state.step();
                        state.dispose();

                        database.commitTransaction();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void putContacts(final ArrayList<TLRPC.TL_contact> contacts, final boolean deleteAll) {
        if (contacts.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (deleteAll) {
                        database.executeFast("DELETE FROM contacts WHERE 1").stepThis().dispose();
                    }
                    database.beginTransaction();
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO contacts VALUES(?, ?)");
                    for (TLRPC.TL_contact contact : contacts) {
                        state.requery();
                        state.bindInteger(1, contact.user_id);
                        state.bindInteger(2, contact.mutual ? 1 : 0);
                        state.step();
                    }
                    state.dispose();
                    database.commitTransaction();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void deleteContacts(final ArrayList<Integer> uids) {
        if (uids == null || uids.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    String ids = TextUtils.join(",", uids);
                    database.executeFast("DELETE FROM contacts WHERE uid IN(" + ids + ")").stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void applyPhoneBookUpdates(final String adds, final String deletes) {
        if (adds.length() == 0 && deletes.length() == 0) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (adds.length() != 0) {
                        database.executeFast(String.format(Locale.US, "UPDATE user_phones_v6 SET deleted = 0 WHERE sphone IN(%s)", adds)).stepThis().dispose();
                    }
                    if (deletes.length() != 0) {
                        database.executeFast(String.format(Locale.US, "UPDATE user_phones_v6 SET deleted = 1 WHERE sphone IN(%s)", deletes)).stepThis().dispose();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void putCachedPhoneBook(final HashMap<Integer, ContactsController.Contact> contactHashMap) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.beginTransaction();
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO user_contacts_v6 VALUES(?, ?, ?)");
                    SQLitePreparedStatement state2 = database.executeFast("REPLACE INTO user_phones_v6 VALUES(?, ?, ?, ?)");
                    for (HashMap.Entry<Integer, ContactsController.Contact> entry : contactHashMap.entrySet()) {
                        ContactsController.Contact contact = entry.getValue();
                        if (contact.phones.isEmpty() || contact.shortPhones.isEmpty()) {
                            continue;
                        }
                        state.requery();
                        state.bindInteger(1, contact.id);
                        state.bindString(2, contact.first_name);
                        state.bindString(3, contact.last_name);
                        state.step();
                        for (int a = 0; a < contact.phones.size(); a++) {
                            state2.requery();
                            state2.bindInteger(1, contact.id);
                            state2.bindString(2, contact.phones.get(a));
                            state2.bindString(3, contact.shortPhones.get(a));
                            state2.bindInteger(4, contact.phoneDeleted.get(a));
                            state2.step();
                        }
                    }
                    state.dispose();
                    state2.dispose();
                    database.commitTransaction();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getCachedPhoneBook() {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                HashMap<Integer, ContactsController.Contact> contactHashMap = new HashMap<>();
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT us.uid, us.fname, us.sname, up.phone, up.sphone, up.deleted FROM user_contacts_v6 as us LEFT JOIN user_phones_v6 as up ON us.uid = up.uid WHERE 1");
                    while (cursor.next()) {
                        int uid = cursor.intValue(0);
                        ContactsController.Contact contact = contactHashMap.get(uid);
                        if (contact == null) {
                            contact = new ContactsController.Contact();
                            contact.first_name = cursor.stringValue(1);
                            contact.last_name = cursor.stringValue(2);
                            contact.id = uid;
                            contactHashMap.put(uid, contact);
                        }
                        String phone = cursor.stringValue(3);
                        if (phone == null) {
                            continue;
                        }
                        contact.phones.add(phone);
                        String sphone = cursor.stringValue(4);
                        if (sphone == null) {
                            continue;
                        }
                        if (sphone.length() == 8 && phone.length() != 8) {
                            sphone = PhoneFormat.stripExceptNumbers(phone);
                        }
                        contact.shortPhones.add(sphone);
                        contact.phoneDeleted.add(cursor.intValue(5));
                        contact.phoneTypes.add("");
                    }
                    cursor.dispose();
                } catch (Exception e) {
                    contactHashMap.clear();
                    FileLog.e("tmessages", e);
                }
                ContactsController.getInstance().performSyncPhoneBook(contactHashMap, true, true, false);
            }
        });
    }

    public void getContacts() {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                ArrayList<TLRPC.TL_contact> contacts = new ArrayList<>();
                ArrayList<TLRPC.User> users = new ArrayList<>();
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT * FROM contacts WHERE 1");
                    StringBuilder uids = new StringBuilder();
                    while (cursor.next()) {
                        int user_id = cursor.intValue(0);
                        TLRPC.TL_contact contact = new TLRPC.TL_contact();
                        contact.user_id = user_id;
                        contact.mutual = cursor.intValue(1) == 1;
                        if (uids.length() != 0) {
                            uids.append(",");
                        }
                        contacts.add(contact);
                        uids.append(contact.user_id);
                    }
                    cursor.dispose();

                    if (uids.length() != 0) {
                        getUsersInternal(uids.toString(), users);
                    }
                } catch (Exception e) {
                    contacts.clear();
                    users.clear();
                    FileLog.e("tmessages", e);
                }
                ContactsController.getInstance().processLoadedContacts(contacts, users, 1);
            }
        });
    }

    public void getUnsentMessages(final int count) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    HashMap<Integer, TLRPC.Message> messageHashMap = new HashMap<>();
                    ArrayList<TLRPC.Message> messages = new ArrayList<>();
                    ArrayList<TLRPC.User> users = new ArrayList<>();
                    ArrayList<TLRPC.Chat> chats = new ArrayList<>();
                    ArrayList<TLRPC.EncryptedChat> encryptedChats = new ArrayList<>();

                    ArrayList<Integer> userIds = new ArrayList<>();
                    ArrayList<Integer> chatIds = new ArrayList<>();
                    ArrayList<Integer> broadcastIds = new ArrayList<>();
                    ArrayList<Integer> encryptedChatIds = new ArrayList<>();
                    SQLiteCursor cursor = database.queryFinalized("SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.uid, s.seq_in, s.seq_out FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid LEFT JOIN messages_seq as s ON m.mid = s.mid WHERE m.mid < 0 AND m.send_state = 1 ORDER BY m.mid DESC LIMIT " + count);
                    while (cursor.next()) {
                        NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(1));
                        if (data != null && cursor.byteBufferValue(1, data) != 0) {
                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                            if (!messageHashMap.containsKey(message.id)) {
                                MessageObject.setUnreadFlags(message, cursor.intValue(0));
                                message.id = cursor.intValue(3);
                                message.date = cursor.intValue(4);
                                if (!cursor.isNull(5)) {
                                    message.random_id = cursor.longValue(5);
                                }
                                message.dialog_id = cursor.longValue(6);
                                message.seq_in = cursor.intValue(7);
                                message.seq_out = cursor.intValue(8);
                                messages.add(message);
                                messageHashMap.put(message.id, message);

                                int lower_id = (int) message.dialog_id;
                                int high_id = (int) (message.dialog_id >> 32);

                                if (lower_id != 0) {
                                    if (high_id == 1) {
                                        if (!broadcastIds.contains(lower_id)) {
                                            broadcastIds.add(lower_id);
                                        }
                                    } else {
                                        if (lower_id < 0) {
                                            if (!chatIds.contains(-lower_id)) {
                                                chatIds.add(-lower_id);
                                            }
                                        } else {
                                            if (!userIds.contains(lower_id)) {
                                                userIds.add(lower_id);
                                            }
                                        }
                                    }
                                } else {
                                    if (!encryptedChatIds.contains(high_id)) {
                                        encryptedChatIds.add(high_id);
                                    }
                                }

                                if (!userIds.contains(message.from_id)) {
                                    userIds.add(message.from_id);
                                }
                                if (message.action != null && message.action.user_id != 0 && !userIds.contains(message.action.user_id)) {
                                    userIds.add(message.action.user_id);
                                }
                                if (message.media != null && message.media.user_id != 0 && !userIds.contains(message.media.user_id)) {
                                    userIds.add(message.media.user_id);
                                }
                                if (message.media != null && message.media.audio != null && message.media.audio.user_id != 0 && !userIds.contains(message.media.audio.user_id)) {
                                    userIds.add(message.media.audio.user_id);
                                }
                                if (message.fwd_from_id instanceof TLRPC.TL_peerUser) {
                                    if (!userIds.contains(message.fwd_from_id.user_id)) {
                                        userIds.add(message.fwd_from_id.user_id);
                                    }
                                } else if (message.fwd_from_id instanceof TLRPC.TL_peerChannel) {
                                    if (!chatIds.contains(message.fwd_from_id.channel_id)) {
                                        chatIds.add(message.fwd_from_id.channel_id);
                                    }
                                }
                                message.send_state = cursor.intValue(2);
                                if (message.to_id.channel_id == 0 && !MessageObject.isUnread(message) && lower_id != 0 || message.id > 0) {
                                    message.send_state = 0;
                                }
                                if (lower_id == 0 && !cursor.isNull(5)) {
                                    message.random_id = cursor.longValue(5);
                                }
                            }
                        }
                        data.reuse();
                    }
                    cursor.dispose();


                    if (!encryptedChatIds.isEmpty()) {
                        getEncryptedChatsInternal(TextUtils.join(",", encryptedChatIds), encryptedChats, userIds);
                    }

                    if (!userIds.isEmpty()) {
                        getUsersInternal(TextUtils.join(",", userIds), users);
                    }

                    if (!chatIds.isEmpty() || !broadcastIds.isEmpty()) {
                        StringBuilder stringToLoad = new StringBuilder();
                        for (Integer cid : chatIds) {
                            if (stringToLoad.length() != 0) {
                                stringToLoad.append(",");
                            }
                            stringToLoad.append(cid);
                        }
                        for (Integer cid : broadcastIds) {
                            if (stringToLoad.length() != 0) {
                                stringToLoad.append(",");
                            }
                            stringToLoad.append(-cid);
                        }
                        getChatsInternal(stringToLoad.toString(), chats);
                    }

                    SendMessagesHelper.getInstance().processUnsentMessages(messages, users, chats, encryptedChats);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getMessages(final long dialog_id, final int count, final int max_id, final int minDate, final int classGuid, final int load_type, final int important) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                TLRPC.TL_messages_messages res = new TLRPC.TL_messages_messages();
                int count_unread = 0;
                int count_query = count;
                int offset_query = 0;
                int min_unread_id = 0;
                int last_message_id = 0;
                int max_unread_date = 0;
                long messageMaxId = max_id;
                int channelId = 0;
                if (important != 0) {
                    channelId = -(int) dialog_id;
                }
                if (messageMaxId != 0 && channelId != 0) {
                    messageMaxId |= ((long) channelId) << 32;
                }
                boolean isEnd = false;
                try {
                    ArrayList<Integer> loadedUsers = new ArrayList<>();
                    ArrayList<Integer> fromUser = new ArrayList<>();
                    ArrayList<Integer> chatsToLoad = new ArrayList<>();
                    ArrayList<Long> replyMessages = new ArrayList<>();
                    HashMap<Integer, ArrayList<TLRPC.Message>> replyMessageOwners = new HashMap<>();

                    SQLiteCursor cursor;
                    int lower_id = (int)dialog_id;
                    if (lower_id != 0) {
                        String imp = important == 2 ? " AND imp = 1 " : "";
                        String holesTable = important == 2 ? "messages_imp_holes" : "messages_holes";

                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT start FROM " + holesTable + " WHERE uid = %d AND start IN (0, 1)", dialog_id));
                        if (cursor.next()) {
                            isEnd = cursor.intValue(0) == 1;
                            cursor.dispose();
                        } else {
                            cursor.dispose();
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT min(mid) FROM messages WHERE uid = %d AND mid > 0", dialog_id));
                            if (cursor.next()) {
                                int mid = cursor.intValue(0);
                                if (mid != 0) {
                                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO " + holesTable + " VALUES(?, ?, ?)");
                                    state.requery();
                                    state.bindLong(1, dialog_id);
                                    state.bindInteger(2, 0);
                                    state.bindInteger(3, mid);
                                    state.step();
                                    state.dispose();
                                }
                            }
                            cursor.dispose();
                        }

                        if (load_type == 3) {
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT max(mid) FROM messages WHERE uid = %d AND mid > 0", dialog_id));
                            if (cursor.next()) {
                                last_message_id = cursor.intValue(0);
                            }
                            cursor.dispose();

                            boolean containMessage = false;
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT mid FROM messages WHERE mid = %d", messageMaxId));
                            if (cursor.next()) {
                                containMessage = true;
                            }
                            cursor.dispose();

                            if (containMessage) {
                                long holeMessageMaxId = 0;
                                long holeMessageMinId = 1;
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT start FROM " + holesTable + " WHERE uid = %d AND start >= %d ORDER BY start ASC LIMIT 1", dialog_id, max_id));
                                if (cursor.next()) {
                                    holeMessageMaxId = cursor.intValue(0);
                                    if (channelId != 0) {
                                        holeMessageMaxId |= ((long) channelId) << 32;
                                    }
                                }
                                cursor.dispose();
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT end FROM " + holesTable + " WHERE uid = %d AND end <= %d ORDER BY end DESC LIMIT 1", dialog_id, max_id));
                                if (cursor.next()) {
                                    holeMessageMinId = cursor.intValue(0);
                                    if (channelId != 0) {
                                        holeMessageMinId |= ((long) channelId) << 32;
                                    }
                                }
                                /*if (holeMessageMaxId == holeMessageMinId) { TODO ???
                                    holeMessageMaxId = 0;
                                    holeMessageMinId = 1;
                                }*/
                                cursor.dispose();
                                if (holeMessageMaxId != 0 || holeMessageMinId != 1) {
                                    if (holeMessageMaxId == 0) {
                                        holeMessageMaxId = 1000000000;
                                        if (channelId != 0) {
                                            holeMessageMaxId |= ((long) channelId) << 32;
                                        }
                                    }
                                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT * FROM (SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.mid <= %d AND m.mid >= %d " + imp + "ORDER BY m.date DESC, m.mid DESC LIMIT %d) UNION " +
                                            "SELECT * FROM (SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.mid > %d AND m.mid <= %d " + imp + "ORDER BY m.date ASC, m.mid ASC LIMIT %d)", dialog_id, messageMaxId, holeMessageMinId, count_query / 2, dialog_id, messageMaxId, holeMessageMaxId, count_query / 2));
                                } else {
                                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT * FROM (SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.mid <= %d " + imp + "ORDER BY m.date DESC, m.mid DESC LIMIT %d) UNION " +
                                            "SELECT * FROM (SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.mid > %d " + imp + "ORDER BY m.date ASC, m.mid ASC LIMIT %d)", dialog_id, messageMaxId, count_query / 2, dialog_id, messageMaxId, count_query / 2));
                                }
                            } else {
                                cursor = null;
                            }
                        } else if (load_type == 1) {
                            long holeMessageId = 0;
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT start FROM " + holesTable + " WHERE uid = %d AND start >= %d ORDER BY start ASC LIMIT 1", dialog_id, max_id));
                            if (cursor.next()) {
                                holeMessageId = cursor.intValue(0);
                                if (channelId != 0) {
                                    holeMessageId |= ((long) channelId) << 32;
                                }
                            }
                            cursor.dispose();
                            if (holeMessageId != 0) {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.date >= %d AND m.mid > %d AND m.mid <= %d " + imp + "ORDER BY m.date ASC, m.mid ASC LIMIT %d", dialog_id, minDate, messageMaxId, holeMessageId, count_query));
                            } else {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.date >= %d AND m.mid > %d " + imp + "ORDER BY m.date ASC, m.mid ASC LIMIT %d", dialog_id, minDate, messageMaxId, count_query));
                            }
                        } else if (minDate != 0) {
                            if (messageMaxId != 0) {
                                long holeMessageId = 0;
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT end FROM " + holesTable + " WHERE uid = %d AND end <= %d ORDER BY end DESC LIMIT 1", dialog_id, max_id));
                                if (cursor.next()) {
                                    holeMessageId = cursor.intValue(0);
                                    if (channelId != 0) {
                                        holeMessageId |= ((long) channelId) << 32;
                                    }
                                }
                                cursor.dispose();
                                if (holeMessageId != 0) {
                                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.date <= %d AND m.mid < %d AND (m.mid >= %d OR m.mid < 0) " + imp + "ORDER BY m.date DESC, m.mid DESC LIMIT %d", dialog_id, minDate, messageMaxId, holeMessageId, count_query));
                                } else {
                                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.date <= %d AND m.mid < %d " + imp + "ORDER BY m.date DESC, m.mid DESC LIMIT %d", dialog_id, minDate, messageMaxId, count_query));
                                }
                            } else {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.date <= %d " + imp + "ORDER BY m.date DESC, m.mid DESC LIMIT %d,%d", dialog_id, minDate, offset_query, count_query));
                            }
                        } else {
                            if (load_type == 2) {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT max(mid) FROM messages WHERE uid = %d AND mid > 0", dialog_id));
                                if (cursor.next()) {
                                    last_message_id = cursor.intValue(0);
                                }
                                cursor.dispose();

                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT min(mid), max(date) FROM messages WHERE uid = %d AND out = 0 AND read_state IN(0,2) AND mid > 0" + imp, dialog_id));
                                if (cursor.next()) {
                                    min_unread_id = cursor.intValue(0);
                                    max_unread_date = cursor.intValue(1);
                                }
                                cursor.dispose();
                                if (min_unread_id != 0) {
                                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT COUNT(*) FROM messages WHERE uid = %d AND mid >= %d " + imp + "AND out = 0 AND read_state IN(0,2)", dialog_id, min_unread_id));
                                    if (cursor.next()) {
                                        count_unread = cursor.intValue(0);
                                    }
                                    cursor.dispose();
                                }
                            }

                            if (count_query > count_unread || count_unread < 4) {
                                count_query = Math.max(count_query, count_unread + 10);
                                if (count_unread < 4) {
                                    count_unread = 0;
                                    min_unread_id = 0;
                                    last_message_id = 0;
                                }
                            } else {
                                offset_query = count_unread - count_query;
                                count_query += 10;
                            }

                            long holeMessageId = 0;
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT max(end) FROM " + holesTable + " WHERE uid = %d", dialog_id));
                            if (cursor.next()) {
                                holeMessageId = cursor.intValue(0);
                                if (channelId != 0) {
                                    holeMessageId |= ((long) channelId) << 32;
                                }
                            }
                            cursor.dispose();
                            if (holeMessageId != 0) {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND (m.mid >= %d OR m.mid < 0) " + imp + "ORDER BY m.date DESC, m.mid DESC LIMIT %d,%d", dialog_id, holeMessageId, offset_query, count_query));
                            } else {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d " + imp + "ORDER BY m.date DESC, m.mid DESC LIMIT %d,%d", dialog_id, offset_query, count_query));
                            }
                        }
                    } else {
                        isEnd = true;
                        if (load_type == 1) {
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.mid < %d ORDER BY m.mid DESC LIMIT %d", dialog_id, max_id, count_query));
                        } else if (minDate != 0) {
                            if (max_id != 0) {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.mid > %d ORDER BY m.mid ASC LIMIT %d", dialog_id, max_id, count_query));
                            } else {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d AND m.date <= %d ORDER BY m.mid ASC LIMIT %d,%d", dialog_id, minDate, offset_query, count_query));
                            }
                        } else {
                            if (load_type == 2) {
                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT min(mid) FROM messages WHERE uid = %d AND mid < 0", dialog_id));
                                if (cursor.next()) {
                                    last_message_id = cursor.intValue(0);
                                }
                                cursor.dispose();

                                cursor = database.queryFinalized(String.format(Locale.US, "SELECT max(mid), max(date) FROM messages WHERE uid = %d AND out = 0 AND read_state IN(0,2) AND mid < 0", dialog_id));
                                if (cursor.next()) {
                                    min_unread_id = cursor.intValue(0);
                                    max_unread_date = cursor.intValue(1);
                                }
                                cursor.dispose();
                                if (min_unread_id != 0) {
                                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT COUNT(*) FROM messages WHERE uid = %d AND mid <= %d AND out = 0 AND read_state IN(0,2)", dialog_id, min_unread_id));
                                    if (cursor.next()) {
                                        count_unread = cursor.intValue(0);
                                    }
                                    cursor.dispose();
                                }
                            }

                            if (count_query > count_unread || count_unread < 4) {
                                count_query = Math.max(count_query, count_unread + 10);
                                if (count_unread < 4) {
                                    count_unread = 0;
                                    min_unread_id = 0;
                                    last_message_id = 0;
                                }
                            } else {
                                offset_query = count_unread - count_query;
                                count_query += 10;
                            }
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT m.read_state, m.data, m.send_state, m.mid, m.date, r.random_id, m.replydata, m.media FROM messages as m LEFT JOIN randoms as r ON r.mid = m.mid WHERE m.uid = %d ORDER BY m.mid ASC LIMIT %d,%d", dialog_id, offset_query, count_query));
                        }
                    }
                    if (cursor != null) {
                        while (cursor.next()) {
                            NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(1));
                            if (data != null && cursor.byteBufferValue(1, data) != 0) {
                                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                MessageObject.setUnreadFlags(message, cursor.intValue(0));
                                message.id = cursor.intValue(3);
                                message.date = cursor.intValue(4);
                                message.dialog_id = dialog_id;
                                if ((message.flags & TLRPC.MESSAGE_FLAG_HAS_VIEWS) != 0) {
                                    message.views = cursor.intValue(7);
                                }
                                res.messages.add(message);
                                fromUser.add(message.from_id);
                                if (message.action != null && message.action.user_id != 0) {
                                    fromUser.add(message.action.user_id);
                                }
                                if (message.media != null && message.media.user_id != 0) {
                                    fromUser.add(message.media.user_id);
                                }
                                if (message.media != null && message.media.audio != null && message.media.audio.user_id != 0) {
                                    fromUser.add(message.media.audio.user_id);
                                }
                                if (message.fwd_from_id instanceof TLRPC.TL_peerUser) {
                                    if (!fromUser.contains(message.fwd_from_id.user_id)) {
                                        fromUser.add(message.fwd_from_id.user_id);
                                    }
                                } else if (message.fwd_from_id instanceof TLRPC.TL_peerChannel) {
                                    if (!chatsToLoad.contains(message.fwd_from_id.channel_id)) {
                                        chatsToLoad.add(message.fwd_from_id.channel_id);
                                    }
                                }
                                if (message.reply_to_msg_id != 0) {
                                    boolean ok = false;
                                    if (!cursor.isNull(6)) {
                                        NativeByteBuffer data2 = new NativeByteBuffer(cursor.byteArrayLength(6));
                                        if (data2 != null && cursor.byteBufferValue(6, data2) != 0) {
                                            message.replyMessage = TLRPC.Message.TLdeserialize(data2, data2.readInt32(false), false);
                                            if (message.replyMessage != null) {
                                                fromUser.add(message.replyMessage.from_id);
                                                if (message.replyMessage.action != null && message.replyMessage.action.user_id != 0) {
                                                    fromUser.add(message.replyMessage.action.user_id);
                                                }
                                                if (message.replyMessage.media != null && message.replyMessage.media.user_id != 0) {
                                                    fromUser.add(message.replyMessage.media.user_id);
                                                }
                                                if (message.replyMessage.media != null && message.replyMessage.media.audio != null && message.replyMessage.media.audio.user_id != 0) {
                                                    fromUser.add(message.replyMessage.media.audio.user_id);
                                                }
                                                if (message.replyMessage.fwd_from_id instanceof TLRPC.TL_peerUser) {
                                                    if (!fromUser.contains(message.replyMessage.fwd_from_id.user_id)) {
                                                        fromUser.add(message.replyMessage.fwd_from_id.user_id);
                                                    }
                                                } else if (message.replyMessage.fwd_from_id instanceof TLRPC.TL_peerChannel) {
                                                    if (!chatsToLoad.contains(message.replyMessage.fwd_from_id.channel_id)) {
                                                        chatsToLoad.add(message.replyMessage.fwd_from_id.channel_id);
                                                    }
                                                }
                                                ok = true;
                                            }
                                        }
                                        data2.reuse();
                                    }
                                    if (!ok) {
                                        long messageId = message.reply_to_msg_id;
                                        if (message.to_id.channel_id != 0) {
                                            messageId |= ((long) message.to_id.channel_id) << 32;
                                        }
                                        if (!replyMessages.contains(messageId)) {
                                            replyMessages.add(messageId);
                                        }
                                        ArrayList<TLRPC.Message> messages = replyMessageOwners.get(message.reply_to_msg_id);
                                        if (messages == null) {
                                            messages = new ArrayList<>();
                                            replyMessageOwners.put(message.reply_to_msg_id, messages);
                                        }
                                        messages.add(message);
                                    }
                                }
                                message.send_state = cursor.intValue(2);
                                if (message.to_id.channel_id == 0 && !MessageObject.isUnread(message) && lower_id != 0 || message.id > 0) {
                                    message.send_state = 0;
                                }
                                if (lower_id == 0 && !cursor.isNull(5)) {
                                    message.random_id = cursor.longValue(5);
                                }
                                if ((int) dialog_id == 0 && message.media != null && message.media.photo != null) {
                                    try {
                                        SQLiteCursor cursor2 = database.queryFinalized(String.format(Locale.US, "SELECT date FROM enc_tasks_v2 WHERE mid = %d", message.id));
                                        if (cursor2.next()) {
                                            message.destroyTime = cursor2.intValue(0);
                                        }
                                        cursor2.dispose();
                                    } catch (Exception e) {
                                        FileLog.e("tmessages", e);
                                    }
                                }
                            }
                            data.reuse();
                        }
                        cursor.dispose();
                    }

                    if (load_type == 3 && res.messages.size() == 1) {
                        res.messages.clear();
                    }

                    Collections.sort(res.messages, new Comparator<TLRPC.Message>() {
                        @Override
                        public int compare(TLRPC.Message lhs, TLRPC.Message rhs) {
                            if (lhs.id > 0 && rhs.id > 0) {
                                if (lhs.id > rhs.id) {
                                    return -1;
                                } else if (lhs.id < rhs.id) {
                                    return 1;
                                }
                            } else if (lhs.id < 0 && rhs.id < 0) {
                                if (lhs.id < rhs.id) {
                                    return -1;
                                } else if (lhs.id > rhs.id) {
                                    return 1;
                                }
                            } else {
                                if (lhs.date > rhs.date) {
                                    return -1;
                                } else if (lhs.date < rhs.date) {
                                    return 1;
                                }
                            }
                            return 0;
                        }
                    });

                    if (important == 2 && !res.messages.isEmpty()) {
                        if (max_id != 0) {
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT start, end, count FROM channel_group WHERE uid = %d AND ((start >= %d AND end <= %d) OR (start = %d))", dialog_id, res.messages.get(res.messages.size() - 1).id, res.messages.get(0).id, res.messages.get(0).id));
                        } else {
                            cursor = database.queryFinalized(String.format(Locale.US, "SELECT start, end, count FROM channel_group WHERE uid = %d AND start >= %d", dialog_id, res.messages.get(res.messages.size() - 1).id));
                        }
                        while (cursor.next()) {
                            TLRPC.TL_messageGroup group = new TLRPC.TL_messageGroup();
                            group.min_id = cursor.intValue(0);
                            group.max_id = cursor.intValue(1);
                            group.count = cursor.intValue(2);
                            res.collapsed.add(group);
                        }
                        cursor.dispose();
                    }

                    if (!replyMessages.isEmpty()) {
                        cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, mid, date FROM messages WHERE mid IN(%s)", TextUtils.join(",", replyMessages)));
                        while (cursor.next()) {
                            NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(0));
                            if (data != null && cursor.byteBufferValue(0, data) != 0) {
                                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                message.id = cursor.intValue(1);
                                message.date = cursor.intValue(2);
                                message.dialog_id = dialog_id;
                                fromUser.add(message.from_id);
                                if (message.action != null && message.action.user_id != 0) {
                                    fromUser.add(message.action.user_id);
                                }
                                if (message.media != null && message.media.user_id != 0) {
                                    fromUser.add(message.media.user_id);
                                }
                                if (message.media != null && message.media.audio != null && message.media.audio.user_id != 0) {
                                    fromUser.add(message.media.audio.user_id);
                                }
                                if (message.fwd_from_id instanceof TLRPC.TL_peerUser) {
                                    if (!fromUser.contains(message.fwd_from_id.user_id)) {
                                        fromUser.add(message.fwd_from_id.user_id);
                                    }
                                } else if (message.fwd_from_id instanceof TLRPC.TL_peerChannel) {
                                    if (!chatsToLoad.contains(message.fwd_from_id.channel_id)) {
                                        chatsToLoad.add(message.fwd_from_id.channel_id);
                                    }
                                }
                                ArrayList<TLRPC.Message> arrayList = replyMessageOwners.get(message.id);
                                if (arrayList != null) {
                                    for (TLRPC.Message m : arrayList) {
                                        m.replyMessage = message;
                                    }
                                }
                            }
                            data.reuse();
                        }
                        cursor.dispose();
                    }

                    StringBuilder usersToLoad = new StringBuilder();
                    for (int uid : fromUser) {
                        if (!loadedUsers.contains(uid)) {
                            if (usersToLoad.length() != 0) {
                                usersToLoad.append(",");
                            }
                            usersToLoad.append(uid);
                            loadedUsers.add(uid);
                        }
                    }
                    if (usersToLoad.length() != 0) {
                        getUsersInternal(usersToLoad.toString(), res.users);
                    }
                    if (!chatsToLoad.isEmpty()) {
                        getChatsInternal(TextUtils.join(",", chatsToLoad), res.chats);
                    }
                } catch (Exception e) {
                    res.messages.clear();
                    res.chats.clear();
                    res.users.clear();
                    res.collapsed.clear();
                    FileLog.e("tmessages", e);
                } finally {
                    MessagesController.getInstance().processLoadedMessages(res, dialog_id, count_query, max_id, true, classGuid, min_unread_id, last_message_id, count_unread, max_unread_date, load_type, important, isEnd);
                }
            }
        });
    }

    public void startTransaction(boolean useQueue) {
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        database.beginTransaction();
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                }
            });
        } else {
            try {
                database.beginTransaction();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
        }
    }

    public void commitTransaction(boolean useQueue) {
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        database.commitTransaction();
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                }
            });
        } else {
            try {
                database.commitTransaction();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
        }
    }

    public TLObject getSentFile(final String path, final int type) {
        if (path == null) {
            return null;
        }
        final Semaphore semaphore = new Semaphore(0);
        final ArrayList<TLObject> result = new ArrayList<>();
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    String id = Utilities.MD5(path);
                    if (id != null) {
                        SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM sent_files_v2 WHERE uid = '%s' AND type = %d", id, type));
                        if (cursor.next()) {
                            NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(0));
                            if (data != null && cursor.byteBufferValue(0, data) != 0) {
                                TLObject file = TLClassStore.Instance().TLdeserialize(data, data.readInt32(false), false);
                                if (file != null) {
                                    result.add(file);
                                }
                            }
                            data.reuse();
                        }
                        cursor.dispose();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    semaphore.release();
                }
            }
        });
        try {
            semaphore.acquire();
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return !result.isEmpty() ? result.get(0) : null;
    }

    public void putSentFile(final String path, final TLObject file, final int type) {
        if (path == null || file == null) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                SQLitePreparedStatement state = null;
                try {
                    String id = Utilities.MD5(path);
                    if (id != null) {
                        state = database.executeFast("REPLACE INTO sent_files_v2 VALUES(?, ?, ?)");
                        state.requery();
                        NativeByteBuffer data = new NativeByteBuffer(file.getObjectSize());
                        file.serializeToStream(data);
                        state.bindString(1, id);
                        state.bindInteger(2, type);
                        state.bindByteBuffer(3, data);
                        state.step();
                        data.reuse();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    if (state != null) {
                        state.dispose();
                    }
                }
            }
        });
    }

    public void updateEncryptedChatSeq(final TLRPC.EncryptedChat chat) {
        if (chat == null) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                SQLitePreparedStatement state = null;
                try {
                    state = database.executeFast("UPDATE enc_chats SET seq_in = ?, seq_out = ?, use_count = ? WHERE uid = ?");
                    state.bindInteger(1, chat.seq_in);
                    state.bindInteger(2, chat.seq_out);
                    state.bindInteger(3, (int)chat.key_use_count_in << 16 | chat.key_use_count_out);
                    state.bindInteger(4, chat.id);
                    state.step();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    if (state != null) {
                        state.dispose();
                    }
                }
            }
        });
    }

    public void updateEncryptedChatTTL(final TLRPC.EncryptedChat chat) {
        if (chat == null) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                SQLitePreparedStatement state = null;
                try {
                    state = database.executeFast("UPDATE enc_chats SET ttl = ? WHERE uid = ?");
                    state.bindInteger(1, chat.ttl);
                    state.bindInteger(2, chat.id);
                    state.step();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    if (state != null) {
                        state.dispose();
                    }
                }
            }
        });
    }

    public void updateEncryptedChatLayer(final TLRPC.EncryptedChat chat) {
        if (chat == null) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                SQLitePreparedStatement state = null;
                try {
                    state = database.executeFast("UPDATE enc_chats SET layer = ? WHERE uid = ?");
                    state.bindInteger(1, chat.layer);
                    state.bindInteger(2, chat.id);
                    state.step();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    if (state != null) {
                        state.dispose();
                    }
                }
            }
        });
    }

    public void updateEncryptedChat(final TLRPC.EncryptedChat chat) {
        if (chat == null) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                SQLitePreparedStatement state = null;
                try {
                    if ((chat.key_hash == null || chat.key_hash.length != 16) && chat.auth_key != null) {
                        byte[] sha1 = Utilities.computeSHA1(chat.auth_key);
                        chat.key_hash = new byte[16];
                        System.arraycopy(sha1, 0, chat.key_hash, 0, chat.key_hash.length);
                    }

                    state = database.executeFast("UPDATE enc_chats SET data = ?, g = ?, authkey = ?, ttl = ?, layer = ?, seq_in = ?, seq_out = ?, use_count = ?, exchange_id = ?, key_date = ?, fprint = ?, fauthkey = ?, khash = ? WHERE uid = ?");
                    NativeByteBuffer data = new NativeByteBuffer(chat.getObjectSize());
                    NativeByteBuffer data2 = new NativeByteBuffer(chat.a_or_b != null ? chat.a_or_b.length : 1);
                    NativeByteBuffer data3 = new NativeByteBuffer(chat.auth_key != null ? chat.auth_key.length : 1);
                    NativeByteBuffer data4 = new NativeByteBuffer(chat.future_auth_key != null ? chat.future_auth_key.length : 1);
                    NativeByteBuffer data5 = new NativeByteBuffer(chat.key_hash != null ? chat.key_hash.length : 1);
                    chat.serializeToStream(data);
                    state.bindByteBuffer(1, data);
                    if (chat.a_or_b != null) {
                        data2.writeBytes(chat.a_or_b);
                    }
                    if (chat.auth_key != null) {
                        data3.writeBytes(chat.auth_key);
                    }
                    if (chat.future_auth_key != null) {
                        data4.writeBytes(chat.future_auth_key);
                    }
                    if (chat.key_hash != null) {
                        data5.writeBytes(chat.key_hash);
                    }
                    state.bindByteBuffer(2, data2);
                    state.bindByteBuffer(3, data3);
                    state.bindInteger(4, chat.ttl);
                    state.bindInteger(5, chat.layer);
                    state.bindInteger(6, chat.seq_in);
                    state.bindInteger(7, chat.seq_out);
                    state.bindInteger(8, (int) chat.key_use_count_in << 16 | chat.key_use_count_out);
                    state.bindLong(9, chat.exchange_id);
                    state.bindInteger(10, chat.key_create_date);
                    state.bindLong(11, chat.future_key_fingerprint);
                    state.bindByteBuffer(12, data4);
                    state.bindByteBuffer(13, data5);
                    state.bindInteger(14, chat.id);

                    state.step();
                    data.reuse();
                    data2.reuse();
                    data3.reuse();
                    data4.reuse();
                    data5.reuse();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    if (state != null) {
                        state.dispose();
                    }
                }
            }
        });
    }

    public void getEncryptedChat(final int chat_id, final Semaphore semaphore, final ArrayList<TLObject> result) {
        if (semaphore == null || result == null) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    ArrayList<Integer> usersToLoad = new ArrayList<>();
                    ArrayList<TLRPC.EncryptedChat> encryptedChats = new ArrayList<>();
                    getEncryptedChatsInternal("" + chat_id, encryptedChats, usersToLoad);
                    if (!encryptedChats.isEmpty() && !usersToLoad.isEmpty()) {
                        ArrayList<TLRPC.User> users = new ArrayList<>();
                        getUsersInternal(TextUtils.join(",", usersToLoad), users);
                        if (!users.isEmpty()) {
                            result.add(encryptedChats.get(0));
                            result.add(users.get(0));
                        }
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    semaphore.release();
                }
            }
        });
    }

    public void putEncryptedChat(final TLRPC.EncryptedChat chat, final TLRPC.User user, final TLRPC.Dialog dialog) {
        if (chat == null) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if ((chat.key_hash == null || chat.key_hash.length != 16) && chat.auth_key != null) {
                        byte[] sha1 = Utilities.computeSHA1(chat.auth_key);
                        chat.key_hash = new byte[16];
                        System.arraycopy(sha1, 0, chat.key_hash, 0, chat.key_hash.length);
                    }
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO enc_chats VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                    NativeByteBuffer data = new NativeByteBuffer(chat.getObjectSize());
                    NativeByteBuffer data2 = new NativeByteBuffer(chat.a_or_b != null ? chat.a_or_b.length : 1);
                    NativeByteBuffer data3 = new NativeByteBuffer(chat.auth_key != null ? chat.auth_key.length : 1);
                    NativeByteBuffer data4 = new NativeByteBuffer(chat.future_auth_key != null ? chat.future_auth_key.length : 1);
                    NativeByteBuffer data5 = new NativeByteBuffer(chat.key_hash != null ? chat.key_hash.length : 1);

                    chat.serializeToStream(data);
                    state.bindInteger(1, chat.id);
                    state.bindInteger(2, user.id);
                    state.bindString(3, formatUserSearchName(user));
                    state.bindByteBuffer(4, data);
                    if (chat.a_or_b != null) {
                        data2.writeBytes(chat.a_or_b);
                    }
                    if (chat.auth_key != null) {
                        data3.writeBytes(chat.auth_key);
                    }
                    if (chat.future_auth_key != null) {
                        data4.writeBytes(chat.future_auth_key);
                    }
                    if (chat.key_hash != null) {
                        data5.writeBytes(chat.key_hash);
                    }
                    state.bindByteBuffer(5, data2);
                    state.bindByteBuffer(6, data3);
                    state.bindInteger(7, chat.ttl);
                    state.bindInteger(8, chat.layer);
                    state.bindInteger(9, chat.seq_in);
                    state.bindInteger(10, chat.seq_out);
                    state.bindInteger(11, (int) chat.key_use_count_in << 16 | chat.key_use_count_out);
                    state.bindLong(12, chat.exchange_id);
                    state.bindInteger(13, chat.key_create_date);
                    state.bindLong(14, chat.future_key_fingerprint);
                    state.bindByteBuffer(15, data4);
                    state.bindByteBuffer(16, data5);

                    state.step();
                    state.dispose();
                    data.reuse();
                    data2.reuse();
                    data3.reuse();
                    data4.reuse();
                    data5.reuse();
                    if (dialog != null) {
                        state = database.executeFast("REPLACE INTO dialogs VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                        state.bindLong(1, dialog.id);
                        state.bindInteger(2, dialog.last_message_date);
                        state.bindInteger(3, dialog.unread_count);
                        state.bindInteger(4, dialog.top_message);
                        state.bindInteger(5, dialog.read_inbox_max_id);
                        state.bindInteger(6, 0);
                        state.bindInteger(7, dialog.top_not_important_message);
                        state.bindInteger(8, dialog.unread_not_important_count);
                        state.bindInteger(9, dialog.pts);
                        state.bindInteger(10, 0);
                        state.step();
                        state.dispose();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    private String formatUserSearchName(TLRPC.User user) {
        StringBuilder str = new StringBuilder("");
        if (user.first_name != null && user.first_name.length() > 0) {
            str.append(user.first_name);
        }
        if (user.last_name != null && user.last_name.length() > 0) {
            if (str.length() > 0) {
                str.append(" ");
            }
            str.append(user.last_name);
        }
        str.append(";;;");
        if (user.username != null && user.username.length() > 0) {
            str.append(user.username);
        }
        return str.toString().toLowerCase();
    }

    private void putUsersInternal(ArrayList<TLRPC.User> users) throws Exception {
        if (users == null || users.isEmpty()) {
            return;
        }
        SQLitePreparedStatement state = database.executeFast("REPLACE INTO users VALUES(?, ?, ?, ?)");
        for (TLRPC.User user : users) {
            state.requery();
            NativeByteBuffer data = new NativeByteBuffer(user.getObjectSize());
            user.serializeToStream(data);
            state.bindInteger(1, user.id);
            state.bindString(2, formatUserSearchName(user));
            if (user.status != null) {
                if (user.status instanceof TLRPC.TL_userStatusRecently) {
                    user.status.expires = -100;
                } else if (user.status instanceof TLRPC.TL_userStatusLastWeek) {
                    user.status.expires = -101;
                } else if (user.status instanceof TLRPC.TL_userStatusLastMonth) {
                    user.status.expires = -102;
                }
                state.bindInteger(3, user.status.expires);
            } else {
                state.bindInteger(3, 0);
            }
            state.bindByteBuffer(4, data);
            state.step();
            data.reuse();
        }
        state.dispose();
    }

    private void putChatsInternal(ArrayList<TLRPC.Chat> chats) throws Exception {
        if (chats == null || chats.isEmpty()) {
            return;
        }
        SQLitePreparedStatement state = database.executeFast("REPLACE INTO chats VALUES(?, ?, ?)");
        for (TLRPC.Chat chat : chats) {
            state.requery();
            NativeByteBuffer data = new NativeByteBuffer(chat.getObjectSize());
            chat.serializeToStream(data);
            state.bindInteger(1, chat.id);
            if (chat.title != null) {
                String name = chat.title.toLowerCase();
                state.bindString(2, name);
            } else {
                state.bindString(2, "");
            }
            state.bindByteBuffer(3, data);
            state.step();
            data.reuse();
        }
        state.dispose();
    }

    public void getUsersInternal(String usersToLoad, ArrayList<TLRPC.User> result) throws Exception {
        if (usersToLoad == null || usersToLoad.length() == 0 || result == null) {
            return;
        }
        SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, status FROM users WHERE uid IN(%s)", usersToLoad));
        while (cursor.next()) {
            try {
                NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(0));
                if (data != null && cursor.byteBufferValue(0, data) != 0) {
                    TLRPC.User user = TLRPC.User.TLdeserialize(data, data.readInt32(false), false);
                    if (user != null) {
                        if (user.status != null) {
                            user.status.expires = cursor.intValue(1);
                        }
                        result.add(user);
                    }
                }
                data.reuse();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
        }
        cursor.dispose();
    }

    public void getChatsInternal(String chatsToLoad, ArrayList<TLRPC.Chat> result) throws Exception {
        if (chatsToLoad == null || chatsToLoad.length() == 0 || result == null) {
            return;
        }
        SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM chats WHERE uid IN(%s)", chatsToLoad));
        while (cursor.next()) {
            try {
                NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(0));
                if (data != null && cursor.byteBufferValue(0, data) != 0) {
                    TLRPC.Chat chat = TLRPC.Chat.TLdeserialize(data, data.readInt32(false), false);
                    if (chat != null) {
                        result.add(chat);
                    }
                }
                data.reuse();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
        }
        cursor.dispose();
    }

    public void getEncryptedChatsInternal(String chatsToLoad, ArrayList<TLRPC.EncryptedChat> result, ArrayList<Integer> usersToLoad) throws Exception {
        if (chatsToLoad == null || chatsToLoad.length() == 0 || result == null) {
            return;
        }
        SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT data, user, g, authkey, ttl, layer, seq_in, seq_out, use_count, exchange_id, key_date, fprint, fauthkey, khash FROM enc_chats WHERE uid IN(%s)", chatsToLoad));
        while (cursor.next()) {
            try {
                NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(0));
                if (data != null && cursor.byteBufferValue(0, data) != 0) {
                    TLRPC.EncryptedChat chat = TLRPC.EncryptedChat.TLdeserialize(data, data.readInt32(false), false);
                    if (chat != null) {
                        chat.user_id = cursor.intValue(1);
                        if (usersToLoad != null && !usersToLoad.contains(chat.user_id)) {
                            usersToLoad.add(chat.user_id);
                        }
                        chat.a_or_b = cursor.byteArrayValue(2);
                        chat.auth_key = cursor.byteArrayValue(3);
                        chat.ttl = cursor.intValue(4);
                        chat.layer = cursor.intValue(5);
                        chat.seq_in = cursor.intValue(6);
                        chat.seq_out = cursor.intValue(7);
                        int use_count = cursor.intValue(8);
                        chat.key_use_count_in = (short)(use_count >> 16);
                        chat.key_use_count_out = (short)(use_count);
                        chat.exchange_id = cursor.longValue(9);
                        chat.key_create_date = cursor.intValue(10);
                        chat.future_key_fingerprint = cursor.longValue(11);
                        chat.future_auth_key = cursor.byteArrayValue(12);
                        chat.key_hash = cursor.byteArrayValue(13);
                        result.add(chat);
                    }
                }
                data.reuse();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
        }
        cursor.dispose();
    }

    private void putUsersAndChatsInternal(final ArrayList<TLRPC.User> users, final ArrayList<TLRPC.Chat> chats, final boolean withTransaction) {
        try {
            if (withTransaction) {
                database.beginTransaction();
            }
            putUsersInternal(users);
            putChatsInternal(chats);
            if (withTransaction) {
                database.commitTransaction();
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void putUsersAndChats(final ArrayList<TLRPC.User> users, final ArrayList<TLRPC.Chat> chats, final boolean withTransaction, boolean useQueue) {
        if (users != null && users.isEmpty() && chats != null && chats.isEmpty()) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    putUsersAndChatsInternal(users, chats, withTransaction);
                }
            });
        } else {
            putUsersAndChatsInternal(users, chats, withTransaction);
        }
    }

    public void removeFromDownloadQueue(final long id, final int type, final boolean move) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (move) {
                        int minDate = -1;
                        SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT min(date) FROM download_queue WHERE type = %d", type));
                        if (cursor.next()) {
                            minDate = cursor.intValue(0);
                        }
                        cursor.dispose();
                        if (minDate != -1) {
                            database.executeFast(String.format(Locale.US, "UPDATE download_queue SET date = %d WHERE uid = %d AND type = %d", minDate - 1, id, type)).stepThis().dispose();
                        }
                    } else {
                        database.executeFast(String.format(Locale.US, "DELETE FROM download_queue WHERE uid = %d AND type = %d", id, type)).stepThis().dispose();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void clearDownloadQueue(final int type) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (type == 0) {
                        database.executeFast("DELETE FROM download_queue WHERE 1").stepThis().dispose();
                    } else {
                        database.executeFast(String.format(Locale.US, "DELETE FROM download_queue WHERE type = %d", type)).stepThis().dispose();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getDownloadQueue(final int type) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    final ArrayList<DownloadObject> objects = new ArrayList<>();
                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT uid, type, data FROM download_queue WHERE type = %d ORDER BY date DESC LIMIT 3", type));
                    while (cursor.next()) {
                        DownloadObject downloadObject = new DownloadObject();
                        downloadObject.type = cursor.intValue(1);
                        downloadObject.id = cursor.longValue(0);
                        NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(2));
                        if (data != null && cursor.byteBufferValue(2, data) != 0) {
                            downloadObject.object = TLClassStore.Instance().TLdeserialize(data, data.readInt32(false), false);
                        }
                        data.reuse();
                        objects.add(downloadObject);
                    }
                    cursor.dispose();

                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            MediaController.getInstance().processDownloadObjects(type, objects);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    private int getMessageMediaType(TLRPC.Message message) {
        if (message instanceof TLRPC.TL_message_secret && (
                message.media instanceof TLRPC.TL_messageMediaPhoto && message.ttl != 0 && message.ttl <= 60 ||
                message.media instanceof TLRPC.TL_messageMediaAudio ||
                message.media instanceof TLRPC.TL_messageMediaVideo)) {
            return 1;
        } else if (message.media instanceof TLRPC.TL_messageMediaPhoto || message.media instanceof TLRPC.TL_messageMediaVideo) {
            return 0;
        }
        return -1;
    }

    public void putWebPages(final HashMap<Long, TLRPC.WebPage> webPages) {
        if (webPages == null || webPages.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    String ids = TextUtils.join(",", webPages.keySet());
                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT mid FROM webpage_pending WHERE id IN (%s)", ids));
                    ArrayList<Long> mids = new ArrayList<>();
                    while (cursor.next()) {
                        mids.add(cursor.longValue(0));
                    }
                    cursor.dispose();

                    if (mids.isEmpty()) {
                        return;
                    }
                    final ArrayList<TLRPC.Message> messages = new ArrayList<>();
                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT mid, data FROM messages WHERE mid IN (%s)", TextUtils.join(",", mids)));
                    while (cursor.next()) {
                        int mid = cursor.intValue(0);
                        NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(1));
                        if (data != null && cursor.byteBufferValue(1, data) != 0) {
                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                            if (message.media instanceof TLRPC.TL_messageMediaWebPage) {
                                message.id = mid;
                                message.media.webpage = webPages.get(message.media.webpage.id);
                                messages.add(message);
                            }
                        }
                        data.reuse();
                    }
                    cursor.dispose();

                    database.executeFast(String.format(Locale.US, "DELETE FROM webpage_pending WHERE id IN (%s)", ids)).stepThis().dispose();

                    if (messages.isEmpty()) {
                        return;
                    }

                    database.beginTransaction();

                    SQLitePreparedStatement state = database.executeFast("UPDATE messages SET data = ? WHERE mid = ?");
                    SQLitePreparedStatement state2 = database.executeFast("UPDATE media_v2 SET data = ? WHERE mid = ?");
                    for (TLRPC.Message message : messages) {
                        NativeByteBuffer data = new NativeByteBuffer(message.getObjectSize());
                        message.serializeToStream(data);

                        long messageId = message.id;
                        if (message.to_id.channel_id != 0) {
                            messageId |= ((long) message.to_id.channel_id) << 32;
                        }

                        state.requery();
                        state.bindByteBuffer(1, data);
                        state.bindLong(2, messageId);
                        state.step();

                        state2.requery();
                        state2.bindByteBuffer(1, data);
                        state2.bindLong(2, messageId);
                        state2.step();

                        data.reuse();
                    }
                    state.dispose();
                    state2.dispose();

                    database.commitTransaction();

                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            NotificationCenter.getInstance().postNotificationName(NotificationCenter.didReceivedWebpages, messages);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void overwriteChannel(final int channel_id, final TLRPC.TL_updates_channelDifferenceTooLong difference) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    final long did = -channel_id;

                    database.executeFast("DELETE FROM messages WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM bot_keyboard WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM media_counts_v2 WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM media_v2 WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM messages_holes WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM messages_imp_holes WHERE uid = " + did).stepThis().dispose();
                    database.executeFast("DELETE FROM media_holes_v2 WHERE uid = " + did).stepThis().dispose();
                    BotQuery.clearBotKeyboard(did, null);

                    TLRPC.TL_messages_dialogs dialogs = new TLRPC.TL_messages_dialogs();
                    dialogs.chats.addAll(difference.chats);
                    dialogs.users.addAll(difference.users);
                    dialogs.messages.addAll(difference.messages);
                    TLRPC.TL_dialogChannel dialog = new TLRPC.TL_dialogChannel();
                    dialog.id = did;
                    dialog.peer = new TLRPC.TL_peerChannel();
                    dialog.peer.channel_id = channel_id;
                    dialog.top_not_important_message = difference.top_message;
                    dialog.top_message = difference.top_important_message;
                    dialog.read_inbox_max_id = difference.read_inbox_max_id;
                    dialog.unread_not_important_count = difference.unread_count;
                    dialog.unread_count = difference.unread_important_count;
                    dialog.notify_settings = null;
                    dialog.pts = difference.pts;
                    dialogs.dialogs.add(dialog);
                    putDialogsInternal(dialogs);

                    MessagesStorage.getInstance().updateDialogsWithDeletedMessages(new ArrayList<Integer>(), false, channel_id);
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            NotificationCenter.getInstance().postNotificationName(NotificationCenter.removeAllMessagesFromDialog, did, true);
                        }
                    });
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void putChannelViews(final SparseArray<SparseIntArray> channelViews, final boolean isChannel) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.beginTransaction();
                    SQLitePreparedStatement state = database.executeFast("UPDATE messages SET media = max((SELECT media FROM messages WHERE mid = ?), ?) WHERE mid = ?");
                    for (int a = 0; a < channelViews.size(); a++) {
                        int peer = channelViews.keyAt(a);
                        SparseIntArray messages = channelViews.get(peer);
                        for (int b = 0; b < messages.size(); b++) {
                            int views = messages.get(messages.keyAt(b));
                            long messageId = messages.keyAt(b);
                            if (isChannel) {
                                messageId |= ((long) -peer) << 32;
                            }
                            state.requery();
                            state.bindLong(1, messageId);
                            state.bindInteger(2, views);
                            state.bindLong(3, messageId);
                            state.step();
                        }
                    }
                    state.dispose();
                    database.commitTransaction();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    private void putMessagesInternal(final ArrayList<TLRPC.Message> messages, final boolean withTransaction, final boolean doNotUpdateDialogDate, final int downloadMask) {
        try {
            if (withTransaction) {
                database.beginTransaction();
            }
            HashMap<Long, TLRPC.Message> messagesMap = new HashMap<>();
            HashMap<Long, TLRPC.Message> messagesMapNotImportant = new HashMap<>();
            HashMap<Long, Integer> messagesCounts = new HashMap<>();
            HashMap<Long, Integer> messagesCountsNotImportant = new HashMap<>();
            HashMap<Integer, HashMap<Long, Integer>> mediaCounts = null;
            HashMap<Long, TLRPC.Message> botKeyboards = new HashMap<>();

            HashMap<Long, Long> messagesMediaIdsMap = null;
            StringBuilder messageMediaIds = null;
            HashMap<Long, Integer> mediaTypes = null;
            StringBuilder messageIds = new StringBuilder();
            HashMap<Long, Long> messagesIdsMap = new HashMap<>();
            HashMap<Long, Long> messagesIdsMapNotImportant = new HashMap<>();

            SQLitePreparedStatement state = database.executeFast("REPLACE INTO messages VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)");
            SQLitePreparedStatement state2 = null;
            SQLitePreparedStatement state3 = database.executeFast("REPLACE INTO randoms VALUES(?, ?)");
            SQLitePreparedStatement state4 = database.executeFast("REPLACE INTO download_queue VALUES(?, ?, ?, ?)");
            SQLitePreparedStatement state5 = database.executeFast("REPLACE INTO webpage_pending VALUES(?, ?)");

            for (int a = 0; a < messages.size(); a++) {
                TLRPC.Message message = messages.get(a);

                long messageId = message.id;
                if (message.dialog_id == 0) {
                    if (message.to_id.user_id != 0) {
                        message.dialog_id = message.to_id.user_id;
                    } else if (message.to_id.chat_id != 0) {
                        message.dialog_id = -message.to_id.chat_id;
                    } else {
                        message.dialog_id = -message.to_id.channel_id;
                    }
                }
                if (message.to_id.channel_id != 0) {
                    messageId |= ((long) message.to_id.channel_id) << 32;
                }

                if ((message.to_id.channel_id == 0 && MessageObject.isUnread(message) || MessageObject.isContentUnread(message)) && !MessageObject.isOut(message)) {
                    if (messageIds.length() > 0) {
                        messageIds.append(",");
                    }

                    messageIds.append(messageId);
                    if (message.to_id.channel_id == 0 || MessageObject.isImportant(message)) {
                        messagesIdsMap.put(messageId, message.dialog_id);
                    } else if (message.to_id.channel_id != 0) {
                        messagesIdsMapNotImportant.put(messageId, message.dialog_id);
                    }
                }
                if (SharedMediaQuery.canAddMessageToMedia(message)) {
                    if (messageMediaIds == null) {
                        messageMediaIds = new StringBuilder();
                        messagesMediaIdsMap = new HashMap<>();
                        mediaTypes = new HashMap<>();
                    }
                    if (messageMediaIds.length() > 0) {
                        messageMediaIds.append(",");
                    }
                    messageMediaIds.append(messageId);
                    messagesMediaIdsMap.put(messageId, message.dialog_id);
                    mediaTypes.put(messageId, SharedMediaQuery.getMediaType(message));
                }
                if (message.reply_markup != null && ((message.reply_markup.flags & 4) == 0 || (message.flags & 16) != 0)) {
                    TLRPC.Message oldMessage = botKeyboards.get(message.dialog_id);
                    if (oldMessage == null || oldMessage.id < message.id) {
                        botKeyboards.put(message.dialog_id, message);
                    }
                }
            }

            for (HashMap.Entry<Long, TLRPC.Message> entry : botKeyboards.entrySet()) {
                BotQuery.putBotKeyboard(entry.getKey(), entry.getValue());
            }

            if (messageMediaIds != null) {
                SQLiteCursor cursor = database.queryFinalized("SELECT mid FROM media_v2 WHERE mid IN(" + messageMediaIds.toString() + ")");
                while (cursor.next()) {
                    long mid = cursor.longValue(0);
                    messagesMediaIdsMap.remove(mid);
                }
                cursor.dispose();
                mediaCounts = new HashMap<>();
                for (HashMap.Entry<Long, Long> entry : messagesMediaIdsMap.entrySet()) {
                    Integer type = mediaTypes.get(entry.getKey());
                    HashMap<Long, Integer> counts = mediaCounts.get(type);
                    Integer count;
                    if (counts == null) {
                        counts = new HashMap<>();
                        count = 0;
                        mediaCounts.put(type, counts);
                    } else {
                        count = counts.get(entry.getValue());
                    }
                    if (count == null) {
                        count = 0;
                    }
                    count++;
                    counts.put(entry.getValue(), count);
                }
            }

            if (messageIds.length() > 0) {
                SQLiteCursor cursor = database.queryFinalized("SELECT mid FROM messages WHERE mid IN(" + messageIds.toString() + ")");
                while (cursor.next()) {
                    messagesIdsMap.remove(cursor.longValue(0));
                    messagesIdsMapNotImportant.remove(cursor.longValue(0));
                }
                cursor.dispose();
                for (Long dialog_id : messagesIdsMap.values()) {
                    Integer count = messagesCounts.get(dialog_id);
                    if (count == null) {
                        count = 0;
                    }
                    count++;
                    messagesCounts.put(dialog_id, count);
                }
                for (Long dialog_id : messagesIdsMapNotImportant.values()) {
                    Integer count = messagesCountsNotImportant.get(dialog_id);
                    if (count == null) {
                        count = 0;
                    }
                    count++;
                    messagesCountsNotImportant.put(dialog_id, count);
                }
            }

            int downloadMediaMask = 0;
            for (int a = 0; a < messages.size(); a++) {
                TLRPC.Message message = messages.get(a);
                fixUnsupportedMedia(message);

                state.requery();
                long messageId = message.id;
                if (message.local_id != 0) {
                    messageId = message.local_id;
                }
                if (message.to_id.channel_id != 0) {
                    messageId |= ((long) message.to_id.channel_id) << 32;
                }

                NativeByteBuffer data = new NativeByteBuffer(message.getObjectSize());
                message.serializeToStream(data);

                boolean updateDialog = true;
                if (message.action != null && message.action instanceof TLRPC.TL_messageEncryptedAction && !(message.action.encryptedAction instanceof TLRPC.TL_decryptedMessageActionSetMessageTTL || message.action.encryptedAction instanceof TLRPC.TL_decryptedMessageActionScreenshotMessages)) {
                    updateDialog = false;
                }

                if (updateDialog) {
                    TLRPC.Message lastMessage;
                    if (message.to_id.channel_id == 0 || MessageObject.isImportant(message)) {
                        lastMessage = messagesMap.get(message.dialog_id);
                        if (lastMessage == null || message.date > lastMessage.date) {
                            messagesMap.put(message.dialog_id, message);
                        }
                    } else if (message.to_id.channel_id != 0) {
                        lastMessage = messagesMap.get(message.dialog_id);
                        if (lastMessage == null || message.date > lastMessage.date) {
                            messagesMapNotImportant.put(message.dialog_id, message);
                        }
                    }
                }

                state.bindLong(1, messageId);
                state.bindLong(2, message.dialog_id);
                state.bindInteger(3, MessageObject.getUnreadFlags(message));
                state.bindInteger(4, message.send_state);
                state.bindInteger(5, message.date);
                state.bindByteBuffer(6, data);
                state.bindInteger(7, (MessageObject.isOut(message) ? 1 : 0));
                state.bindInteger(8, message.ttl);
                if ((message.flags & TLRPC.MESSAGE_FLAG_HAS_VIEWS) != 0) {
                    state.bindInteger(9, message.views);
                } else {
                    state.bindInteger(9, getMessageMediaType(message));
                }
                state.bindInteger(10, MessageObject.isImportant(message) ? 1 : 0);
                state.step();

                if (message.random_id != 0) {
                    state3.requery();
                    state3.bindLong(1, message.random_id);
                    state3.bindLong(2, messageId);
                    state3.step();
                }

                if (SharedMediaQuery.canAddMessageToMedia(message)) {
                    if (state2 == null) {
                        state2 = database.executeFast("REPLACE INTO media_v2 VALUES(?, ?, ?, ?, ?)");
                    }
                    state2.requery();
                    state2.bindLong(1, messageId);
                    state2.bindLong(2, message.dialog_id);
                    state2.bindInteger(3, message.date);
                    state2.bindInteger(4, SharedMediaQuery.getMediaType(message));
                    state2.bindByteBuffer(5, data);
                    state2.step();
                }

                if (message.media instanceof TLRPC.TL_messageMediaWebPage && message.media.webpage instanceof TLRPC.TL_webPagePending) {
                    state5.requery();
                    state5.bindLong(1, message.media.webpage.id);
                    state5.bindLong(2, messageId);
                    state5.step();
                }

                data.reuse();

                if ((message.to_id.channel_id == 0 || MessageObject.isImportant(message)) && message.date >= ConnectionsManager.getInstance().getCurrentTime() - 60 * 60 * 24 && downloadMask != 0) {
                    if (message.media instanceof TLRPC.TL_messageMediaAudio || message.media instanceof TLRPC.TL_messageMediaPhoto || message.media instanceof TLRPC.TL_messageMediaVideo || message.media instanceof TLRPC.TL_messageMediaDocument) {
                        int type = 0;
                        long id = 0;
                        TLObject object = null;
                        if (message.media instanceof TLRPC.TL_messageMediaAudio) {
                            if ((downloadMask & MediaController.AUTODOWNLOAD_MASK_AUDIO) != 0 && message.media.audio.size < 1024 * 1024 * 5) {
                                id = message.media.audio.id;
                                type = MediaController.AUTODOWNLOAD_MASK_AUDIO;
                                object = message.media.audio;
                            }
                        } else if (message.media instanceof TLRPC.TL_messageMediaPhoto) {
                            if ((downloadMask & MediaController.AUTODOWNLOAD_MASK_PHOTO) != 0) {
                                TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(message.media.photo.sizes, AndroidUtilities.getPhotoSize());
                                if (photoSize != null) {
                                    id = message.media.photo.id;
                                    type = MediaController.AUTODOWNLOAD_MASK_PHOTO;
                                    object = photoSize;
                                }
                            }
                        } else if (message.media instanceof TLRPC.TL_messageMediaVideo) {
                            if ((downloadMask & MediaController.AUTODOWNLOAD_MASK_VIDEO) != 0) {
                                id = message.media.video.id;
                                type = MediaController.AUTODOWNLOAD_MASK_VIDEO;
                                object = message.media.video;
                            }
                        } else if (message.media instanceof TLRPC.TL_messageMediaDocument) {
                            if ((downloadMask & MediaController.AUTODOWNLOAD_MASK_DOCUMENT) != 0) {
                                id = message.media.document.id;
                                type = MediaController.AUTODOWNLOAD_MASK_DOCUMENT;
                                object = message.media.document;
                            }
                        }
                        if (object != null) {
                            downloadMediaMask |= type;
                            state4.requery();
                            data = new NativeByteBuffer(object.getObjectSize());
                            object.serializeToStream(data);
                            state4.bindLong(1, id);
                            state4.bindInteger(2, type);
                            state4.bindInteger(3, message.date);
                            state4.bindByteBuffer(4, data);
                            state4.step();
                            data.reuse();
                        }
                    }
                }
            }
            state.dispose();
            if (state2 != null) {
                state2.dispose();
            }
            state3.dispose();
            state4.dispose();
            state5.dispose();

            state = database.executeFast("REPLACE INTO dialogs VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            HashMap<Long, TLRPC.Message> dids = new HashMap<>();
            dids.putAll(messagesMap);
            dids.putAll(messagesMapNotImportant);

            for (HashMap.Entry<Long, TLRPC.Message> pair : dids.entrySet()) {
                Long key = pair.getKey();
                if (key == 0) {
                    continue;
                }
                TLRPC.Message message = messagesMap.get(key);
                TLRPC.Message messageNotImportant = messagesMapNotImportant.get(key);

                int channelId = 0;
                if (message != null) {
                    channelId = message.to_id.channel_id;
                }
                if (messageNotImportant != null) {
                    channelId = messageNotImportant.to_id.channel_id;
                }

                SQLiteCursor cursor = database.queryFinalized("SELECT date, unread_count, last_mid_i, unread_count_i, pts, date_i, last_mid FROM dialogs WHERE did = " + key);
                int dialog_date = 0;
                int last_mid = 0;
                int old_unread_count = 0;
                int last_mid_i = 0;
                int old_unread_count_i = 0;
                int pts = channelId != 0 ? 1 : 0;
                int dialog_date_i = 0;
                if (cursor.next()) {
                    dialog_date = cursor.intValue(0);
                    old_unread_count = cursor.intValue(1);
                    last_mid_i = cursor.intValue(2);
                    old_unread_count_i = cursor.intValue(3);
                    pts = cursor.intValue(4);
                    dialog_date_i = cursor.intValue(5);
                    last_mid = cursor.intValue(6);
                } else if (channelId != 0) {
                    MessagesController.getInstance().checkChannelInviter(channelId);
                }
                cursor.dispose();

                state.requery();

                Integer unread_count = messagesCounts.get(key);
                if (unread_count == null) {
                    unread_count = 0;
                } else {
                    messagesCounts.put(key, unread_count + old_unread_count);
                }
                Integer unread_count_i = messagesCountsNotImportant.get(key);
                if (unread_count_i == null) {
                    unread_count_i = 0;
                } else {
                    messagesCountsNotImportant.put(key, unread_count_i + old_unread_count_i);
                }
                long messageId = message != null ? message.id : last_mid;
                if (message != null) {
                    if (message.local_id != 0) {
                        messageId = message.local_id;
                    }
                }
                long messageIdNotImportant = messageNotImportant != null ? messageNotImportant.id : last_mid_i;
                if (messageNotImportant != null) {
                    if (messageNotImportant.local_id != 0) {
                        messageIdNotImportant = messageNotImportant.local_id;
                    }
                }

                if (channelId != 0) {
                    messageId |= ((long) channelId) << 32;
                    messageIdNotImportant |= ((long) channelId) << 32;
                }

                state.bindLong(1, key);
                if (message != null && (!doNotUpdateDialogDate || dialog_date == 0)) {
                    state.bindInteger(2, message.date);
                } else {
                    state.bindInteger(2, dialog_date);
                }
                state.bindInteger(3, old_unread_count + unread_count);
                state.bindLong(4, messageId);
                state.bindInteger(5, 0);
                state.bindInteger(6, 0);
                state.bindLong(7, messageIdNotImportant);
                state.bindInteger(8, unread_count_i + old_unread_count_i);
                state.bindInteger(9, pts);
                if (messageNotImportant != null && (!doNotUpdateDialogDate || dialog_date == 0)) {
                    state.bindInteger(10, messageNotImportant.date);
                } else {
                    state.bindInteger(10, dialog_date_i);
                }
                state.step();
            }
            state.dispose();

            if (mediaCounts != null) {
                state3 = database.executeFast("REPLACE INTO media_counts_v2 VALUES(?, ?, ?)");
                for (HashMap.Entry<Integer, HashMap<Long, Integer>> counts : mediaCounts.entrySet()) {
                    Integer type = counts.getKey();
                    for (HashMap.Entry<Long, Integer> pair : counts.getValue().entrySet()) {
                        long uid = pair.getKey();
                        int lower_part = (int) uid;
                        int count = -1;
                        SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT count FROM media_counts_v2 WHERE uid = %d AND type = %d LIMIT 1", uid, type));
                        if (cursor.next()) {
                            count = cursor.intValue(0);
                        }
                        cursor.dispose();
                        if (count != -1) {
                            state3.requery();
                            count += pair.getValue();
                            state3.bindLong(1, uid);
                            state3.bindInteger(2, type);
                            state3.bindInteger(3, count);
                            state3.step();
                        }
                    }
                }
                state3.dispose();
            }
            if (withTransaction) {
                database.commitTransaction();
            }
            MessagesController.getInstance().processDialogsUpdateRead(messagesCounts);

            if (downloadMediaMask != 0) {
                final int downloadMediaMaskFinal = downloadMediaMask;
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        MediaController.getInstance().newDownloadObjectsAvailable(downloadMediaMaskFinal);
                    }
                });
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void putMessages(final ArrayList<TLRPC.Message> messages, final boolean withTransaction, boolean useQueue, final boolean doNotUpdateDialogDate, final int downloadMask) {
        if (messages.size() == 0) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask);
                }
            });
        } else {
            putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask);
        }
    }

    public void markMessageAsSendError(final TLRPC.Message message) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    long messageId = message.id;
                    if (message.to_id.channel_id != 0) {
                        messageId |= ((long) message.to_id.channel_id) << 32;
                    }
                    database.executeFast("UPDATE messages SET send_state = 2 WHERE mid = " + messageId).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    /*public void getHoleMessages() {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {

                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void clearHoleMessages(final int enc_id) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast("DELETE FROM secret_holes WHERE uid = " + enc_id).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void putHoleMessage(final int enc_id, final TLRPC.Message message) {
        if (message == null) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO secret_holes VALUES(?, ?, ?, ?)");

                    state.requery();
                    NativeByteBuffer data = new NativeByteBuffer(message.getObjectSize());
                    message.serializeToStream(data);
                    state.bindInteger(1, enc_id);
                    state.bindInteger(2, message.seq_in);
                    state.bindInteger(3, message.seq_out);
                    state.bindByteBuffer(4, data);
                    state.step();
                    data.reuse();

                    state.dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }*/

    public void setMessageSeq(final int mid, final int seq_in, final int seq_out) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO messages_seq VALUES(?, ?, ?)");
                    state.requery();
                    state.bindInteger(1, mid);
                    state.bindInteger(2, seq_in);
                    state.bindInteger(3, seq_out);
                    state.step();
                    state.dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    private long[] updateMessageStateAndIdInternal(long random_id, Integer _oldId, int newId, int date, int channelId) {
        SQLiteCursor cursor = null;
        long oldMessageId;
        long newMessageId = newId;

        if (_oldId == null) {
            try {
                cursor = database.queryFinalized(String.format(Locale.US, "SELECT mid FROM randoms WHERE random_id = %d LIMIT 1", random_id));
                if (cursor.next()) {
                    _oldId = cursor.intValue(0);
                }
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            if (_oldId == null) {
                return null;
            }
        }
        oldMessageId = _oldId;
        if (channelId != 0) {
            oldMessageId |= ((long) channelId) << 32;
            newMessageId |= ((long) channelId) << 32;
        }

        long did = 0;
        try {
            cursor = database.queryFinalized(String.format(Locale.US, "SELECT uid FROM messages WHERE mid = %d LIMIT 1", oldMessageId));
            if (cursor.next()) {
                did = cursor.longValue(0);
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }

        if (did == 0) {
            return null;
        }
        if (oldMessageId == newMessageId && date != 0) {
            SQLitePreparedStatement state = null;
            try {
                state = database.executeFast("UPDATE messages SET send_state = 0, date = ? WHERE mid = ?");
                state.bindInteger(1, date);
                state.bindLong(2, newMessageId);
                state.step();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            } finally {
                if (state != null) {
                    state.dispose();
                }
            }

            return new long[] {did, newId};
        } else {
            SQLitePreparedStatement state = null;
            try {
                state = database.executeFast("UPDATE messages SET mid = ?, send_state = 0 WHERE mid = ?");
                state.bindLong(1, newMessageId);
                state.bindLong(2, oldMessageId);
                state.step();
            } catch (Exception e) {
                try {
                    database.executeFast(String.format(Locale.US, "DELETE FROM messages WHERE mid = %d", oldMessageId)).stepThis().dispose();
                    database.executeFast(String.format(Locale.US, "DELETE FROM messages_seq WHERE mid = %d", oldMessageId)).stepThis().dispose();
                } catch (Exception e2) {
                    FileLog.e("tmessages", e2);
                }
                FileLog.e("tmessages", e);
            } finally {
                if (state != null) {
                    state.dispose();
                    state = null;
                }
            }

            try {
                state = database.executeFast("UPDATE media_v2 SET mid = ? WHERE mid = ?");
                state.bindLong(1, newMessageId);
                state.bindLong(2, oldMessageId);
                state.step();
            } catch (Exception e) {
                try {
                    database.executeFast(String.format(Locale.US, "DELETE FROM media_v2 WHERE mid = %d", oldMessageId)).stepThis().dispose();
                } catch (Exception e2) {
                    FileLog.e("tmessages", e2);
                }
                FileLog.e("tmessages", e);
            } finally {
                if (state != null) {
                    state.dispose();
                    state = null;
                }
            }

            try {
                state = database.executeFast("UPDATE dialogs SET last_mid = ? WHERE last_mid = ?");
                state.bindLong(1, newMessageId);
                state.bindLong(2, oldMessageId);
                state.step();
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            } finally {
                if (state != null) {
                    state.dispose();
                }
            }

            return new long[] {did, _oldId};
        }
    }

    public long[] updateMessageStateAndId(final long random_id, final Integer _oldId, final int newId, final int date, boolean useQueue, final int channelId) {
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    updateMessageStateAndIdInternal(random_id, _oldId, newId, date, channelId);
                }
            });
        } else {
            return updateMessageStateAndIdInternal(random_id, _oldId, newId, date, channelId);
        }
        return null;
    }

    private void updateUsersInternal(final ArrayList<TLRPC.User> users, final boolean onlyStatus, final boolean withTransaction) {
        if (Thread.currentThread().getId() != storageQueue.getId()) {
            throw new RuntimeException("wrong db thread");
        }
        try {
            if (onlyStatus) {
                if (withTransaction) {
                    database.beginTransaction();
                }
                SQLitePreparedStatement state = database.executeFast("UPDATE users SET status = ? WHERE uid = ?");
                for (TLRPC.User user : users) {
                    state.requery();
                    if (user.status != null) {
                        state.bindInteger(1, user.status.expires);
                    } else {
                        state.bindInteger(1, 0);
                    }
                    state.bindInteger(2, user.id);
                    state.step();
                }
                state.dispose();
                if (withTransaction) {
                    database.commitTransaction();
                }
            } else {
                StringBuilder ids = new StringBuilder();
                HashMap<Integer, TLRPC.User> usersDict = new HashMap<>();
                for (TLRPC.User user : users) {
                    if (ids.length() != 0) {
                        ids.append(",");
                    }
                    ids.append(user.id);
                    usersDict.put(user.id, user);
                }
                ArrayList<TLRPC.User> loadedUsers = new ArrayList<>();
                getUsersInternal(ids.toString(), loadedUsers);
                for (TLRPC.User user : loadedUsers) {
                    TLRPC.User updateUser = usersDict.get(user.id);
                    if (updateUser != null) {
                        if (updateUser.first_name != null && updateUser.last_name != null) {
                            if (!UserObject.isContact(user)) {
                                user.first_name = updateUser.first_name;
                                user.last_name = updateUser.last_name;
                            }
                            user.username = updateUser.username;
                        } else if (updateUser.photo != null) {
                            user.photo = updateUser.photo;
                        } else if (updateUser.phone != null) {
                            user.phone = updateUser.phone;
                        }
                    }
                }

                if (!loadedUsers.isEmpty()) {
                    if (withTransaction) {
                        database.beginTransaction();
                    }
                    putUsersInternal(loadedUsers);
                    if (withTransaction) {
                        database.commitTransaction();
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void updateUsers(final ArrayList<TLRPC.User> users, final boolean onlyStatus, final boolean withTransaction, boolean useQueue) {
        if (users.isEmpty()) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    updateUsersInternal(users, onlyStatus, withTransaction);
                }
            });
        } else {
            updateUsersInternal(users, onlyStatus, withTransaction);
        }
    }

    private void markMessagesAsReadInternal(SparseArray<Long> inbox, SparseIntArray outbox, HashMap<Integer, Integer> encryptedMessages) {
        try {
            if (inbox != null) {
                for (int b = 0; b < inbox.size(); b++) {
                    int key = inbox.keyAt(b);
                    long messageId = inbox.get(key);
                    database.executeFast(String.format(Locale.US, "UPDATE messages SET read_state = read_state | 1 WHERE uid = %d AND mid > 0 AND mid <= %d AND read_state IN(0,2) AND out = 0", key, messageId)).stepThis().dispose();
                }
            }
            if (outbox != null) {
                for (int b = 0; b < outbox.size(); b++) {
                    int key = outbox.keyAt(b);
                    int messageId = outbox.get(key);
                    database.executeFast(String.format(Locale.US, "UPDATE messages SET read_state = read_state | 1 WHERE uid = %d AND mid > 0 AND mid <= %d AND read_state IN(0,2) AND out = 1", key, messageId)).stepThis().dispose();
                }
            }
            if (encryptedMessages != null && !encryptedMessages.isEmpty()) {
                for (HashMap.Entry<Integer, Integer> entry : encryptedMessages.entrySet()) {
                    long dialog_id = ((long)entry.getKey()) << 32;
                    int max_date = entry.getValue();
                    SQLitePreparedStatement state = database.executeFast("UPDATE messages SET read_state = read_state | 1 WHERE uid = ? AND date <= ? AND read_state IN(0,2) AND out = 1");
                    state.requery();
                    state.bindLong(1, dialog_id);
                    state.bindInteger(2, max_date);
                    state.step();
                    state.dispose();
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void markMessagesContentAsRead(final ArrayList<Long> mids) {
        if (mids == null || mids.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    database.executeFast(String.format(Locale.US, "UPDATE messages SET read_state = read_state | 2 WHERE mid IN (%s)", TextUtils.join(",", mids))).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void markMessagesAsRead(final SparseArray<Long> inbox, final SparseIntArray outbox, final HashMap<Integer, Integer> encryptedMessages, boolean useQueue) {
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    markMessagesAsReadInternal(inbox, outbox, encryptedMessages);
                }
            });
        } else {
            markMessagesAsReadInternal(inbox, outbox, encryptedMessages);
        }
    }

    public void markMessagesAsDeletedByRandoms(final ArrayList<Long> messages) {
        if (messages.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    String ids = TextUtils.join(",", messages);
                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT mid FROM randoms WHERE random_id IN(%s)", ids));
                    final ArrayList<Integer> mids = new ArrayList<>();
                    while (cursor.next()) {
                        mids.add(cursor.intValue(0));
                    }
                    cursor.dispose();
                    if (!mids.isEmpty()) {
                        AndroidUtilities.runOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                NotificationCenter.getInstance().postNotificationName(NotificationCenter.messagesDeleted, mids, 0);
                            }
                        });
                        MessagesStorage.getInstance().updateDialogsWithReadMessagesInternal(mids, null);
                        MessagesStorage.getInstance().markMessagesAsDeletedInternal(mids, 0);
                        MessagesStorage.getInstance().updateDialogsWithDeletedMessagesInternal(mids, 0);
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    private void markMessagesAsDeletedInternal(final ArrayList<Integer> messages, int channelId) {
        try {
            String ids;
            if (channelId != 0) {
                StringBuilder builder = new StringBuilder(messages.size());
                for (int a = 0; a < messages.size(); a++) {
                    long messageId = messages.get(a);
                    messageId |= ((long) channelId) << 32;
                    if (builder.length() > 0) {
                        builder.append(',');
                    }
                    builder.append(messageId);
                }
                ids = builder.toString();
            } else {
                ids = TextUtils.join(",", messages);
            }
            SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT uid, data FROM messages WHERE mid IN(%s)", ids));
            ArrayList<File> filesToDelete = new ArrayList<>();
            try {
                while (cursor.next()) {
                    long did = cursor.longValue(0);
                    if ((int)did != 0) {
                        continue;
                    }
                    NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(1));
                    if (data != null && cursor.byteBufferValue(1, data) != 0) {
                        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        if (message == null || message.media == null) {
                            continue;
                        }
                        if (message.media instanceof TLRPC.TL_messageMediaAudio) {
                            File file = FileLoader.getPathToAttach(message.media.audio);
                            if (file != null && file.toString().length() > 0) {
                                filesToDelete.add(file);
                            }
                        } else if (message.media instanceof TLRPC.TL_messageMediaPhoto) {
                            for (TLRPC.PhotoSize photoSize : message.media.photo.sizes) {
                                File file = FileLoader.getPathToAttach(photoSize);
                                if (file != null && file.toString().length() > 0) {
                                    filesToDelete.add(file);
                                }
                            }
                        } else if (message.media instanceof TLRPC.TL_messageMediaVideo) {
                            File file = FileLoader.getPathToAttach(message.media.video);
                            if (file != null && file.toString().length() > 0) {
                                filesToDelete.add(file);
                            }
                            file = FileLoader.getPathToAttach(message.media.video.thumb);
                            if (file != null && file.toString().length() > 0) {
                                filesToDelete.add(file);
                            }
                        } else if (message.media instanceof TLRPC.TL_messageMediaDocument) {
                            File file = FileLoader.getPathToAttach(message.media.document);
                            if (file != null && file.toString().length() > 0) {
                                filesToDelete.add(file);
                            }
                            file = FileLoader.getPathToAttach(message.media.document.thumb);
                            if (file != null && file.toString().length() > 0) {
                                filesToDelete.add(file);
                            }
                        }
                    }
                    data.reuse();
                }
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
            cursor.dispose();
            FileLoader.getInstance().deleteFiles(filesToDelete);
            database.executeFast(String.format(Locale.US, "DELETE FROM messages WHERE mid IN(%s)", ids)).stepThis().dispose();
            database.executeFast(String.format(Locale.US, "DELETE FROM bot_keyboard WHERE mid IN(%s)", ids)).stepThis().dispose();
            database.executeFast(String.format(Locale.US, "DELETE FROM messages_seq WHERE mid IN(%s)", ids)).stepThis().dispose();
            database.executeFast(String.format(Locale.US, "DELETE FROM media_v2 WHERE mid IN(%s)", ids)).stepThis().dispose();
            database.executeFast("DELETE FROM media_counts_v2 WHERE 1").stepThis().dispose();
            BotQuery.clearBotKeyboard(0, messages);
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    private void updateDialogsWithDeletedMessagesInternal(final ArrayList<Integer> messages, int channelId) {
        if (Thread.currentThread().getId() != storageQueue.getId()) {
            throw new RuntimeException("wrong db thread");
        }
        try {
            String ids;
            if (!messages.isEmpty()) {

                if (channelId != 0) {
                    StringBuilder builder = new StringBuilder(messages.size());
                    for (int a = 0; a < messages.size(); a++) {
                        long messageId = messages.get(a);
                        messageId |= ((long) channelId) << 32;
                        if (builder.length() > 0) {
                            builder.append(',');
                        }
                        builder.append(messageId);
                    }
                    ids = builder.toString();
                } else {
                    ids = TextUtils.join(",", messages);
                }

                SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT did FROM dialogs WHERE last_mid IN(%s)", ids));
                ArrayList<Long> dialogsToUpdate = new ArrayList<>();
                while (cursor.next()) {
                    dialogsToUpdate.add(cursor.longValue(0));
                }
                cursor.dispose();
                database.beginTransaction();
                SQLitePreparedStatement state = database.executeFast("UPDATE dialogs SET unread_count = 0, last_mid = (SELECT mid FROM messages WHERE uid = ? AND date = (SELECT MAX(date) FROM messages WHERE uid = ? )) WHERE did = ?");
                for (long did : dialogsToUpdate) {
                    state.requery();
                    state.bindLong(1, did);
                    state.bindLong(2, did);
                    state.bindLong(3, did);
                    state.step();
                }
                state.dispose();
                database.commitTransaction();

                ids = TextUtils.join(",", dialogsToUpdate);
            } else {
                ids = "" + (-channelId);
            }

            TLRPC.messages_Dialogs dialogs = new TLRPC.messages_Dialogs();
            ArrayList<TLRPC.EncryptedChat> encryptedChats = new ArrayList<>();
            ArrayList<Integer> usersToLoad = new ArrayList<>();
            ArrayList<Integer> chatsToLoad = new ArrayList<>();
            ArrayList<Integer> encryptedToLoad = new ArrayList<>();
            SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT d.did, d.last_mid, d.unread_count, d.date, m.data, m.read_state, m.mid, m.send_state, m.date, d.last_mid_i, d.unread_count_i, d.pts FROM dialogs as d LEFT JOIN messages as m ON d.last_mid = m.mid WHERE d.did IN(%s)", ids));
            while (cursor.next()) {
                TLRPC.Dialog dialog;
                if (channelId == 0) {
                    dialog = new TLRPC.TL_dialog();
                } else {
                    dialog = new TLRPC.TL_dialogChannel();
                }
                dialog.id = cursor.longValue(0);
                dialog.top_message = cursor.intValue(1);
                dialog.unread_count = cursor.intValue(2);
                dialog.last_message_date = cursor.intValue(3);
                dialog.pts = cursor.intValue(11);
                dialog.top_not_important_message = cursor.intValue(9);
                dialog.unread_not_important_count = cursor.intValue(10);

                dialogs.dialogs.add(dialog);

                NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(4));
                if (data != null && cursor.byteBufferValue(4, data) != 0) {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    MessageObject.setUnreadFlags(message, cursor.intValue(5));
                    message.id = cursor.intValue(6);
                    message.send_state = cursor.intValue(7);
                    int date = cursor.intValue(8);
                    if (date != 0) {
                        dialog.last_message_date = date;
                    }
                    message.dialog_id = dialog.id;
                    dialogs.messages.add(message);

                    if (message.from_id > 0) {
                        if (!usersToLoad.contains(message.from_id)) {
                            usersToLoad.add(message.from_id);
                        }
                    } else {
                        if (!chatsToLoad.contains(-message.from_id)) {
                            chatsToLoad.add(-message.from_id);
                        }
                    }
                    if (message.action != null && message.action.user_id != 0) {
                        if (!usersToLoad.contains(message.action.user_id)) {
                            usersToLoad.add(message.action.user_id);
                        }
                    }
                    if (message.fwd_from_id instanceof TLRPC.TL_peerUser) {
                        if (!usersToLoad.contains(message.fwd_from_id.user_id)) {
                            usersToLoad.add(message.fwd_from_id.user_id);
                        }
                    } else if (message.fwd_from_id instanceof TLRPC.TL_peerChannel) {
                        if (!chatsToLoad.contains(message.fwd_from_id.channel_id)) {
                            chatsToLoad.add(message.fwd_from_id.channel_id);
                        }
                    }
                }
                data.reuse();

                int lower_id = (int)dialog.id;
                int high_id = (int)(dialog.id >> 32);
                if (lower_id != 0) {
                    if (high_id == 1) {
                        if (!chatsToLoad.contains(lower_id)) {
                            chatsToLoad.add(lower_id);
                        }
                    } else {
                        if (lower_id > 0) {
                            if (!usersToLoad.contains(lower_id)) {
                                usersToLoad.add(lower_id);
                            }
                        } else {
                            if (!chatsToLoad.contains(-lower_id)) {
                                chatsToLoad.add(-lower_id);
                            }
                        }
                    }
                } else {
                    if (!encryptedToLoad.contains(high_id)) {
                        encryptedToLoad.add(high_id);
                    }
                }
            }
            cursor.dispose();

            if (!encryptedToLoad.isEmpty()) {
                getEncryptedChatsInternal(TextUtils.join(",", encryptedToLoad), encryptedChats, usersToLoad);
            }

            if (!chatsToLoad.isEmpty()) {
                getChatsInternal(TextUtils.join(",", chatsToLoad), dialogs.chats);
            }

            if (!usersToLoad.isEmpty()) {
                getUsersInternal(TextUtils.join(",", usersToLoad), dialogs.users);
            }

            if (!dialogs.dialogs.isEmpty() || !encryptedChats.isEmpty()) {
                MessagesController.getInstance().processDialogsUpdate(dialogs, encryptedChats);
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void updateDialogsWithDeletedMessages(final ArrayList<Integer> messages, boolean useQueue, final int channelId) {
        if (messages.isEmpty() && channelId == 0) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    updateDialogsWithDeletedMessagesInternal(messages, channelId);
                }
            });
        } else {
            updateDialogsWithDeletedMessagesInternal(messages, channelId);
        }
    }

    public void markMessagesAsDeleted(final ArrayList<Integer> messages, boolean useQueue, final int channelId) {
        if (messages.isEmpty()) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    markMessagesAsDeletedInternal(messages, channelId);
                }
            });
        } else {
            markMessagesAsDeletedInternal(messages, channelId);
        }
    }

    private void fixUnsupportedMedia(TLRPC.Message message) {
        if (message == null) {
            return;
        }
        boolean ok = false;
        if (message.media instanceof TLRPC.TL_messageMediaUnsupported_old) {
            if (message.media.bytes.length == 0) {
                message.media.bytes = new byte[1];
                message.media.bytes[0] = TLRPC.LAYER;
            }
        } else if (message.media instanceof TLRPC.TL_messageMediaUnsupported) {
            message.media = new TLRPC.TL_messageMediaUnsupported_old();
            message.media.bytes = new byte[1];
            message.media.bytes[0] = TLRPC.LAYER;
            message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        }
    }

    private void doneHolesInTable(String table, long did, int max_id) throws Exception {
        if (max_id == 0) {
            database.executeFast(String.format(Locale.US, "DELETE FROM " + table + " WHERE uid = %d", did)).stepThis().dispose();
        } else {
            database.executeFast(String.format(Locale.US, "DELETE FROM " + table + " WHERE uid = %d AND start = 0", did)).stepThis().dispose();
        }
        SQLitePreparedStatement state = database.executeFast("REPLACE INTO " + table + " VALUES(?, ?, ?)");
        state.requery();
        state.bindLong(1, did);
        state.bindInteger(2, 1);
        state.bindInteger(3, 1);
        state.step();
        state.dispose();
    }

    public void doneHolesInMedia(long did, int max_id, int type) throws Exception {
        if (type == -1) {
            if (max_id == 0) {
                database.executeFast(String.format(Locale.US, "DELETE FROM media_holes_v2 WHERE uid = %d", did)).stepThis().dispose();
            } else {
                database.executeFast(String.format(Locale.US, "DELETE FROM media_holes_v2 WHERE uid = %d AND start = 0", did)).stepThis().dispose();
            }
            SQLitePreparedStatement state = database.executeFast("REPLACE INTO media_holes_v2 VALUES(?, ?, ?, ?)");
            for (int a = 0; a < SharedMediaQuery.MEDIA_TYPES_COUNT; a++) {
                state.requery();
                state.bindLong(1, did);
                state.bindInteger(2, a);
                state.bindInteger(3, 1);
                state.bindInteger(4, 1);
                state.step();
            }
            state.dispose();
        } else {
            if (max_id == 0) {
                database.executeFast(String.format(Locale.US, "DELETE FROM media_holes_v2 WHERE uid = %d AND type = %d", did, type)).stepThis().dispose();
            } else {
                database.executeFast(String.format(Locale.US, "DELETE FROM media_holes_v2 WHERE uid = %d AND type = %d AND start = 0", did, type)).stepThis().dispose();
            }
            SQLitePreparedStatement state = database.executeFast("REPLACE INTO media_holes_v2 VALUES(?, ?, ?, ?)");
            state.requery();
            state.bindLong(1, did);
            state.bindInteger(2, type);
            state.bindInteger(3, 1);
            state.bindInteger(4, 1);
            state.step();
            state.dispose();
        }
    }

    private class Hole {

        public Hole(int s, int e) {
            start = s;
            end = e;
        }

        public Hole(int t, int s, int e) {
            type = t;
            start = s;
            end = e;
        }

        public int start;
        public int end;
        public int type;
    }

    public void closeHolesInMedia(long did, int minId, int maxId, int type) throws Exception {
        try {
            boolean ok = false;
            SQLiteCursor cursor;
            if (type < 0) {
                cursor = database.queryFinalized(String.format(Locale.US, "SELECT type, start, end FROM media_holes_v2 WHERE uid = %d AND type >= 0 AND ((end >= %d AND end <= %d) OR (start >= %d AND start <= %d) OR (start >= %d AND end <= %d) OR (start <= %d AND end >= %d))", did, minId, maxId, minId, maxId, minId, maxId, minId, maxId));
            } else {
                cursor = database.queryFinalized(String.format(Locale.US, "SELECT type, start, end FROM media_holes_v2 WHERE uid = %d AND type = %d AND ((end >= %d AND end <= %d) OR (start >= %d AND start <= %d) OR (start >= %d AND end <= %d) OR (start <= %d AND end >= %d))", did, type, minId, maxId, minId, maxId, minId, maxId, minId, maxId));
            }
            ArrayList<Hole> holes = null;
            while (cursor.next()) {
                if (holes == null) {
                    holes = new ArrayList<>();
                }
                int holeType = cursor.intValue(0);
                int start = cursor.intValue(1);
                int end = cursor.intValue(2);
                if (start == end && start == 1) {
                    continue;
                }
                holes.add(new Hole(holeType, start, end));
            }
            cursor.dispose();
            if (holes != null) {
                for (int a = 0; a < holes.size(); a++) {
                    Hole hole = holes.get(a);
                    if (maxId >= hole.end - 1 && minId <= hole.start + 1) {
                        database.executeFast(String.format(Locale.US, "DELETE FROM media_holes_v2 WHERE uid = %d AND type = %d AND start = %d AND end = %d", did, hole.type, hole.start, hole.end)).stepThis().dispose();
                    } else if (maxId >= hole.end - 1) {
                        if (hole.end != minId) {
                            try {
                                database.executeFast(String.format(Locale.US, "UPDATE media_holes_v2 SET end = %d WHERE uid = %d AND type = %d AND start = %d AND end = %d", minId, did, hole.type, hole.start, hole.end)).stepThis().dispose();
                            } catch (Exception e) {
                                FileLog.e("tmessages", e);
                            }
                        }
                    } else if (minId <= hole.start + 1) {
                        if (hole.start != maxId) {
                            try {
                                database.executeFast(String.format(Locale.US, "UPDATE media_holes_v2 SET start = %d WHERE uid = %d AND type = %d AND start = %d AND end = %d", maxId, did, hole.type, hole.start, hole.end)).stepThis().dispose();
                            } catch (Exception e) {
                                FileLog.e("tmessages", e);
                            }
                        }
                    } else {
                        database.executeFast(String.format(Locale.US, "DELETE FROM media_holes_v2 WHERE uid = %d AND type = %d AND start = %d AND end = %d", did, hole.type, hole.start, hole.end)).stepThis().dispose();
                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO media_holes_v2 VALUES(?, ?, ?, ?)");
                        state.requery();
                        state.bindLong(1, did);
                        state.bindInteger(2, hole.type);
                        state.bindInteger(3, hole.start);
                        state.bindInteger(4, minId);
                        state.step();
                        state.requery();
                        state.bindLong(1, did);
                        state.bindInteger(2, hole.type);
                        state.bindInteger(3, maxId);
                        state.bindInteger(4, hole.end);
                        state.step();
                        state.dispose();
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    private void closeHolesInTable(String table, long did, int minId, int maxId) throws Exception {
        try {
            boolean ok = false;
            SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT start, end FROM " + table + " WHERE uid = %d AND ((end >= %d AND end <= %d) OR (start >= %d AND start <= %d) OR (start >= %d AND end <= %d) OR (start <= %d AND end >= %d))", did, minId, maxId, minId, maxId, minId, maxId, minId, maxId));
            ArrayList<Hole> holes = null;
            while (cursor.next()) {
                if (holes == null) {
                    holes = new ArrayList<>();
                }
                int start = cursor.intValue(0);
                int end = cursor.intValue(1);
                if (start == end && start == 1) {
                    continue;
                }
                holes.add(new Hole(start, end));
            }
            cursor.dispose();
            if (holes != null) {
                for (int a = 0; a < holes.size(); a++) {
                    Hole hole = holes.get(a);
                    if (maxId >= hole.end - 1 && minId <= hole.start + 1) {
                        database.executeFast(String.format(Locale.US, "DELETE FROM " + table + " WHERE uid = %d AND start = %d AND end = %d", did, hole.start, hole.end)).stepThis().dispose();
                    } else if (maxId >= hole.end - 1) {
                        if (hole.end != minId) {
                            try {
                                database.executeFast(String.format(Locale.US, "UPDATE " + table + " SET end = %d WHERE uid = %d AND start = %d AND end = %d", minId, did, hole.start, hole.end)).stepThis().dispose();
                            } catch (Exception e) {
                                FileLog.e("tmessages", e);
                            }
                        }
                    } else if (minId <= hole.start + 1) {
                        if (hole.start != maxId) {
                            try {
                                database.executeFast(String.format(Locale.US, "UPDATE " + table + " SET start = %d WHERE uid = %d AND start = %d AND end = %d", maxId, did, hole.start, hole.end)).stepThis().dispose(); //TODO check
                            } catch (Exception e) {
                                FileLog.e("tmessages", e);
                            }
                        }
                    } else {
                        database.executeFast(String.format(Locale.US, "DELETE FROM " + table + " WHERE uid = %d AND start = %d AND end = %d", did, hole.start, hole.end)).stepThis().dispose();
                        SQLitePreparedStatement state = database.executeFast("REPLACE INTO " + table + " VALUES(?, ?, ?)");
                        state.requery();
                        state.bindLong(1, did);
                        state.bindInteger(2, hole.start);
                        state.bindInteger(3, minId);
                        state.step();
                        state.requery();
                        state.bindLong(1, did);
                        state.bindInteger(2, maxId);
                        state.bindInteger(3, hole.end);
                        state.step();
                        state.dispose();
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void applyNewChannelsGroups(SparseArray<ArrayList<TLRPC.TL_messageGroup>> channelsGroups) {
        //TODO ?
    }

    public void putMessages(final TLRPC.messages_Messages messages, final long dialog_id, final int load_type, final int max_id, final int important, final boolean createDialog) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (messages.messages.isEmpty()) {
                        if (load_type == 0) {
                            if (important != 2) {
                                doneHolesInTable("messages_holes", dialog_id, max_id);
                                doneHolesInMedia(dialog_id, max_id, -1);
                            }
                            if (important != 0) {
                                doneHolesInTable("messages_imp_holes", dialog_id, max_id);
                            }
                        }
                        return;
                    }
                    database.beginTransaction();

                    if (!messages.collapsed.isEmpty() && important == 2) {
                        int maxId, minId;
                        int count = messages.collapsed.size();
                        for (int a = 0; a < count; a++) {
                            TLRPC.TL_messageGroup group = messages.collapsed.get(a);
                            if (a < count - 1) {
                                minId = group.max_id;
                                maxId = messages.collapsed.get(a + 1).min_id;
                                closeHolesInTable("messages_holes", dialog_id, minId, maxId);
                                closeHolesInMedia(dialog_id, minId, maxId, -1);
                            }
                            if (a == 0) {
                                minId = messages.messages.get(messages.messages.size() - 1).id;
                                maxId = minId > group.min_id ? group.max_id : group.min_id;
                                closeHolesInTable("messages_holes", dialog_id, minId, maxId);
                                closeHolesInMedia(dialog_id, minId, maxId, -1);
                            }
                            if (a == count - 1) {
                                maxId = messages.messages.get(0).id;
                                minId = maxId < group.max_id ? group.min_id : group.max_id;
                                closeHolesInTable("messages_holes", dialog_id, minId, maxId);
                                closeHolesInMedia(dialog_id, minId, maxId, -1);
                            }
                        }
                    }
                    if (load_type == 0) {
                        int minId = messages.messages.get(messages.messages.size() - 1).id;
                        if (important != 2 || messages.collapsed.isEmpty()) {
                            closeHolesInTable("messages_holes", dialog_id, minId, max_id);
                            closeHolesInMedia(dialog_id, minId, max_id, -1);
                        }
                        if (important != 0) {
                            closeHolesInTable("messages_imp_holes", dialog_id, minId, max_id);
                        }
                    } else if (load_type == 1 || messages.collapsed.isEmpty()) {
                        int maxId = messages.messages.get(0).id;
                        if (important != 2) {
                            closeHolesInTable("messages_holes", dialog_id, max_id, maxId);
                            closeHolesInMedia(dialog_id, max_id, maxId, -1);
                        }
                        if (important != 0) {
                            closeHolesInTable("messages_imp_holes", dialog_id, max_id, maxId);
                        }
                    } else if (load_type == 3 || load_type == 2) {
                        int maxId = max_id == 0 ? Integer.MAX_VALUE : messages.messages.get(0).id;
                        int minId = messages.messages.get(messages.messages.size() - 1).id;
                        if (important != 2 || messages.collapsed.isEmpty()) {
                            closeHolesInTable("messages_holes", dialog_id, minId, maxId);
                            closeHolesInMedia(dialog_id, minId, maxId, -1);
                        }
                        if (important != 0) {
                            closeHolesInTable("messages_imp_holes", dialog_id, minId, maxId);
                        }
                    }
                    int count = messages.messages.size();

                    //load_type == 0 ? backward loading
                    //load_type == 1 ? forward loading
                    //load_type == 2 ? load from first unread
                    //load_type == 3 ? load around message

                    SQLitePreparedStatement state = database.executeFast("REPLACE INTO messages VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)");
                    SQLitePreparedStatement state2 = database.executeFast("REPLACE INTO media_v2 VALUES(?, ?, ?, ?, ?)");
                    TLRPC.Message botKeyboard = null;
                    int countBeforeImportant = 0;
                    int countAfterImportant = 0;
                    int minChannelMessageId = Integer.MAX_VALUE;
                    int maxChannelMessageId = 0;
                    int lastChannelImportantId = -1;
                    int channelId = 0;
                    for (int a = 0; a < count; a++) {
                        TLRPC.Message message = messages.messages.get(a);

                        long messageId = message.id;
                        if (channelId == 0) {
                            channelId = message.to_id.channel_id;
                        }
                        if (message.to_id.channel_id != 0) {
                            messageId |= ((long) channelId) << 32;
                        }

                        if (a == 0 && createDialog) {
                            SQLitePreparedStatement state3 = database.executeFast("REPLACE INTO dialogs VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                            state3.bindLong(1, dialog_id);
                            state3.bindInteger(2, message.date);
                            state3.bindInteger(3, 0);
                            state3.bindLong(4, messageId);
                            state3.bindInteger(5, message.id);
                            state3.bindInteger(6, 0);
                            state3.bindLong(7, messageId);
                            state3.bindInteger(8, 0);
                            state3.bindInteger(9, messages.pts);
                            state3.bindInteger(10, message.date);
                            state3.step();
                            state3.dispose();
                        }

                        boolean isImportant = MessageObject.isImportant(message);
                        if (load_type != -1 && important == 1) {
                            if (isImportant) {
                                minChannelMessageId = Math.min(minChannelMessageId, message.id);
                                maxChannelMessageId = Math.max(maxChannelMessageId, message.id);
                                if (lastChannelImportantId == -1) {
                                    countBeforeImportant = countAfterImportant;
                                } else {
                                    if (countAfterImportant != 0) {
                                        TLRPC.TL_messageGroup group = new TLRPC.TL_messageGroup();
                                        group.max_id = lastChannelImportantId;
                                        group.min_id = message.id;
                                        group.count = countAfterImportant;
                                        messages.collapsed.add(group);
                                    }
                                }
                                countAfterImportant = 0;
                                lastChannelImportantId = message.id;
                            } else {
                                countAfterImportant++;
                            }
                        }

                        fixUnsupportedMedia(message);
                        state.requery();
                        NativeByteBuffer data = new NativeByteBuffer(message.getObjectSize());
                        message.serializeToStream(data);
                        state.bindLong(1, messageId);
                        state.bindLong(2, dialog_id);
                        state.bindInteger(3, MessageObject.getUnreadFlags(message));
                        state.bindInteger(4, message.send_state);
                        state.bindInteger(5, message.date);
                        state.bindByteBuffer(6, data);
                        state.bindInteger(7, (MessageObject.isOut(message) ? 1 : 0));
                        state.bindInteger(8, 0);
                        if ((message.flags & TLRPC.MESSAGE_FLAG_HAS_VIEWS) != 0) {
                            state.bindInteger(9, message.views);
                        } else {
                            state.bindInteger(9, 0);
                        }
                        state.bindInteger(10, isImportant ? 1 : 0);
                        state.step();

                        if (SharedMediaQuery.canAddMessageToMedia(message)) {
                            state2.requery();
                            state2.bindLong(1, messageId);
                            state2.bindLong(2, dialog_id);
                            state2.bindInteger(3, message.date);
                            state2.bindInteger(4, SharedMediaQuery.getMediaType(message));
                            state2.bindByteBuffer(5, data);
                            state2.step();
                        }
                        data.reuse();

                        if (message.reply_markup != null && ((message.reply_markup.flags & 4) == 0 || (message.flags & 16) != 0)) {
                            if (botKeyboard == null || botKeyboard.id < message.id) {
                                botKeyboard = message;
                            }
                        }
                    }
                    state.dispose();
                    state2.dispose();
                    if (botKeyboard != null) {
                        BotQuery.putBotKeyboard(dialog_id, botKeyboard);
                    }

                    if (load_type != -1 && important != 0) {
                        /*if ((messages.flags & 1) == 0) {
                            if (countBeforeImportant != 0) {
                                if (load_type == 0) {
                                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT start, count FROM channel_group WHERE uid = %d AND start <= %d ORDER BY start ASC LIMIT 1", dialog_id, maxChannelMessageId));
                                    if (cursor.next()) {
                                        int currentStart = cursor.intValue(0);
                                        int currentCount = cursor.intValue(1);
                                        database.executeFast(String.format(Locale.US, "UPDATE channel_group SET start = %d, count = %d WHERE uid = %d AND start = %d", maxChannelMessageId, cursor.intValue(1) + countBeforeImportant, dialog_id, cursor.intValue(0))).stepThis().dispose();
                                    } else {
                                        TLRPC.TL_messageGroup group = new TLRPC.TL_messageGroup();
                                        group.max_id = max_id != 0 ? max_id : Integer.MAX_VALUE;
                                        group.min_id = maxChannelMessageId;
                                        group.count = countBeforeImportant;
                                        messages.collapsed.add(group);
                                    }
                                    cursor.dispose();
                                }
                            }
                            if (countAfterImportant != 0) {
                                if (load_type == 0) {
                                    TLRPC.TL_messageGroup group = new TLRPC.TL_messageGroup();
                                    group.max_id = minChannelMessageId;
                                    group.min_id = 0;
                                    group.count = countBeforeImportant;
                                    messages.collapsed.add(group);
                                }
                            }
                        }*/
                        if (!messages.collapsed.isEmpty()) {
                            state = database.executeFast("REPLACE INTO channel_group VALUES(?, ?, ?, ?)");
                            for (int a = 0; a < messages.collapsed.size(); a++) {
                                TLRPC.TL_messageGroup group = messages.collapsed.get(a);
                                if (group.min_id > group.max_id) {
                                    int temp = group.min_id;
                                    group.min_id = group.max_id;
                                    group.max_id = temp;
                                }
                                state.requery();
                                state.bindLong(1, dialog_id);
                                state.bindInteger(2, group.min_id);
                                state.bindInteger(3, group.max_id);
                                state.bindInteger(4, group.count);
                                state.step();
                            }
                            state.dispose();
                        }
                        if (important == 1) {
                            messages.collapsed.clear();
                        }
                    }

                    putUsersInternal(messages.users);
                    putChatsInternal(messages.chats);

                    database.commitTransaction();

                    if (createDialog) {
                        MessagesStorage.getInstance().updateDialogsWithDeletedMessages(new ArrayList<Integer>(), false, channelId);
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void getDialogs(final int cacheOffset, final int offset, final int serverOffset, final int count) {
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                TLRPC.messages_Dialogs dialogs = new TLRPC.messages_Dialogs();
                ArrayList<TLRPC.EncryptedChat> encryptedChats = new ArrayList<>();
                try {
                    ArrayList<Integer> usersToLoad = new ArrayList<>();
                    usersToLoad.add(UserConfig.getClientUserId());
                    ArrayList<Integer> chatsToLoad = new ArrayList<>();
                    ArrayList<Integer> encryptedToLoad = new ArrayList<>();
                    SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT d.did, d.last_mid, d.unread_count, d.date, m.data, m.read_state, m.mid, m.send_state, s.flags, m.date, d.last_mid_i, d.unread_count_i, d.pts FROM dialogs as d LEFT JOIN messages as m ON d.last_mid = m.mid LEFT JOIN dialog_settings as s ON d.did = s.did ORDER BY d.date DESC LIMIT %d,%d", cacheOffset, count));
                    while (cursor.next()) {
                        TLRPC.Dialog dialog;
                        int pts = cursor.intValue(12);
                        long id = cursor.longValue(0);
                        if (pts == 0 || (int) id > 0) {
                            dialog = new TLRPC.TL_dialog();
                        } else {
                            dialog = new TLRPC.TL_dialogChannel();
                        }
                        dialog.id = id;
                        dialog.top_message = cursor.intValue(1);
                        dialog.unread_count = cursor.intValue(2);
                        dialog.last_message_date = cursor.intValue(3);
                        dialog.pts = pts;
                        dialog.top_not_important_message = cursor.intValue(10);
                        dialog.unread_not_important_count = cursor.intValue(11);
                        long flags = cursor.longValue(8);
                        int low_flags = (int)flags;
                        dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
                        if ((low_flags & 1) != 0) {
                            dialog.notify_settings.mute_until = (int)(flags >> 32);
                            if (dialog.notify_settings.mute_until == 0) {
                                dialog.notify_settings.mute_until = Integer.MAX_VALUE;
                            }
                        }
                        dialogs.dialogs.add(dialog);

                        NativeByteBuffer data = new NativeByteBuffer(cursor.byteArrayLength(4));
                        if (data != null && cursor.byteBufferValue(4, data) != 0) {
                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                            if (message != null) {
                                MessageObject.setUnreadFlags(message, cursor.intValue(5));
                                message.id = cursor.intValue(6);
                                int date = cursor.intValue(9);
                                if (date != 0) {
                                    dialog.last_message_date = date;
                                }
                                message.send_state = cursor.intValue(7);
                                message.dialog_id = dialog.id;
                                dialogs.messages.add(message);

                                if (message.from_id > 0) {
                                    if (!usersToLoad.contains(message.from_id)) {
                                        usersToLoad.add(message.from_id);
                                    }
                                } else {
                                    if (!chatsToLoad.contains(-message.from_id)) {
                                        chatsToLoad.add(-message.from_id);
                                    }
                                }
                                if (message.action != null && message.action.user_id != 0) {
                                    if (!usersToLoad.contains(message.action.user_id)) {
                                        usersToLoad.add(message.action.user_id);
                                    }
                                }
                                if (message.fwd_from_id instanceof TLRPC.TL_peerUser) {
                                    if (!usersToLoad.contains(message.fwd_from_id.user_id)) {
                                        usersToLoad.add(message.fwd_from_id.user_id);
                                    }
                                } else if (message.fwd_from_id instanceof TLRPC.TL_peerChannel) {
                                    if (!chatsToLoad.contains(message.fwd_from_id.channel_id)) {
                                        chatsToLoad.add(message.fwd_from_id.channel_id);
                                    }
                                }
                            }
                        }
                        data.reuse();

                        int lower_id = (int)dialog.id;
                        int high_id = (int)(dialog.id >> 32);
                        if (lower_id != 0) {
                            if (high_id == 1) {
                                if (!chatsToLoad.contains(lower_id)) {
                                    chatsToLoad.add(lower_id);
                                }
                            } else {
                                if (lower_id > 0) {
                                    if (!usersToLoad.contains(lower_id)) {
                                        usersToLoad.add(lower_id);
                                    }
                                } else {
                                    if (!chatsToLoad.contains(-lower_id)) {
                                        chatsToLoad.add(-lower_id);
                                    }
                                }
                            }
                        } else {
                            if (!encryptedToLoad.contains(high_id)) {
                                encryptedToLoad.add(high_id);
                            }
                        }
                    }
                    cursor.dispose();

                    if (!encryptedToLoad.isEmpty()) {
                        getEncryptedChatsInternal(TextUtils.join(",", encryptedToLoad), encryptedChats, usersToLoad);
                    }

                    if (!chatsToLoad.isEmpty()) {
                        getChatsInternal(TextUtils.join(",", chatsToLoad), dialogs.chats);
                    }
                    if (!usersToLoad.isEmpty()) {
                        getUsersInternal(TextUtils.join(",", usersToLoad), dialogs.users);
                    }
                    MessagesController.getInstance().processLoadedDialogs(dialogs, encryptedChats, cacheOffset, offset, serverOffset, count, true, false);
                } catch (Exception e) {
                    dialogs.dialogs.clear();
                    dialogs.users.clear();
                    dialogs.chats.clear();
                    encryptedChats.clear();
                    FileLog.e("tmessages", e);
                    MessagesController.getInstance().processLoadedDialogs(dialogs, encryptedChats, 0, 0, 0, 100, true, true);
                }
            }
        });
    }

    private void putDialogsInternal(final TLRPC.messages_Dialogs dialogs) {
        try {
            database.beginTransaction();
            final HashMap<Long, ArrayList<TLRPC.Message>> new_dialogMessage = new HashMap<>();
            for (int a = 0; a < dialogs.messages.size(); a++) {
                TLRPC.Message message = dialogs.messages.get(a);
                ArrayList<TLRPC.Message> arrayList = new_dialogMessage.get(message.dialog_id);
                if (arrayList == null) {
                    arrayList = new ArrayList<>();
                    new_dialogMessage.put(message.dialog_id, arrayList);
                }
                arrayList.add(message);
            }

            if (!dialogs.dialogs.isEmpty()) {
                SQLitePreparedStatement state = database.executeFast("REPLACE INTO messages VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)");
                SQLitePreparedStatement state2 = database.executeFast("REPLACE INTO dialogs VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                SQLitePreparedStatement state3 = database.executeFast("REPLACE INTO media_v2 VALUES(?, ?, ?, ?, ?)");
                SQLitePreparedStatement state4 = database.executeFast("REPLACE INTO dialog_settings VALUES(?, ?)");
                SQLitePreparedStatement state5 = database.executeFast("REPLACE INTO messages_holes VALUES(?, ?, ?)");
                SQLitePreparedStatement state6 = database.executeFast("REPLACE INTO media_holes_v2 VALUES(?, ?, ?, ?)");
                SQLitePreparedStatement state7 = database.executeFast("REPLACE INTO messages_imp_holes VALUES(?, ?, ?)");
                SQLitePreparedStatement state8 = null;

                for (int a = 0; a < dialogs.dialogs.size(); a++) {
                    TLRPC.Dialog dialog = dialogs.dialogs.get(a);

                    if (dialog.id == 0) {
                        if (dialog.peer.user_id != 0) {
                            dialog.id = dialog.peer.user_id;
                        } else if (dialog.peer.chat_id != 0) {
                            dialog.id = -dialog.peer.chat_id;
                        } else {
                            dialog.id = -dialog.peer.channel_id;
                        }
                    }
                    int messageDate = 0;
                    int messageDateI = 0;
                    int impMessageId = 0;
                    int notImpMessageId = 0;
                    ArrayList<TLRPC.Message> arrayList = new_dialogMessage.get(dialog.id);
                    if (arrayList != null) {
                        for (int b = 0; b < arrayList.size(); b++) {
                            TLRPC.Message message = arrayList.get(b);
                            if (MessageObject.isImportant(message) || message.to_id.channel_id == 0) {
                                messageDate = message.date;
                            } else {
                                messageDateI = message.date;
                            }

                            if (message.reply_markup != null && ((message.reply_markup.flags & 4) == 0 || (message.flags & 16) != 0)) {
                                BotQuery.putBotKeyboard(dialog.id, message);
                            }

                            fixUnsupportedMedia(message);
                            NativeByteBuffer data = new NativeByteBuffer(message.getObjectSize());
                            message.serializeToStream(data);

                            long messageId = message.id;
                            if (message.to_id.channel_id != 0) {
                                messageId |= ((long) message.to_id.channel_id) << 32;
                            }

                            state.requery();
                            state.bindLong(1, messageId);
                            state.bindLong(2, dialog.id);
                            state.bindInteger(3, MessageObject.getUnreadFlags(message));
                            state.bindInteger(4, message.send_state);
                            state.bindInteger(5, message.date);
                            state.bindByteBuffer(6, data);
                            state.bindInteger(7, (MessageObject.isOut(message) ? 1 : 0));
                            state.bindInteger(8, 0);
                            if ((message.flags & TLRPC.MESSAGE_FLAG_HAS_VIEWS) != 0) {
                                state.bindInteger(9, message.views);
                            } else {
                                state.bindInteger(9, 0);
                            }
                            state.bindInteger(10, MessageObject.isImportant(message) ? 1 : 0);
                            state.step();

                            if (MessageObject.isImportant(message)) {
                                state7.requery();
                                state7.bindLong(1, dialog.id);
                                state7.bindInteger(2, message.id == 1 ? 1 : 0);
                                state7.bindInteger(3, message.id);
                                state7.step();
                                impMessageId = message.id;
                            } else {
                                notImpMessageId = message.id;
                            }

                            if (SharedMediaQuery.canAddMessageToMedia(message)) {
                                state3.requery();
                                state3.bindLong(1, messageId);
                                state3.bindLong(2, dialog.id);
                                state3.bindInteger(3, message.date);
                                state3.bindInteger(4, SharedMediaQuery.getMediaType(message));
                                state3.bindByteBuffer(5, data);
                                state3.step();
                            }
                            data.reuse();
                        }
                        if (impMessageId != 0 && notImpMessageId == 0) {
                            notImpMessageId = impMessageId;
                            impMessageId = 0;
                        }

                        if (notImpMessageId != 0 && impMessageId == 0) {
                            state5.requery();
                            state5.bindLong(1, dialog.id);
                            state5.bindInteger(2, notImpMessageId == 1 ? 1 : 0);
                            state5.bindInteger(3, notImpMessageId);
                            state5.step();

                            for (int b = 0; b < SharedMediaQuery.MEDIA_TYPES_COUNT; b++) {
                                state6.requery();
                                state6.bindLong(1, dialog.id);
                                state6.bindInteger(2, b);
                                state6.bindInteger(3, notImpMessageId == 1 ? 1 : 0);
                                state6.bindInteger(4, notImpMessageId);
                                state6.step();
                            }
                        } else {
                            int firstId = impMessageId > notImpMessageId ? notImpMessageId : impMessageId;
                            int lastId = impMessageId > notImpMessageId ? impMessageId : notImpMessageId;

                            state5.requery();
                            state5.bindLong(1, dialog.id);
                            state5.bindInteger(2, firstId == 1 ? 1 : 0);
                            state5.bindInteger(3, firstId);
                            state5.step();

                            state5.requery();
                            state5.bindLong(1, dialog.id);
                            state5.bindInteger(2, firstId);
                            state5.bindInteger(3, lastId);
                            state5.step();

                            for (int b = 0; b < SharedMediaQuery.MEDIA_TYPES_COUNT; b++) {
                                state6.requery();
                                state6.bindLong(1, dialog.id);
                                state6.bindInteger(2, b);
                                state6.bindInteger(3, firstId == 1 ? 1 : 0);
                                state6.bindInteger(4, firstId);
                                state6.step();

                                state6.requery();
                                state6.bindLong(1, dialog.id);
                                state6.bindInteger(2, b);
                                state6.bindInteger(3, firstId);
                                state6.bindInteger(4, lastId);
                                state6.step();
                            }

                            if (impMessageId < notImpMessageId) {
                                if (state8 == null) {
                                    state8 = database.executeFast("REPLACE INTO channel_group VALUES(?, ?, ?, ?)");
                                }
                                state8.requery();
                                state8.bindLong(1, dialog.id);
                                state8.bindInteger(2, impMessageId);
                                state8.bindInteger(3, Integer.MAX_VALUE);
                                state8.bindInteger(4, notImpMessageId - impMessageId);
                                state8.step();
                            }
                        }
                    }

                    long topMessage = dialog.top_message;
                    long topMessageI = dialog.top_not_important_message;
                    if (dialog.peer.channel_id != 0) {
                        topMessage |= ((long) dialog.peer.channel_id) << 32;
                        topMessageI |= ((long) dialog.peer.channel_id) << 32;
                    }

                    state2.requery();
                    state2.bindLong(1, dialog.id);
                    state2.bindInteger(2, messageDate);
                    state2.bindInteger(3, dialog.unread_count);
                    state2.bindLong(4, topMessage);
                    state2.bindInteger(5, dialog.read_inbox_max_id);
                    state2.bindInteger(6, 0);
                    state2.bindLong(7, topMessageI);
                    state2.bindInteger(8, dialog.unread_not_important_count);
                    state2.bindInteger(9, dialog.pts);
                    state2.bindInteger(10, messageDateI);
                    state2.step();

                    if (dialog.notify_settings != null) {
                        state4.requery();
                        state4.bindLong(1, dialog.id);
                        state4.bindInteger(2, dialog.notify_settings.mute_until != 0 ? 1 : 0);
                        state4.step();
                    }
                }
                state.dispose();
                state2.dispose();
                state3.dispose();
                state4.dispose();
                state5.dispose();
                state6.dispose();
                state7.dispose();
                if (state8 != null) {
                    state8.dispose();
                }
            }

            putUsersInternal(dialogs.users);
            putChatsInternal(dialogs.chats);

            database.commitTransaction();
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public void putDialogs(final TLRPC.messages_Dialogs dialogs) {
        if (dialogs.dialogs.isEmpty()) {
            return;
        }
        storageQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                putDialogsInternal(dialogs);
                loadUnreadMessages();
            }
        });
    }

    public int getChannelPtsSync(final int channelId) {
        final Semaphore semaphore = new Semaphore(0);
        final Integer[] pts = new Integer[] {0};
        MessagesStorage.getInstance().getStorageQueue().postRunnable(new Runnable() {
            @Override
            public void run() {
                SQLiteCursor cursor = null;
                try {
                    cursor = database.queryFinalized("SELECT pts FROM dialogs WHERE did = " + (-channelId));
                    if (cursor.next()) {
                        pts[0] = cursor.intValue(0);
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
                semaphore.release();
            }
        });
        try {
            semaphore.acquire();
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return pts[0];
    }

    public TLRPC.User getUserSync(final int user_id) {
        final Semaphore semaphore = new Semaphore(0);
        final TLRPC.User[] user = new TLRPC.User[1];
        MessagesStorage.getInstance().getStorageQueue().postRunnable(new Runnable() {
            @Override
            public void run() {
                user[0] = getUser(user_id);
                semaphore.release();
            }
        });
        try {
            semaphore.acquire();
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return user[0];
    }

    public TLRPC.Chat getChatSync(final int user_id) {
        final Semaphore semaphore = new Semaphore(0);
        final TLRPC.Chat[] chat = new TLRPC.Chat[1];
        MessagesStorage.getInstance().getStorageQueue().postRunnable(new Runnable() {
            @Override
            public void run() {
                chat[0] = getChat(user_id);
                semaphore.release();
            }
        });
        try {
            semaphore.acquire();
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return chat[0];
    }

    public TLRPC.User getUser(final int user_id) {
        TLRPC.User user = null;
        try {
            ArrayList<TLRPC.User> users = new ArrayList<>();
            getUsersInternal("" + user_id, users);
            if (!users.isEmpty()) {
                user = users.get(0);
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return user;
    }

    public ArrayList<TLRPC.User> getUsers(final ArrayList<Integer> uids) {
        ArrayList<TLRPC.User> users = new ArrayList<>();
        try {
            getUsersInternal(TextUtils.join(",", uids), users);
        } catch (Exception e) {
            users.clear();
            FileLog.e("tmessages", e);
        }
        return users;
    }

    public TLRPC.Chat getChat(final int chat_id) {
        TLRPC.Chat chat = null;
        try {
            ArrayList<TLRPC.Chat> chats = new ArrayList<>();
            getChatsInternal("" + chat_id, chats);
            if (!chats.isEmpty()) {
                chat = chats.get(0);
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return chat;
    }

    public TLRPC.EncryptedChat getEncryptedChat(final int chat_id) {
        TLRPC.EncryptedChat chat = null;
        try {
            ArrayList<TLRPC.EncryptedChat> encryptedChats = new ArrayList<>();
            getEncryptedChatsInternal("" + chat_id, encryptedChats, null);
            if (!encryptedChats.isEmpty()) {
                chat = encryptedChats.get(0);
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return chat;
    }
}
