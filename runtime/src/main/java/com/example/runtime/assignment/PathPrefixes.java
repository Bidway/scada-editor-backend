package com.example.runtime.assignment;

/**
 * Префиксы путей тегов сравниваются по границе сегмента: {@code A.B} покрывает {@code A.B} и
 * {@code A.B.C}, но не {@code A.BC}. Иначе «Барановичи-1» покрыло бы «Барановичи-10».
 */
public final class PathPrefixes {

    private PathPrefixes() {
    }

    public static boolean covers(String prefix, String path) {
        return path.equals(prefix) || path.startsWith(prefix + ".");
    }

    /** Один префикс покрывает другой — выбор топика для пути стал бы неоднозначным. */
    public static boolean overlap(String a, String b) {
        return covers(a, b) || covers(b, a);
    }
}
