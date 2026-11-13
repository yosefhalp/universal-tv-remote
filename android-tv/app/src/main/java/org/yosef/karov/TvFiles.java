package org.yosef.karov;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class TvFiles {
    static final int CHUNK_BYTES = 96 * 1024;
    static final long MAX_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int PAGE = 80, MAX_SCAN = 2000, MAX_DELETE = 20;
    private static final String PART_PREFIX = ".karov-upload-";
    private static final String[] COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_MIME_TYPE
    };

    private final Context context;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "karov-files"));
    private final Set<String> cleaned = new HashSet<>();
    private Transfer active;
    private Snapshot snapshot;

    TvFiles(Context context) {
        this.context = context;
        worker.scheduleWithFixedDelay(
                () -> {
                    if (active != null && SystemClock.elapsedRealtime() - active.updated > 60_000)
                        discard();
                },
                15,
                15,
                TimeUnit.SECONDS);
    }

    void execute(
            String method,
            JSONObject args,
            BooleanSupplier current,
            DeviceActions.Result callback) {
        try {
            worker.execute(() -> {
                Object result;
                try {
                    requireCurrent(current);
                    result =
                            switch (method) {
                                case "fileDestinations" -> destinations();
                                case "fileList" -> list(args);
                                case "fileBegin" -> begin(args, current);
                                case "fileChunk" -> append(args, current);
                                case "fileFinish" -> finish(args, current);
                                case "fileCancel" -> cancel(args);
                                case "fileDelete" -> delete(args, current);
                                case "fileOpen" -> open(args, current);
                                case "fileChooseFolder" -> {
                                    if (!TvFileStorage.chooseFolder(context))
                                        throw fail("FILE_NO_PICKER");
                                    yield true;
                                }
                                case "fileStorageSettings" -> {
                                    if (!TvFileStorage.openStorageSettings(context))
                                        throw fail("UNSUPPORTED");
                                    yield true;
                                }
                                default -> throw fail("UNSUPPORTED");
                            };
                } catch (FileError error) {
                    callback.done(null, error.getMessage());
                    return;
                } catch (JSONException | IllegalArgumentException error) {
                    callback.done(null, "FILE_INVALID");
                    return;
                } catch (SecurityException error) {
                    callback.done(null, "FILE_PERMISSION");
                    return;
                } catch (IOException | RuntimeException error) {
                    callback.done(
                            null,
                            method.equals("fileList") || method.equals("fileOpen")
                                    ? "FILE_READ_FAILED"
                                    : "FILE_WRITE_FAILED");
                    return;
                }
                callback.done(result, null);
            });
        } catch (RejectedExecutionException error) {
            callback.done(null, "DISCONNECTED");
        }
    }

    void disconnected() {
        if (!worker.isShutdown())
            worker.execute(() -> {
                discard();
                snapshot = null;
            });
    }

    void close() {
        worker.shutdown();
    }

    private JSONObject destinations() throws JSONException {
        JSONArray result = new JSONArray();
        for (TvFileStorage.Target target : TvFileStorage.destinations(context).values()) {
            recover(target);
            result.put(target.toJson());
        }
        return new JSONObject()
                .put("destinations", result)
                .put("folderPicker", TvFileStorage.folderPicker(context));
    }

    private TvFileStorage.Target target(JSONObject args, boolean write)
            throws JSONException, IOException {
        return target(args.getString("destination"), write);
    }

    private TvFileStorage.Target target(String id, boolean write) throws IOException {
        TvFileStorage.Target target = TvFileStorage.destinations(context).get(id);
        if (target == null || (target.directory == null && target.tree == null))
            throw fail("FILE_STORAGE_UNAVAILABLE");
        if (write && !target.writable) throw fail("FILE_PERMISSION");
        recover(target);
        return target;
    }

    private JSONObject begin(JSONObject args, BooleanSupplier current)
            throws JSONException, IOException {
        if (active != null) throw fail("FILE_BUSY");
        String name = checkedName(args.getString("name"));
        long bytes = integer(args, "bytes");
        if (bytes > MAX_BYTES) throw fail("FILE_TOO_LARGE");
        TvFileStorage.Target target = target(args, true);
        if (target.freeBytes >= 0 && bytes > target.freeBytes) throw fail("FILE_NO_SPACE");
        requireCurrent(current);
        Transfer item = new Transfer(target, name, bytes);
        active = item;
        try {
            if (target.directory != null) {
                item.parentIdentity = attributes(target.directory.toPath()).fileKey();
                item.file = new File(target.directory, item.partName);
                if (!item.file.createNewFile()) {
                    item.file = null;
                    throw fail("FILE_WRITE_FAILED");
                }
                item.output = new FileOutputStream(item.file);
            } else {
                item.document =
                        DocumentsContract.createDocument(
                                context.getContentResolver(),
                                root(target),
                                "application/octet-stream",
                                item.partName);
                if (item.document == null) throw fail("FILE_WRITE_FAILED");
                DocumentInfo info = document(item.document);
                if (info == null || (info.flags & Document.FLAG_SUPPORTS_RENAME) == 0)
                    throw fail("FILE_PERMISSION");
                // שומרים בדיוק את המסמך הזמני שנוצר כדי לנקות אותו גם אם התהליך הופסק.
                if (!journal().edit().putString(target.id, item.document.toString()).commit())
                    throw fail("FILE_WRITE_FAILED");
                ParcelFileDescriptor descriptor =
                        context.getContentResolver().openFileDescriptor(item.document, "w");
                if (descriptor == null) throw fail("FILE_WRITE_FAILED");
                item.output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
            }
            requireCurrent(current);
            return new JSONObject()
                    .put("transfer", item.id)
                    .put("chunkBytes", CHUNK_BYTES)
                    .put("window", 4);
        } catch (IOException | RuntimeException | JSONException error) {
            discard();
            throw error;
        }
    }

    private JSONObject append(JSONObject args, BooleanSupplier current)
            throws JSONException, IOException {
        Transfer item = transfer(args);
        try {
            long offset = integer(args, "offset");
            if (offset != item.received) throw fail("FILE_CHANGED");
            String data = args.getString("data");
            if (data.isEmpty() || data.length() > (CHUNK_BYTES * 4 + 2) / 3)
                throw fail("FILE_INVALID");
            byte[] bytes = Base64.getUrlDecoder().decode(data);
            if (bytes.length > CHUNK_BYTES || bytes.length > item.bytes - item.received)
                throw fail("FILE_INVALID");
            requireCurrent(current);
            item.output.write(bytes);
            item.received += bytes.length;
            item.updated = SystemClock.elapsedRealtime();
            return new JSONObject().put("received", item.received);
        } catch (IOException | RuntimeException | JSONException error) {
            discard();
            throw error;
        }
    }

    private JSONObject finish(JSONObject args, BooleanSupplier current)
            throws JSONException, IOException {
        Transfer item = transfer(args);
        try {
            if (item.received != item.bytes) throw fail("FILE_INCOMPLETE");
            item.output.getFD().sync();
            item.output.close();
            item.output = null;
            TvFileStorage.Target target = target(item.target.id, true);
            if (!Objects.equals(target.directory, item.target.directory)
                    || !Objects.equals(target.tree, item.target.tree))
                throw fail("FILE_STORAGE_UNAVAILABLE");
            requireCurrent(current);
            String saved;
            if (item.file != null) {
                if (!Objects.equals(
                        item.parentIdentity, attributes(target.directory.toPath()).fileKey()))
                    throw fail("FILE_STORAGE_UNAVAILABLE");
                File output = availableName(target.directory, item.name);
                requireCurrent(current);
                Files.move(item.file.toPath(), output.toPath());
                item.file = null;
                saved = output.getName();
            } else {
                Uri result =
                        DocumentsContract.renameDocument(
                                context.getContentResolver(), item.document, item.name);
                if (result == null) throw fail("FILE_WRITE_FAILED");
                item.document = null;
                journal().edit().remove(item.target.id).apply();
                DocumentInfo info = document(result);
                saved = info == null ? item.name : info.name;
            }
            active = null;
            return new JSONObject().put("name", saved).put("bytes", item.bytes);
        } catch (IOException | RuntimeException | JSONException error) {
            discard();
            throw error;
        }
    }

    private boolean cancel(JSONObject args) throws JSONException, IOException {
        if (active == null) return true;
        transfer(args);
        discard();
        return true;
    }

    private Transfer transfer(JSONObject args) throws JSONException, IOException {
        if (active == null || !active.id.equals(args.getString("transfer")))
            throw fail("FILE_CHANGED");
        return active;
    }

    private void discard() {
        Transfer item = active;
        active = null;
        if (item == null) return;
        if (item.output != null) {
            try {
                item.output.close();
            } catch (IOException ignored) {
            }
        }
        if (item.file != null) {
            try {
                if (Objects.equals(
                        item.parentIdentity,
                        attributes(item.file.getParentFile().toPath()).fileKey()))
                    Files.deleteIfExists(item.file.toPath());
            } catch (IOException | RuntimeException ignored) {
            }
        }
        if (item.document != null && removePartial(item.document))
            journal().edit().remove(item.target.id).apply();
    }

    private JSONObject list(JSONObject args) throws IOException, JSONException {
        TvFileStorage.Target target = target(args, false);
        long offset = args.has("offset") ? integer(args, "offset") : 0;
        if (offset == 0) snapshot = scan(target);
        if (snapshot == null
                || !snapshot.destination.equals(target.id)
                || offset > snapshot.entries.size()) throw fail("FILE_CHANGED");
        int end = Math.min((int) offset + PAGE, snapshot.entries.size());
        JSONArray files = new JSONArray();
        for (int i = (int) offset; i < end; i++) files.put(snapshot.entries.get(i).toJson());
        return new JSONObject()
                .put("files", files)
                .put("offset", offset)
                .put("hasMore", end < snapshot.entries.size())
                .put("truncated", snapshot.truncated);
    }

    private Snapshot scan(TvFileStorage.Target target) throws IOException {
        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;
        if (target.directory != null) {
            try (DirectoryStream<Path> paths =
                    Files.newDirectoryStream(target.directory.toPath())) {
                int count = 0;
                for (Path path : paths) {
                    if (count++ >= MAX_SCAN) {
                        truncated = true;
                        break;
                    }
                    String name = path.getFileName().toString();
                    if (!validListedName(name)) continue;
                    try {
                        BasicFileAttributes data = attributes(path);
                        if (data.isRegularFile())
                            entries.add(Entry.local(name, data, target.writable));
                    } catch (java.nio.file.NoSuchFileException disappeared) {
                        // ייתכן שקובץ הוסר בזמן קריאת הרשימה.
                    }
                }
            }
        } else {
            Uri children =
                    DocumentsContract.buildChildDocumentsUriUsingTree(
                            target.tree, DocumentsContract.getTreeDocumentId(target.tree));
            try (Cursor cursor =
                    context.getContentResolver().query(children, COLUMNS, null, null, null)) {
                if (cursor == null) throw fail("FILE_READ_FAILED");
                int count = 0;
                while (cursor.moveToNext()) {
                    if (count++ >= MAX_SCAN) {
                        truncated = true;
                        break;
                    }
                    DocumentInfo info = readDocument(cursor);
                    if (info.directory || !validListedName(info.name)) continue;
                    Uri uri = DocumentsContract.buildDocumentUriUsingTree(target.tree, info.id);
                    entries.add(Entry.document(info, uri, target.writable));
                }
            }
        }
        entries.sort(
                Comparator.comparingLong((Entry entry) -> entry.bytes)
                        .reversed()
                        .thenComparing(entry -> entry.name)
                        .thenComparing(entry -> entry.id));
        Object identity =
                target.directory == null ? null : attributes(target.directory.toPath()).fileKey();
        return new Snapshot(target.id, identity, entries, truncated);
    }

    private JSONObject delete(JSONObject args, BooleanSupplier current)
            throws JSONException, IOException {
        TvFileStorage.Target target = target(args, true);
        JSONArray ids = args.getJSONArray("ids");
        if (ids.length() == 0 || ids.length() > MAX_DELETE) throw fail("FILE_INVALID");
        Set<String> seen = new HashSet<>();
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.getString(i);
            if (!seen.add(id)) throw fail("FILE_INVALID");
            entries.add(entry(target, id));
        }
        int removed = 0, failed = 0;
        long bytes = 0;
        for (Entry entry : entries) {
            requireCurrent(current);
            if (!entry.canDelete) {
                failed++;
                continue;
            }
            boolean deleted;
            try {
                if (target.directory != null) {
                    File file = checkedFile(target, entry.name);
                    if (!entry.matches(attributes(file.toPath()))) throw fail("FILE_CHANGED");
                    requireCurrent(current);
                    Files.delete(file.toPath());
                    deleted = true;
                } else {
                    DocumentInfo info = checkedDocument(target, entry);
                    requireCurrent(current);
                    deleted =
                            (info.flags & Document.FLAG_SUPPORTS_DELETE) != 0
                                    && DocumentsContract.deleteDocument(
                                            context.getContentResolver(), entry.uri);
                }
            } catch (FileError error) {
                if ("DISCONNECTED".equals(error.getMessage())) throw error;
                deleted = false;
            } catch (IOException | RuntimeException error) {
                deleted = false;
            }
            if (deleted) {
                removed++;
                bytes += Math.max(0, entry.bytes);
            } else failed++;
        }
        snapshot = null;
        return new JSONObject()
                .put("removed", removed)
                .put("failed", failed)
                .put("freedBytes", bytes);
    }

    private boolean open(JSONObject args, BooleanSupplier current)
            throws JSONException, IOException {
        TvFileStorage.Target target = target(args, false);
        Entry entry = entry(target, args.getString("id"));
        String mime = viewerType(entry.name);
        if (mime == null) throw fail("FILE_NO_VIEWER");
        Uri uri;
        if (target.directory != null) {
            File file = checkedFile(target, entry.name);
            if (!entry.matches(attributes(file.toPath()))) throw fail("FILE_CHANGED");
            uri = TvFileProvider.uri(context, target.id, entry.name);
        } else {
            checkedDocument(target, entry);
            uri = entry.uri;
        }
        requireCurrent(current);
        if (TvFileStorage.folderSelectionActive()) throw fail("FILE_PERMISSION");
        Intent view =
                new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mime)
                        .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri(entry.name, uri));
        try {
            context.startActivity(view);
            return true;
        } catch (android.content.ActivityNotFoundException error) {
            throw fail("FILE_NO_VIEWER");
        }
    }

    private Entry entry(TvFileStorage.Target target, String id) throws IOException {
        if (snapshot == null || !snapshot.destination.equals(target.id)) throw fail("FILE_CHANGED");
        if (target.directory != null
                && !Objects.equals(
                        snapshot.parentIdentity, attributes(target.directory.toPath()).fileKey()))
            throw fail("FILE_CHANGED");
        for (Entry entry : snapshot.entries) if (entry.id.equals(id)) return entry;
        throw fail("FILE_CHANGED");
    }

    private DocumentInfo checkedDocument(TvFileStorage.Target target, Entry entry)
            throws IOException {
        // הספק המקומי מזהה מסמך לפי הנתיב בעץ. מאמתים שהוא עדיין ילד ישיר של התיקייה שנבחרה.
        String rootId = DocumentsContract.getTreeDocumentId(target.tree);
        String id = DocumentsContract.getDocumentId(entry.uri);
        String prefix = rootId.endsWith(":") ? rootId : rootId + "/";
        if (!id.startsWith(prefix) || id.substring(prefix.length()).contains("/"))
            throw fail("FILE_CHANGED");
        DocumentInfo info = document(entry.uri);
        if (info == null
                || info.directory
                || !entry.name.equals(info.name)
                || entry.bytes != info.bytes
                || entry.modified != info.modified) throw fail("FILE_CHANGED");
        return info;
    }

    private DocumentInfo document(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, COLUMNS, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? readDocument(cursor) : null;
        }
    }

    private static DocumentInfo readDocument(Cursor cursor) {
        return new DocumentInfo(
                cursor.getString(0),
                cursor.getString(1),
                cursor.isNull(2) ? -1 : cursor.getLong(2),
                cursor.getLong(3),
                cursor.getInt(4),
                Document.MIME_TYPE_DIR.equals(cursor.getString(5)));
    }

    private static Uri root(TvFileStorage.Target target) {
        return DocumentsContract.buildDocumentUriUsingTree(
                target.tree, DocumentsContract.getTreeDocumentId(target.tree));
    }

    private android.content.SharedPreferences journal() {
        return context.getSharedPreferences("file_partials", Context.MODE_PRIVATE);
    }

    private void recover(TvFileStorage.Target target) {
        if (!target.writable
                || (active != null && active.target.id.equals(target.id))
                || !cleaned.add(target.id)) return;
        try {
            if (target.directory != null) {
                try (DirectoryStream<Path> paths =
                        Files.newDirectoryStream(
                                target.directory.toPath(), PART_PREFIX + "*.part")) {
                    for (Path path : paths)
                        if (isPartial(path.getFileName().toString())
                                && attributes(path).isRegularFile()) Files.deleteIfExists(path);
                }
            } else if (target.tree != null) {
                String saved = journal().getString(target.id, "");
                if (!saved.isEmpty() && removePartial(Uri.parse(saved)))
                    journal().edit().remove(target.id).apply();
            }
        } catch (IOException | RuntimeException error) {
            cleaned.remove(target.id);
        }
    }

    private boolean removePartial(Uri uri) {
        try {
            DocumentInfo info = document(uri);
            return info == null
                    || (!info.directory
                            && isPartial(info.name)
                            && DocumentsContract.deleteDocument(context.getContentResolver(), uri));
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    private static boolean isPartial(String name) {
        return name != null && name.matches("\\.karov-upload-[a-f0-9]{32}\\.part(?:\\.bin)?");
    }

    private static boolean validListedName(String name) {
        return name != null
                && !name.isEmpty()
                && !name.equals(".")
                && !name.equals("..")
                && !name.startsWith(PART_PREFIX)
                && !name.contains("/")
                && !name.contains("\\")
                && name.codePoints().noneMatch(Character::isISOControl);
    }

    static String checkedName(String name) throws IOException {
        if (!validListedName(name)
                || name.trim().isEmpty()
                || name.getBytes(StandardCharsets.UTF_8).length > 180) throw fail("FILE_INVALID");
        return name;
    }

    static File checkedFile(TvFileStorage.Target target, String name) throws IOException {
        if (target.directory == null || !validListedName(name)) throw fail("FILE_CHANGED");
        File file = new File(target.directory, name);
        if (!file.getCanonicalFile().equals(new File(target.directory.getCanonicalFile(), name))
                || !attributes(file.toPath()).isRegularFile()) throw fail("FILE_CHANGED");
        return file;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static File availableName(File directory, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 1000; i++) {
            File path = new File(directory, i == 1 ? name : stem + " (" + i + ")" + extension);
            if (!Files.exists(path.toPath(), LinkOption.NOFOLLOW_LINKS)) return path;
        }
        throw fail("FILE_WRITE_FAILED");
    }

    static String viewerType(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String mime =
                MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(
                                name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
        if (mime != null
                && (mime.startsWith("image/")
                        || mime.startsWith("video/")
                        || mime.startsWith("audio/")
                        || mime.equals("application/pdf")
                        || mime.equals("text/plain"))) return mime;
        return null;
    }

    private static long integer(JSONObject args, String key) throws JSONException, IOException {
        Object value = args.get(key);
        if (!(value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < 0) throw fail("FILE_INVALID");
        return ((Number) value).longValue();
    }

    private static void requireCurrent(BooleanSupplier current) throws IOException {
        if (!current.getAsBoolean()) throw fail("DISCONNECTED");
    }

    private static FileError fail(String code) {
        return new FileError(code);
    }

    private static final class FileError extends IOException {
        FileError(String code) {
            super(code);
        }
    }

    private static final class Transfer {
        final String id = Crypto.id();
        final String partName = PART_PREFIX + id + ".part";
        final TvFileStorage.Target target;
        final String name;
        final long bytes;
        long received, updated = SystemClock.elapsedRealtime();
        Object parentIdentity;
        File file;
        Uri document;
        FileOutputStream output;

        Transfer(TvFileStorage.Target target, String name, long bytes) {
            this.target = target;
            this.name = name;
            this.bytes = bytes;
        }
    }

    private record Snapshot(
            String destination, Object parentIdentity, List<Entry> entries, boolean truncated) {}

    private record DocumentInfo(
            String id, String name, long bytes, long modified, int flags, boolean directory) {}

    private static final class Entry {
        final String id = Crypto.id();
        final String name;
        final long bytes, modified;
        final Object fileKey;
        final Uri uri;
        final boolean canDelete;

        private Entry(
                String name,
                long bytes,
                long modified,
                Object fileKey,
                Uri uri,
                boolean canDelete) {
            this.name = name;
            this.bytes = bytes;
            this.modified = modified;
            this.fileKey = fileKey;
            this.uri = uri;
            this.canDelete = canDelete;
        }

        static Entry local(String name, BasicFileAttributes data, boolean writable) {
            return new Entry(
                    name,
                    data.size(),
                    data.lastModifiedTime().toMillis(),
                    data.fileKey(),
                    null,
                    writable);
        }

        static Entry document(DocumentInfo info, Uri uri, boolean writable) {
            return new Entry(
                    info.name,
                    info.bytes,
                    info.modified,
                    null,
                    uri,
                    writable && (info.flags & Document.FLAG_SUPPORTS_DELETE) != 0);
        }

        boolean matches(BasicFileAttributes data) {
            return data.isRegularFile()
                    && bytes == data.size()
                    && modified == data.lastModifiedTime().toMillis()
                    && Objects.equals(fileKey, data.fileKey());
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("bytes", bytes)
                    .put("modified", modified)
                    .put("canDelete", canDelete)
                    .put("canOpen", viewerType(name) != null);
        }
    }
}
