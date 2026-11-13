package org.yosef.karov;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TvFileStorage {
    static final String LOCAL_FILES_FOLDER = "org.yosef.karov.LOCAL_FILES_FOLDER";
    private static final String STORAGE_PROVIDER = "com.android.externalstorage.documents";
    private static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    private static final int WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final int MAX_FOLDERS = 12;
    private static volatile Object folderSelection;

    public static final class Target {
        public final String id, name, kind, detail;
        public final boolean writable, needsAccess;
        public final long freeBytes, totalBytes;
        public final File directory;
        public final Uri tree;

        private Target(
                String id,
                String name,
                String kind,
                String detail,
                boolean writable,
                boolean needsAccess,
                File directory,
                Uri tree,
                File capacityPath) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.detail = detail;
            this.writable = writable;
            this.needsAccess = needsAccess;
            this.directory = directory;
            this.tree = tree;
            long free = -1, total = -1;
            if (capacityPath != null) {
                try {
                    StatFs stats = new StatFs(capacityPath.getPath());
                    free = stats.getAvailableBytes();
                    total = stats.getTotalBytes();
                } catch (IllegalArgumentException | SecurityException unavailable) {
                    // קושחה עשויה לדווח על נפח בלי לאפשר לקרוא את נתוני המקום שלו.
                }
            }
            freeBytes = free;
            totalBytes = total;
        }

        public JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("kind", kind)
                    .put("detail", detail)
                    .put("writable", writable)
                    .put("needsAccess", needsAccess)
                    .put("freeBytes", freeBytes)
                    .put("totalBytes", totalBytes);
        }
    }

    public static Map<String, Target> destinations(Context context) {
        Map<String, Target> result = new LinkedHashMap<>();
        File files = context.getFilesDir();
        Target internal = directory("internal", "קבצים בקרוב", "internal", files, true);
        result.put(internal.id, internal);
        StorageManager storage = context.getSystemService(StorageManager.class);
        if (storage == null) return result;
        boolean picker = folderPicker(context);
        UUID internalUuid = uuid(storage, files);
        Map<String, Target> external = new LinkedHashMap<>();
        for (File path : context.getExternalFilesDirs(null)) {
            if (path == null) continue;
            try {
                StorageVolume volume = storage.getStorageVolume(path);
                if (volume == null || !external(volume, path, storage, internalUuid)) continue;
                String key = volumeKey(volume);
                if (key == null) continue;
                String state = Environment.getExternalStorageState(path);
                if (!Environment.MEDIA_MOUNTED.equals(state)
                        && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) continue;
                Target target =
                        directory(
                                "usb-" + opaque(key),
                                label(volume, context),
                                "usb",
                                path,
                                Environment.MEDIA_MOUNTED.equals(state));
                if (target.directory != null) external.put(key, target);
            } catch (IllegalArgumentException | SecurityException unavailable) {
                // ייתכן שהכונן הוצא בין גילוי הנתיב לקריאת מצב הנפח.
            }
        }

        Map<String, Target> approved = new LinkedHashMap<>();
        if (localProvider(context)) {
            for (UriPermission permission :
                    context.getContentResolver().getPersistedUriPermissions()) {
                if (!permission.isReadPermission()) continue;
                Uri tree = permission.getUri();
                try {
                    StorageVolume volume = treeVolume(storage, tree);
                    String key = volumeKey(volume);
                    if (key == null
                            || !tree.toString().equals(preferences(context).getString(key, "")))
                        continue;
                    if (!mounted(volume)) continue;
                    int flags = folderFlags(context, tree);
                    boolean writable =
                            permission.isWritePermission()
                                    && Environment.MEDIA_MOUNTED.equals(volume.getState())
                                    && (flags & Document.FLAG_DIR_SUPPORTS_CREATE) != 0;
                    boolean isExternal = external(volume, null, storage, internalUuid);
                    String folder = folderLabel(tree);
                    Target target =
                            new Target(
                                    "folder-" + opaque(key + "|" + tree),
                                    folder,
                                    isExternal ? "usb" : "internal",
                                    "תיקייה שנבחרה בטלוויזיה",
                                    writable,
                                    picker && !writable,
                                    null,
                                    tree,
                                    volumeDirectory(volume));
                    approved.put(key, target);
                } catch (RuntimeException unavailable) {
                    // הרשאה קיימת אינה מבטיחה שהתיקייה עוד נגישה אחרי הזזה או הוצאת הכונן.
                }
            }
        }
        for (StorageVolume volume : storage.getStorageVolumes()) {
            String key = volumeKey(volume);
            if (!external(volume, null, storage, internalUuid)
                    && (key == null || !external.containsKey(key))) continue;
            Target target = key == null ? null : external.remove(key);
            Target folder = key == null ? null : approved.remove(key);
            if (target != null) result.put(target.id, target);
            if (folder != null) result.put(folder.id, folder);
            if (target == null && folder == null) {
                String name = label(volume, context);
                boolean needsAccess =
                        picker
                                && key != null
                                && Environment.MEDIA_MOUNTED.equals(volume.getState());
                String detail =
                        needsAccess
                                ? "נדרשת בחירת תיקייה בטלוויזיה"
                                : mounted(volume)
                                        ? "אין תיקייה זמינה לקרוב בכונן"
                                        : "הכונן אינו זמין";
                String id = "usb-unavailable-" + opaque(key == null ? name : key);
                result.put(
                        id,
                        new Target(
                                id,
                                name,
                                "usb",
                                detail,
                                false,
                                needsAccess,
                                null,
                                null,
                                mounted(volume) ? volumeDirectory(volume) : null));
            }
        }
        for (Target target : external.values()) result.put(target.id, target);
        for (Target target : approved.values()) result.put(target.id, target);
        return result;
    }

    private static Target directory(String id, String name, String kind, File root, boolean write) {
        File directory = root == null ? null : new File(root, "received");
        try {
            if (directory != null && write && !directory.isDirectory()) directory.mkdir();
            // תיקיית הקליטה חייבת להיות תיקייה רגילה וישירה בנתיב שהחזירה המערכת.
            if (directory != null
                    && (!directory.isDirectory()
                            || !directory.canRead()
                            || !new File(root.getCanonicalFile(), "received")
                                    .equals(directory.getCanonicalFile()))) directory = null;
            write &= directory != null && directory.canWrite();
        } catch (IOException | SecurityException unavailable) {
            directory = null;
            write = false;
        }
        return new Target(
                id, name, kind, "תיקיית הקבצים של קרוב", write, false, directory, null, root);
    }

    public static boolean folderPicker(Context context) {
        return localProvider(context) && folderIntent(context) != null;
    }

    public static Intent folderIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(READ | WRITE | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    }

    static Intent folderIntent(Context context) {
        Intent intent = folderIntent();
        for (ResolveInfo candidate :
                context.getPackageManager()
                        .queryIntentActivities(
                                intent,
                                PackageManager.MATCH_SYSTEM_ONLY
                                        | PackageManager.MATCH_DEFAULT_ONLY)) {
            if (candidate.activityInfo != null
                    && !candidate.activityInfo.packageName.endsWith("frameworkpackagestubs")) {
                // ההרשאה מגיעה מבורר שהותקן כחלק מהמערכת, ולא מאפליקציה שנרשמה לאותה פעולה.
                return intent.setComponent(
                        new ComponentName(
                                candidate.activityInfo.packageName, candidate.activityInfo.name));
            }
        }
        return null;
    }

    public static boolean chooseFolder(Context context) {
        // הכפתור המקומי יכול להחזיר את המסך אם המשתמש יצא ממנו דרך כפתור הבית.
        if ((folderSelectionActive() && !(context instanceof MainActivity))
                || !folderPicker(context)
                || (!MainActivity.visible && TvAccessibilityService.instance == null)) return false;
        return start(
                context,
                new Intent(context, MainActivity.FilesFolderActivity.class)
                        .setAction(LOCAL_FILES_FOLDER));
    }

    public static boolean folderSelectionActive() {
        return folderSelection != null;
    }

    static synchronized void beginFolderSelection(Object screen) {
        folderSelection = screen;
    }

    static synchronized void endFolderSelection(Object screen) {
        if (folderSelection == screen) folderSelection = null;
    }

    public static boolean openStorageSettings(Context context) {
        Intent storage = new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
        if (resolves(context, storage) && start(context, storage)) return true;
        return SystemSettings.open(context, false);
    }

    private static boolean start(Context context, Intent intent) {
        try {
            if (!(context instanceof android.app.Activity))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    private static boolean resolves(Context context, Intent intent) {
        ResolveInfo info =
                context.getPackageManager()
                        .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        return info != null
                && info.activityInfo != null
                && !info.activityInfo.packageName.endsWith("frameworkpackagestubs");
    }

    public static synchronized void acceptFolder(Context context, Intent data) throws Exception {
        Uri tree = data == null ? null : data.getData();
        StorageManager storage = context.getSystemService(StorageManager.class);
        if (storage == null || !localProvider(context)) throw new IOException("FILE_PERMISSION");
        StorageVolume volume = treeVolume(storage, tree);
        String key = volumeKey(volume);
        if (key == null || !mounted(volume)) throw new IOException("FILE_STORAGE_UNAVAILABLE");
        int modes = data.getFlags() & (READ | WRITE);
        if ((modes & READ) == 0) throw new IOException("FILE_PERMISSION");
        folderFlags(context, tree);
        SharedPreferences settings = preferences(context);
        String previous = settings.getString(key, "");
        if (previous.isEmpty() && settings.getAll().size() >= MAX_FOLDERS)
            throw new IOException("FILE_PERMISSION");
        ContentResolver resolver = context.getContentResolver();
        resolver.takePersistableUriPermission(tree, (modes & WRITE) != 0 ? READ | WRITE : READ);
        if (!settings.edit().putString(key, tree.toString()).commit()) {
            if (!tree.toString().equals(previous)) release(resolver, tree);
            throw new IOException("FILE_PERMISSION");
        }
        if (!previous.isEmpty() && !previous.equals(tree.toString()))
            release(resolver, Uri.parse(previous));
    }

    static synchronized boolean forgetFolders(Context context) {
        SharedPreferences settings = preferences(context);
        Map<String, ?> saved = settings.getAll();
        if (!settings.edit().clear().commit()) return false;
        for (Object item : saved.values())
            if (item instanceof String value)
                release(context.getContentResolver(), Uri.parse(value));
        return true;
    }

    static boolean hasFolders(Context context) {
        return !preferences(context).getAll().isEmpty();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("received-folders", Context.MODE_PRIVATE);
    }

    private static void release(ContentResolver resolver, Uri tree) {
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (!tree.equals(permission.getUri())) continue;
            int modes =
                    (permission.isReadPermission() ? READ : 0)
                            | (permission.isWritePermission() ? WRITE : 0);
            try {
                if (modes != 0) resolver.releasePersistableUriPermission(tree, modes);
            } catch (SecurityException alreadyReleased) {
                // ייתכן ש-Android הסירה את ההרשאה לפני הקריאה הזאת.
            }
        }
    }

    private static boolean localProvider(Context context) {
        ProviderInfo provider =
                context.getPackageManager().resolveContentProvider(STORAGE_PROVIDER, 0);
        return provider != null
                && provider.applicationInfo != null
                && (provider.applicationInfo.flags
                                & (ApplicationInfo.FLAG_SYSTEM
                                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                        != 0;
    }

    private static StorageVolume treeVolume(StorageManager storage, Uri tree) {
        if (tree == null
                || !"content".equals(tree.getScheme())
                || !STORAGE_PROVIDER.equals(tree.getAuthority())
                || !DocumentsContract.isTreeUri(tree))
            throw new IllegalArgumentException("FILE_PERMISSION");
        String document = DocumentsContract.getTreeDocumentId(tree);
        int colon = document.indexOf(':');
        if (colon < 1 || colon == document.length() - 1)
            throw new IllegalArgumentException("FILE_PERMISSION");
        String relative = document.substring(colon + 1);
        String lower = relative.toLowerCase(Locale.ROOT);
        if (lower.equals("android/data")
                || lower.startsWith("android/data/")
                || lower.equals("android/obb")
                || lower.startsWith("android/obb/"))
            throw new IllegalArgumentException("FILE_PERMISSION");
        for (String part : relative.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("FILE_PERMISSION");
        String prefix = document.substring(0, colon);
        if ("primary".equals(prefix)) return storage.getPrimaryStorageVolume();
        for (StorageVolume volume : storage.getStorageVolumes())
            if (prefix.equalsIgnoreCase(volume.getUuid())) return volume;
        throw new IllegalArgumentException("FILE_STORAGE_UNAVAILABLE");
    }

    private static int folderFlags(Context context, Uri tree) {
        Uri folder =
                DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                folder,
                                new String[] {Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS},
                                null,
                                null,
                                null)) {
            if (cursor == null
                    || !cursor.moveToFirst()
                    || !Document.MIME_TYPE_DIR.equals(cursor.getString(0)))
                throw new IllegalArgumentException("FILE_PERMISSION");
            return cursor.getInt(1);
        }
    }

    private static String folderLabel(Uri tree) {
        String document = DocumentsContract.getTreeDocumentId(tree);
        return document.substring(Math.max(document.lastIndexOf('/'), document.indexOf(':')) + 1);
    }

    private static String label(StorageVolume volume, Context context) {
        String label = volume.getDescription(context);
        return label == null || label.trim().isEmpty() ? "אחסון חיצוני" : label;
    }

    private static boolean mounted(StorageVolume volume) {
        return Environment.MEDIA_MOUNTED.equals(volume.getState())
                || Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState());
    }

    private static File volumeDirectory(StorageVolume volume) {
        return Build.VERSION.SDK_INT >= 30 ? volume.getDirectory() : null;
    }

    private static String volumeKey(StorageVolume volume) {
        String raw = volume.getUuid();
        if (raw != null && !raw.isEmpty()) return raw.toLowerCase(Locale.ROOT);
        return volume.isPrimary() && !volume.isRemovable() ? "primary" : null;
    }

    private static boolean external(
            StorageVolume volume, File path, StorageManager storage, UUID internal) {
        if (internal != null && internal.toString().equalsIgnoreCase(volume.getUuid()))
            return false;
        if (volume.isRemovable()) return true;
        if (path != null && Environment.isExternalStorageRemovable(path)) return true;
        File volumePath = path == null ? volumeDirectory(volume) : path;
        UUID uuid = uuid(storage, volumePath);
        return uuid != null && !StorageManager.UUID_DEFAULT.equals(uuid) && !uuid.equals(internal);
    }

    private static UUID uuid(StorageManager storage, File path) {
        if (path == null) return null;
        try {
            return storage.getUuidForPath(path);
        } catch (IOException | IllegalArgumentException | SecurityException unavailable) {
            return null;
        }
    }

    private static String opaque(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Crypto.bytes(value));
            StringBuilder key = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                int item = digest[i] & 255;
                key.append(Character.forDigit(item >>> 4, 16))
                        .append(Character.forDigit(item & 15, 16));
            }
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private TvFileStorage() {}
}
