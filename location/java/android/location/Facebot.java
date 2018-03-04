package android.location;

import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;

import android.util.*;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Injector for system API calls
 * <p>
 * {@hide}
 */
public class Facebot {

    private final static String TAG = "Facebot";
    private final IFacebot mService;

    /**
     * @hide - hide this constructor because it has a parameter of type
     *       ICountryDetector, which is a system private class. The right way to
     *       create an instance of this class is using the factory
     *       Context.getSystemService.
     */
    public Facebot(IFacebot service) {
        mService = service;
    }

    private final HashMap<Entry, Entry> ENTRIES = new HashMap();
    private final String LOG_FILE = "/sdcard/facebot_api_calls.log";

    private BufferedOutputStream writer = null;
    private boolean newData = false;

    public String addEntry(String className, String methodName, String arguments, String result) {
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

    private static void writeEntry(Entry entry) {
        synchronized (ENTRIES) {
            if (writer == null)
                openWriter();
        }
        if(writer == null)
            return;
        String ss = JsonSerializer.toJson(entry);
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
        map.put("pid",Integer.toString(android.os.Process.myPid()));
    }

    public Entry(Map<String, String> map) {
        this.map = map;
    }

    public final int hashCode() {
        return map.get("className").hashCode() + map.get("methodName").hashCode() + map.get("arguments").hashCode();
    }

}

