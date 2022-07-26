package clox;

import static clox.Memory.*;
import static clox.ObjectLox.*;
import static clox.Value.*;

public class Table {
    public static class Entry{
        ObjString key;
        Value value;
    }

    int count;
    int capacity;
    Entry[] entries;

    public static final double TABLE_MAX_LOAD = 0.75;

    public static void initTable(Table table) {
        table.count = 0;
        table.capacity = 0;
        table.entries = null;
    }

    public static void freeTable(Table table) {
        FREE_ARRAY(table.entries, table.capacity);
        initTable(table);
    }

    public static Entry findEntry(Entry[] entries, int capacity, ObjString key) {
        int index = key.hash & capacity - 1;
        Entry tombstone = null;

        for (;;) {
            Entry entry = entries[index];
            if (entry.key == null) {
                if (IS_NIL(entry.value))
                    return tombstone != null ? tombstone : entry;
            } else if (entry.key == key) {
                return entry;
            }

            index = (index + 1) & capacity - 1;
        }
    }

    public static void adjustCapacity(Table table, int capacity) {
        Entry[] entries = ALLOCATE(new Entry[capacity]);
        for (int i = 0; i < capacity; i++) {
            entries[i] = new Entry();
            entries[i].key = null;
            entries[i].value = NIL_VAL();
        }

        table.count = 0;
        for (int i = 0; i < table.capacity; i++) {
            Entry  entry = table.entries[i];
            if (entry.key == null) continue;

            Entry  dest = findEntry(entries, capacity, entry.key);
            dest.key = entry.key;
            dest.value = entry.value;
            table.count++;
        }

        FREE_ARRAY(table.entries, table.capacity);
        table.entries = entries;
        table.capacity = capacity;
    }

    public static boolean tableGet(Table table, ObjString key, Value value) {
        if (table.count == 0)return false;

        Entry entry = findEntry(table.entries, table.capacity, key);
        if (entry.key == null) return false;

        value.set(entry.value);
        return true;
    }

    public static boolean tableSet(Table table, ObjString key, Value value) {
        if (table.count + 1 > table.capacity * TABLE_MAX_LOAD) {
            int capacity = GROW_CAPACITY(table.capacity);
            adjustCapacity(table, capacity);
        }

        Entry entry = findEntry(table.entries, table.capacity, key);
        boolean isNewKey = entry.key == null;
        if (isNewKey && IS_NIL(entry.value)) table.count++;

        entry.key = key;
        entry.value = value;
        return isNewKey;
    }

    public static boolean tableDelete(Table table, ObjString key) {
        if (table.count == 0)return false;

        Entry entry = findEntry(table.entries, table.capacity, key);
        if (entry.key == null) return false;

        entry.key = null;
        entry.value = BOOL_VAL(true);
        return true;
    }

    public static void tableAddAll(Table from, Table to) {
        for (int i = 0; i < from.capacity; ++i) {
            Entry entry = from.entries[i];
            if (entry.key != null) {
                tableSet(to, entry.key, entry.value);
            }
        }
    }

    public static ObjString tableFindString(Table table, String chars, int length, int hash) {
        if (table.count == 0) return null;

        long index = hash & table.capacity - 1;

        for (;;) {
            Entry entry = table.entries[(int) index];
            if (entry.key == null) {
                if (IS_NIL(entry.value)) return null;
            } else if (entry.key.length == length &&
                    entry.key.hash == hash &&
                            memcmp(entry.key.chars, chars, length) == 0)
                return entry.key;
            index = (index + 1) & table.capacity - 1;
        }
    }

    public static void tableRemoveWhite(Table table){
        for(int i = 0; i < table.capacity; i++){
            Entry entry = table.entries[i];
            if(entry.key != null && !entry.key.isMarked){
                tableDelete(table, entry.key);
            }
        }
    }

    public static void markTable(Table table){
        for(int i = 0; i < table.capacity; i++){
            Entry entry = table.entries[i];
            markObject(entry.key);
            markValue(entry.value);
        }
    }
}
