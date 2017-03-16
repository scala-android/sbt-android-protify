package com.hanhuy.android.protify.agent.internal;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * @author pfnguyen
 */
public class DexExtractor {
    private static final String TAG = DexLoader.TAG;

    private static final String PROTIFY_DEX_PREFIX = "protify-dex/";
    private static final String DEX_SUFFIX = ".dex";

    static final String ZIP_SUFFIX = ".zip";
    private static final int MAX_EXTRACT_ATTEMPTS = 3;

    private static final String PREFS_FILE = "protify.version";
    private static final String KEY_TIME_STAMP = "timestamp";
    private static final String KEY_CRC = "crc";
    private static final String LOCK_FILE = "protify.extraction.lock";

    /**
     * Size of reading buffers.
     */
    private static final int BUFFER_SIZE = 0x4000;
    /* Keep value away from 0 because it is a too probable time stamp value */
    private static final long NO_VALUE = -1L;

    /**
     * Extracts application secondary dexes into files in the application data
     * directory.
     *
     * @return a list of files that were created. The list may be empty if there
     *         are no secondary dex files.
     * @throws IOException if encounters a problem while reading or writing
     *         secondary dex files
     */
    static List<File> load(Context context, ApplicationInfo applicationInfo, File dexDir) throws IOException {
        return load(context, applicationInfo, dexDir, false);
    }
    static List<File> load(final Context context, final ApplicationInfo applicationInfo, final File dexDir, final boolean force) throws IOException {
        Log.i(TAG, "DexExtractor.load(" + applicationInfo.sourceDir + ")");
        final File sourceApk = new File(applicationInfo.sourceDir);

        return withBlockingLock(dexDir, sourceApk, new RunnableIO<List<File>>() {
            @Override
            public List<File> run(boolean dirty) throws IOException {
                List<File> files;
                if (!force && !dirty) {
                    files = loadExistingExtractions(dexDir);
                    if (files.isEmpty())
                        files = performExtractions(sourceApk, dexDir);
                } else {
                    Log.i(TAG, "Detected that extraction must be performed.");
                    files = performExtractions(sourceApk, dexDir);
                }

                Log.i(TAG, "load found " + files.size() + " secondary dex files");
                return files;
            }
        });
    }

    interface RunnableIO<T> {
        T run(boolean dirty) throws IOException;
    }

    private static <T> T withBlockingLock(File dexDir, File sourceApk, RunnableIO<T> r) throws IOException {
        final long currentCrc = getZipCrc(sourceApk);
        File lockFile = new File(dexDir, LOCK_FILE);
        RandomAccessFile lockRaf = new RandomAccessFile(lockFile, "rw");
        FileChannel lockChannel = null;
        FileLock cacheLock = null;
        try {
            lockChannel = lockRaf.getChannel();
            Log.v(TAG, "Waiting for lock on: " + lockFile.getAbsolutePath());
            cacheLock = lockChannel.lock();
            Log.v(TAG, "Locked " + lockFile.getPath());
            long l = lockRaf.length() >= (Long.SIZE / 8) ? lockRaf.readLong() : 0;
            long c = lockRaf.length() >= 2 * (Long.SIZE / 8) ? lockRaf.readLong() : 0;
            return r.run(c != currentCrc || l != getTimeStamp(sourceApk));
        } finally {
            lockRaf.seek(0);
            lockRaf.writeLong(getTimeStamp(sourceApk));
            lockRaf.writeLong(currentCrc);
            if (cacheLock != null)
                cacheLock.release();
            if (lockChannel != null)
                lockChannel.close();
            lockRaf.close();
            Log.v(TAG, "Unlocked " + lockFile.getPath());
        }
    }

