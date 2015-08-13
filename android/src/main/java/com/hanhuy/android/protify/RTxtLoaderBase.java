package com.hanhuy.android.protify;


import android.util.Log;

import java.io.*;

public abstract class RTxtLoaderBase {

    private final static String TAG = "RTxtLoader";
    private String lasthash;

    private static boolean eq(Object o1, Object o2) {
        return o1 == null ? o2 == null : o1.equals(o2);
    }

    public void load(String rtxt, String hash) {
        if (!eq(lasthash,hash)) {
            if (rtxt != null) {
                File f = new File(rtxt);
                if (f.isFile()) {

                    BufferedReader r = null;
                    try {
                        r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "utf-8"));
                        String line;
                        while ((line = r.readLine()) != null) {
                            String[] parts = line.split(" ");
                            switch (parts[0]) {
                                case "int": {
                                    String clazz = parts[1];
                                    String name = parts[2];
                                    int value = asInt(parts[3]);
                                    setInt(clazz, name, value);
                                    break;
                                }
                                case "int[]": {
                                    String clazz = parts[1];
                                    String name = parts[2];
                                    int[] value = new int[parts.length - 5];
                                    for (int i = 0, j = 4; i < value.length; i++, j++) {
                                        value[i] = asInt(parts[j].replaceAll(",",""));
                                    }
                                    setIntArray(clazz, name, value);
                                    break;
                                }
                            }
                        }

                    } catch (IOException e) {
                        // swallow
                    } finally {
                        try {
                            if (r != null) r.close();
                        } catch (IOException ex) {
                            // swallow
                        }
                    }
                }
            }

        } else {
            Log.v(TAG, "Skipping R.txt loading");
        }
        lasthash = hash;
    }

    private static int asInt(String s) {
        return s.startsWith("0x") ? Integer.parseInt(s.substring(2), 16) : Integer.parseInt(s);
    }

    public abstract void setInt(String clazz, String name, int value);
    public abstract void setIntArray(String clazz, String name, int[] value);
}
