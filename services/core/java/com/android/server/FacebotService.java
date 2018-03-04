/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server;

import android.content.Context;
import android.location.Country;
import android.location.CountryListener;
import android.location.ICountryDetector;
import android.location.ICountryListener;
import android.os.*;
import android.util.*;
import com.android.internal.os.BackgroundThread;
import com.android.server.location.ComprehensiveCountryDetector;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @hide
 */
public class FacebotService extends IFacebot.Stub implements Runnable {

    private final static String TAG = "FacebotService";

    private final Context mContext;
    private boolean mSystemReady;

    public FacebotService(Context context) {
        super();
        mContext = context;
    }

    private static final HashMap<Entry, Entry> ENTRIES = new HashMap();
    private static final String LOG_FILE = "/sdcard/facebot_api_calls.log";

    private static BufferedOutputStream writer = null;
    private static boolean newData = false;

    enum FacebotMode {
        DISABLED,
        RECORD,
        PLAY
    }

    private FacebotMode mode;

    @Override
    public String addEntry(String className, String methodName, String arguments, String result) {
        if (!mSystemReady || mode == FacebotMode.DISABLED) {
            return result;   // server not yet active
        }
        Entry entry = new Entry(className, methodName, arguments, result);
        boolean storeEntry = false;
        synchronized (ENTRIES) {
            if (!ENTRIES.containsKey(entry)) {
                storeEntry = true;
            } else {
                Entry oldEntry = ENTRIES.get(entry);
                String oldRez = oldEntry.map.get("result");
                String rez = entry.map.get("result");
                if (!oldRez.equals(rez)) {
                    storeEntry = true;
                }
            }
            if (storeEntry) {
                ENTRIES.put(entry, entry);
            }
        }
        if(storeEntry) {
            writeEntry(entry);
        }
    }

    private void writeEntry(Entry entry) {
        synchronized (ENTRIES) {
            if (writer == null)
                openWriter();
        }
        if(writer == null)
            return;
        String ss = FaceBot.JsonSerializer.toJson(entry);
        byte bb[] = ss.getBytes();
        try {
            synchronized (writer) {
                writer.write(bb, 0, bb.length);
                writer.write('\n');
                newData = true;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void openWriter() {
        File file = new File(LOG_FILE);
        try {
            if(!file.exists())
                file.createNewFile();
            writer = new BufferedOutputStream(new FileOutputStream(file));
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while(true) {
                        if (writer != null) {
                            if(newData) {
                                synchronized (writer) {
                                    newData = false;
                                    try {
                                        writer.flush();
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                        break;
                                    }
                                }
                            }
                        }
                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            break;
                        }
                    }
                }
            }).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void closeWriter() {
        synchronized (writer) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                writer = null;
            }
        }
    }

    public static class JsonSerializer {

        public static String toJson(Entry entry) {
            String json = null;
            Writer writer = null;
            JsonWriter jsonWriter = null;
            try {
                writer = new StringWriter();
                jsonWriter = new JsonWriter(writer);
                writeEntry(jsonWriter, entry);
                json = writer.toString();
            } catch(IOException e) {
                Log.e(TAG, "Could not write theme mapping", e);
            } finally {
                closeQuietly(writer);
                closeQuietly(jsonWriter);
            }
            return json;
        }

        private static void writeEntry(JsonWriter writer, Entry entry)
                throws IOException {
            writer.beginObject();
            for(Map.Entry<String, String> en : entry.map.entrySet()) {
                String name = en.getKey();
                String val = en.getValue();
                writer.name(name).value(val);
            }
            writer.endObject();
        }

        public static Entry fromJson(String json) {
            if (json == null) return null;
            Map<String, String> map = new ArrayMap<>();
            StringReader reader = null;
            JsonReader jsonReader = null;
            try {
                reader = new StringReader(json);
                jsonReader = new JsonReader(reader);
                jsonReader.beginObject();
                while (jsonReader.hasNext()) {
                    String name = jsonReader.nextName();
                    String val = jsonReader.nextString();
                    map.put(name, val);
                }
                jsonReader.endObject();
            } catch(Exception e) {
                Log.e(TAG, "Could not parse ThemeConfig from: " + json, e);
            } finally {
                closeQuietly(reader);
                closeQuietly(jsonReader);
            }
            return new Entry(map);
        }

        private static void closeQuietly(Reader reader) {
            try {
                if (reader != null) reader.close();
            } catch(IOException e) {
            }
        }

        private static void closeQuietly(JsonReader reader) {
            try {
                if (reader != null) reader.close();
            } catch(IOException e) {
            }
        }

        private static void closeQuietly(Writer writer) {
            try {
                if (writer != null) writer.close();
            } catch(IOException e) {
            }
        }

        private static void closeQuietly(JsonWriter writer) {
            try {
                if (writer != null) writer.close();
            } catch(IOException e) {
            }
        }
    }

    void systemRunning() {
        // Shall we wait for the initialization finish.
        BackgroundThread.getHandler().post(this);
    }

    public void run() {
        initialize();
        mSystemReady = true;
    }

    private void initialize() {
         mode = SystemProperties.getInt("facebot.mode", 0);
    }

    // For testing
    boolean isSystemReady() {
        return mSystemReady;
    }

}

/**
 * Entry for API call logging
 * <p>
 * {@hide}
 */
class Entry {
    Map<String, String> map;

    Entry(String className, String methodName, String arguments, String result) {
        map = new ArrayMap<>();
        map.put("className",className);
        map.put("methodName",methodName);
        map.put("arguments",arguments);
        map.put("result",result);
    }

    public Entry(Map<String, String> map) {
        this.map = map;
    }

    public final int hashCode() {
        return map.get("className").hashCode() + map.get("methodName").hashCode() + map.get("arguments").hashCode();
    }

}