    private static List<File> loadExistingExtractions(File dexDir) {
        Log.i(TAG, "loading existing secondary dex files");

        File[] files = dexDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File file, String s) {
                return Build.VERSION.SDK_INT >= 14 ?
                        s.endsWith(DEX_SUFFIX) : s.endsWith(ZIP_SUFFIX);
            }
        });
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return (int) (f2.lastModified() - f1.lastModified());
            }
        });

        if (files == null) files = new File[0];

        return Arrays.asList(files);
    }

    private static long getTimeStamp(File archive) {
        long timeStamp = archive.lastModified();
        if (timeStamp == NO_VALUE) {
            // never return NO_VALUE
            timeStamp--;
        }
        return timeStamp;
    }


    private static long getZipCrc(File archive) throws IOException {
        long computedValue = ZipUtil.getZipCrc(archive);
        if (computedValue == NO_VALUE) {
            // never return NO_VALUE
            computedValue--;
        }
        return computedValue;
    }

    private static List<File> performExtractions(File sourceApk, File dexDir)
            throws IOException {

        // Ensure that whatever deletions happen in prepareDexDir only happen if the zip that
        // contains a secondary dex file in there is not consistent with the latest apk.  Otherwise,
        // multi-process race conditions can cause a crash loop where one process deletes the zip
        // while another had created it.
        prepareDexDir(dexDir);

        List<File> files = new ArrayList<File>();

        final ZipFile apk = new ZipFile(sourceApk);
        try {
            for (Enumeration<? extends ZipEntry> e = apk.entries(); e.hasMoreElements();) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (name.startsWith(PROTIFY_DEX_PREFIX) && name.endsWith(DEX_SUFFIX)) {
                    String fname = name.substring(name.lastIndexOf("/") + 1);
                    File extractedFile = new File(dexDir,
                            Build.VERSION.SDK_INT < 14 ? fname + ZIP_SUFFIX : fname);
                    if (Build.VERSION.SDK_INT < 14) {
                        extractV4(apk, entry, extractedFile, "protify-extraction");
                    } else
                        extractV14(apk, entry, extractedFile, "protify-extraction");
                    files.add(extractedFile);
                }
            }
        } finally {
            try {
                apk.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close resource", e);
            }
        }

        return files;
    }

    // TODO use FileObserver to lock on this directory if necessary
    private static void prepareDexDir(File dexDir) {
        File[] files = dexDir.listFiles();
        if (files == null) {
            Log.w(TAG, "Failed to list secondary dex dir content (" + dexDir.getPath() + ").");
            return;
        }
        for (File oldFile : files) {
            if (!LOCK_FILE.equals(oldFile.getName())) {
                if (!oldFile.delete()) {
                    Log.w(TAG, "Failed to delete old file " + oldFile.getPath());
                }
            }
        }
    }

    private static void extractV14(ZipFile apk, ZipEntry dexFile, File extractTo,
                                String extractedFilePrefix) throws IOException {

        InputStream in = apk.getInputStream(dexFile);
        File tmp = File.createTempFile(extractedFilePrefix, DEX_SUFFIX,
                extractTo.getParentFile());
        try {
            OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp));
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                int length = in.read(buffer);
                while (length != -1) {
                    out.write(buffer, 0, length);
                    length = in.read(buffer);
                }
            } finally {
                out.close();
            }
            if (!tmp.renameTo(extractTo)) {
                throw new IOException("Failed to rename \"" + tmp.getAbsolutePath() +
                        "\" to \"" + extractTo.getAbsolutePath() + "\"");
            }
            Log.i(TAG, "Extracted " + extractTo.getPath());
        } finally {
            closeQuietly(in);
            tmp.delete(); // return status ignored
        }
        if (!extractTo.isFile()) {
            throw new IOException("Failed to extract to: " + extractTo);
        }
    }
    private static void extractV4(ZipFile apk, ZipEntry dexFile, File extractTo,
                                String extractedFilePrefix) throws IOException, FileNotFoundException {

        InputStream in = apk.getInputStream(dexFile);
        ZipOutputStream out = null;
        File tmp = File.createTempFile(extractedFilePrefix, ZIP_SUFFIX,
                extractTo.getParentFile());
        Log.i(TAG, "Extracting " + tmp.getPath());
        try {
            out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
            try {
                ZipEntry classesDex = new ZipEntry("classes.dex");
                // keep zip entry time since it is the criteria used by Dalvik
                classesDex.setTime(dexFile.getTime());
                out.putNextEntry(classesDex);

                byte[] buffer = new byte[BUFFER_SIZE];
                int length = in.read(buffer);
                while (length != -1) {
                    out.write(buffer, 0, length);
                    length = in.read(buffer);
                }
                out.closeEntry();
            } finally {
                out.close();
            }
            Log.i(TAG, "Renaming to " + extractTo.getPath());
            if (!tmp.renameTo(extractTo)) {
                throw new IOException("Failed to rename \"" + tmp.getAbsolutePath() +
                        "\" to \"" + extractTo.getAbsolutePath() + "\"");
            }
        } finally {
            closeQuietly(in);
            tmp.delete(); // return status ignored
        }
    }


        /**
         * Closes the given {@code Closeable}. Suppresses any IO exceptions.
         */
    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to close resource", e);
        }
    }
}

