package com.android.server.pm;

import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Side-store for per-package {@link GosPackageState}, deliberately kept out of the core
 * package-settings persistence ({@code packages.xml}). Each entry is keyed by (package, userId)
 * and written to a single AtomicFile; a bug here can never corrupt the main package database.
 */
class GosPackageStateStore {
    private static final String TAG = "GosPackageStateStore";

    private static final String TAG_ROOT = "gos-package-states";
    private static final String TAG_PACKAGE = "package";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_FLAG_STORAGE_1 = "flags-1";
    private static final String ATTR_PACKAGE_FLAG_STORAGE = "package-flags";
    private static final String ATTR_STORAGE_SCOPES = "storage-scopes";
    private static final String ATTR_CONTACT_SCOPES = "contact-scopes";

    private final AtomicFile mFile;
    private final Object mLock = new Object();
    // null until first load
    private Map<String, GosPackageState> mStates;

    GosPackageStateStore(File file) {
        mFile = new AtomicFile(file);
    }

    GosPackageState get(String packageName, int userId) {
        synchronized (mLock) {
            ensureLoadedLocked();
            GosPackageState s = mStates.get(key(packageName, userId));
            return s == null ? GosPackageState.NONE : s;
        }
    }

    void put(String packageName, int userId, GosPackageState state) {
        synchronized (mLock) {
            ensureLoadedLocked();
            if (state == null || GosPackageState.DEFAULT.equals(state)) {
                mStates.remove(key(packageName, userId));
            } else {
                mStates.put(key(packageName, userId), state);
            }
            writeLocked();
        }
    }

    void remove(String packageName, int userId) {
        synchronized (mLock) {
            ensureLoadedLocked();
            mStates.remove(key(packageName, userId));
            writeLocked();
        }
    }

    private static String key(String packageName, int userId) {
        return packageName + "/" + userId;
    }

    private void ensureLoadedLocked() {
        if (mStates != null) {
            return;
        }
        mStates = new HashMap<>();
        if (!mFile.getBaseFile().exists()) {
            return;
        }
        try (FileInputStream in = mFile.openRead()) {
            TypedXmlPullParser parser = Xml.resolvePullParser(in);
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG || !TAG_PACKAGE.equals(parser.getName())) {
                    continue;
                }
                String name = parser.getAttributeValue(null, ATTR_NAME);
                int userId = parser.getAttributeInt(null, ATTR_USER_ID, 0);
                GosPackageState state = deserialize(parser);
                if (name != null) {
                    mStates.put(key(name, userId), state);
                }
            }
        } catch (IOException | XmlPullParserException e) {
            Log.w(TAG, "failed to read GosPackageState store, starting fresh", e);
            mStates = new HashMap<>();
        }
    }

    private void writeLocked() {
        FileOutputStream out = null;
        try {
            out = mFile.startWrite();
            TypedXmlSerializer serializer = Xml.resolveSerializer(out);
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_ROOT);
            for (Map.Entry<String, GosPackageState> e : mStates.entrySet()) {
                String k = e.getKey();
                int slash = k.lastIndexOf('/');
                String name = k.substring(0, slash);
                int userId = Integer.parseInt(k.substring(slash + 1));
                serializer.startTag(null, TAG_PACKAGE);
                serializer.attribute(null, ATTR_NAME, name);
                serializer.attributeInt(null, ATTR_USER_ID, userId);
                serializeInner(e.getValue(), serializer);
                serializer.endTag(null, TAG_PACKAGE);
            }
            serializer.endTag(null, TAG_ROOT);
            serializer.endDocument();
            mFile.finishWrite(out);
        } catch (IOException e) {
            Log.w(TAG, "failed to write GosPackageState store", e);
            if (out != null) {
                mFile.failWrite(out);
            }
        }
    }

    private static void serializeInner(GosPackageState ps, TypedXmlSerializer serializer)
            throws IOException {
        long flagStorage1 = ps.flagStorage1;
        if (flagStorage1 != 0L) {
            serializer.attributeLong(null, ATTR_FLAG_STORAGE_1, flagStorage1);
        }
        if (ps.hasFlag(GosPackageStateFlag.STORAGE_SCOPES_ENABLED)) {
            byte[] s = ps.storageScopes;
            if (s != null) {
                serializer.attributeBytesHex(null, ATTR_STORAGE_SCOPES, s);
            }
        }
        if (ps.hasFlag(GosPackageStateFlag.CONTACT_SCOPES_ENABLED)) {
            byte[] s = ps.contactScopes;
            if (s != null) {
                serializer.attributeBytesHex(null, ATTR_CONTACT_SCOPES, s);
            }
        }
        long packageFlagStorage = ps.packageFlagStorage;
        if (packageFlagStorage != 0L) {
            serializer.attributeLong(null, ATTR_PACKAGE_FLAG_STORAGE, packageFlagStorage);
        }
    }

    private static GosPackageState deserialize(TypedXmlPullParser parser)
            throws XmlPullParserException {
        long flagStorage1 = 0L;
        long packageFlagStorage = 0L;
        byte[] storageScopes = null;
        byte[] contactScopes = null;

        for (int i = 0, numAttr = parser.getAttributeCount(); i < numAttr; ++i) {
            String attr = parser.getAttributeName(i);
            switch (attr) {
                case ATTR_FLAG_STORAGE_1 ->
                    flagStorage1 = parser.getAttributeLong(i);
                case ATTR_PACKAGE_FLAG_STORAGE ->
                    packageFlagStorage = parser.getAttributeLong(i);
                case ATTR_STORAGE_SCOPES ->
                    storageScopes = parser.getAttributeBytesHex(i);
                case ATTR_CONTACT_SCOPES ->
                    contactScopes = parser.getAttributeBytesHex(i);
                default -> {
                    // ignore name/userId attributes, handled by the caller
                }
            }
        }
        return new GosPackageState(flagStorage1, packageFlagStorage, storageScopes, contactScopes);
    }
}
