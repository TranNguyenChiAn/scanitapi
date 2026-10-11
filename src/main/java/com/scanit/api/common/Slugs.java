package com.scanit.api.common;

import java.text.Normalizer;
import java.util.Locale;

public final class Slugs {
    private Slugs() {
    }

    /** Bo dau tieng Viet, chi giu chu va so. "Xám than" -> "xam than". */
    public static String plain(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.trim().replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    /** "Xám than" -> "XAMTHAN" (dung cho SKU). */
    public static String compactUpper(String s) {
        return plain(s).replaceAll("[^a-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    /** "Set len" -> "set-len" (dung cho slug danh muc). */
    public static String hyphen(String s) {
        return plain(s).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}
