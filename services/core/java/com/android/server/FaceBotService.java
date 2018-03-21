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

import android.app.ActivityManager;
import android.content.Context;
import com.facebot.*;
import android.os.*;
import android.util.*;
import com.android.internal.os.BackgroundThread;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @hide
 */
public class FaceBotService extends IFaceBot.Stub implements Runnable {

    private final static String TAG = "FaceBotService";
    public static final String FACEBOT_MODE_PROPERTY = "persist.facebot.mode";

    private final Context mContext;
    private boolean mSystemReady;

    public FaceBotService(Context context) {
        super();
        mContext = context;
    }

    private final HashMap<Entry, Entry> CURR_ENTRIES = new HashMap();
    private final HashMap<Entry, Entry> NEW_ENTRIES = new HashMap();
    private final String FILE_WRITE = "/data/facebot_write.log";
    private final String FILE_READ  = "/data/facebot_read.log";

//    private BufferedOutputStream writer = null;
    private boolean newData = false;

    enum FacebotMode {
        DISABLED,
        RECORD,
        PLAY
    }

    private FacebotMode mode;
    private HashMap<Integer,String> processes = new HashMap<>();

    private void loadProcesses() {
        ActivityManager manager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo processInfo : manager.getRunningAppProcesses()) {
            processes.put(processInfo.pid,processInfo.processName);
        }
    }

    private String getProcessName(int pid) {
        String processName = processes.get(pid);
        if(processName == null) {
            processName = Integer.toString(pid);
        }
        return processName;
    }

    @Override
    public String addEntry(int pid, String className, String methodName, String arguments, String result) {
        if (!mSystemReady) {
            return result;   // server not yet active
        }
        String spid = getProcessName(pid);
        switch(mode) {
            case DISABLED:
                break;
            case RECORD: {
                Entry entry = new Entry(spid, className, methodName, arguments, result);
                boolean storeEntry = false;
                synchronized (CURR_ENTRIES) {
                    if (!CURR_ENTRIES.containsKey(entry)) {
                        storeEntry = true;
                    }
                    else {
                        Entry oldEntry = CURR_ENTRIES.get(entry);
                        String oldRez = oldEntry.map.get("result");
                        String oldPid = oldEntry.map.get("pid");
                        if (!oldRez.equals(result) || !oldPid.equals(spid)) {
                            storeEntry = true;
                        }
                    }
                    if (storeEntry) {
                        CURR_ENTRIES.put(entry, entry);
                        synchronized (NEW_ENTRIES) {
                            NEW_ENTRIES.put(entry, entry);
                            newData = true;
                        }
                    }
                }
                break;
            }
            case PLAY: {
                Entry entry = new Entry(spid,className, methodName, arguments, result);
                Entry oldEntry = CURR_ENTRIES.get(entry);
                if(oldEntry != null) {
                    result = oldEntry.map.get("result");
                }
                break;
            }
        }
        return result;
    }

    private void writeEntries() throws IOException {
        try(FileWriter fw = new FileWriter(FILE_WRITE, true);
            BufferedWriter bw = new BufferedWriter(fw);
            PrintWriter out = new PrintWriter(bw)) {
            HashMap<Entry,Entry> temp = new HashMap<Entry,Entry>();
            synchronized (NEW_ENTRIES) {
                temp.putAll(NEW_ENTRIES);
                NEW_ENTRIES.clear();
                newData = false;
            }
            for (Entry entry :
                    temp.values()) {
                String ss = FaceBotService.JsonSerializer.toJson(entry);
                out.println(ss);
            }
        }
    }

    private void loadEntries() {
        File file = new File(FILE_READ);
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                Entry entry = FaceBotService.JsonSerializer.fromJson(line);
                CURR_ENTRIES.put(entry,entry);
                Log.d(TAG,"Loaded entry:"+entry.toString());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startWriterThread() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while(true) {
                    loadProcesses();
                    if(newData) {
                        try {
                            writeEntries();
                        } catch (IOException e) {
                            // clear map to save resources
                            synchronized (NEW_ENTRIES) {
                                NEW_ENTRIES.clear();
                                newData = false;
                            }
                            e.printStackTrace();
                        }
                    }
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        break;
                    }
                }
            }
        }).start();
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
                Log.e(TAG, "Could not write entry", e);
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
                Log.e(TAG, "Could not parse entry from: " + json, e);
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
         mode = FacebotMode.values()[SystemProperties.getInt(FACEBOT_MODE_PROPERTY, 0)];
         switch(mode) {
             case DISABLED:
                 break;
             case RECORD:
                 startWriterThread();
                 break;
             case PLAY:
                 loadEntries();
                 break;
         }
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

    Entry(String pid,String className, String methodName, String arguments, String result) {
        map = new ArrayMap<>();
        map.put("pid",pid);
        map.put("className",className);
        map.put("methodName",methodName);
        map.put("arguments",arguments);
        map.put("result",result);
    }

    public Entry(Map<String, String> map) {
        this.map = map;
    }

    @Override
    public int hashCode() {
        String args = map.get("arguments");
        return map.get("className").hashCode() + map.get("methodName").hashCode() +
                (args == null ? 0 : args.hashCode());
    }

    @Override
    public String toString() {
        return String.format("[%s] %s.%s(%s): <%s>",map.get("pid"),map.get("className"),map.get("methodName"),map.get("arguments"),map.get("result"));
    }

    @Override
    public boolean equals(Object obj) {
        return hashCode() == obj.hashCode();
    }
}
