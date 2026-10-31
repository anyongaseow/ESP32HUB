package com.esphub;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Daftar ESP32 (ID, SSID, password), disimpan di SharedPreferences sebagai JSON. */
final class Store {

    static final class Esp {
        long key;      // kunci internal, tidak berubah walau ID/SSID diganti
        String id = "";
        String ssid = "";
        String pass = "";

        Esp copy() {
            Esp e = new Esp();
            e.key = key; e.id = id; e.ssid = ssid; e.pass = pass;
            return e;
        }
    }

    private static Store instance;

    static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }

    private final SharedPreferences sp;
    private final List<Esp> list = new ArrayList<>();

    private Store(Context c) {
        sp = c.getSharedPreferences("esp", Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(sp.getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Esp e = new Esp();
                e.key = o.getLong("k");
                e.id = o.optString("id");
                e.ssid = o.optString("s");
                e.pass = o.optString("p");
                list.add(e);
            }
        } catch (JSONException ignored) {
        }
        sort();
    }

    synchronized List<Esp> all() {
        List<Esp> out = new ArrayList<>();
        for (Esp e : list) out.add(e.copy());
        return out;
    }

    synchronized Esp find(long key) {
        for (Esp e : list) if (e.key == key) return e.copy();
        return null;
    }

    synchronized int size() {
        return list.size();
    }

    /** Tambah (key == 0) atau ganti data ESP yang sudah ada. */
    synchronized void put(Esp e) {
        if (e.key == 0) {
            e.key = sp.getLong("next", 1);
            sp.edit().putLong("next", e.key + 1).apply();
            list.add(e.copy());
        } else {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).key == e.key) { list.set(i, e.copy()); break; }
            }
        }
        sort();
        save();
    }

    synchronized void putAll(List<Esp> items) {
        long next = sp.getLong("next", 1);
        for (Esp e : items) {
            e.key = next++;
            list.add(e.copy());
        }
        sp.edit().putLong("next", next).apply();
        sort();
        save();
    }

    synchronized void remove(long key) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).key == key) { list.remove(i); break; }
        }
        save();
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Esp e : list) {
                a.put(new JSONObject().put("k", e.key).put("id", e.id).put("s", e.ssid).put("p", e.pass));
            }
        } catch (JSONException ignored) {
        }
        sp.edit().putString("list", a.toString()).apply();
    }

    private void sort() {
        Collections.sort(list, (x, y) -> naturalCompare(x.id, y.id));
    }

    /** Urutan alami: "ESP 2" sebelum "ESP 10". */
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) return c;
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
