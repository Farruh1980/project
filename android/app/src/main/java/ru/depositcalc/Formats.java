package ru.depositcalc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Форматирование сумм, ставок и дат в привычном русском виде. */
public final class Formats {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private Formats() {
    }

    /** 1234567.891 → «1 234 567,89». */
    public static String money(BigDecimal value) {
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        boolean negative = rounded.signum() < 0;
        String digits = rounded.abs().toPlainString();
        int dot = digits.indexOf('.');
        String whole = dot < 0 ? digits : digits.substring(0, dot);
        String fraction = dot < 0 ? "00" : digits.substring(dot + 1);

        StringBuilder grouped = new StringBuilder();
        int counter = 0;
        for (int i = whole.length() - 1; i >= 0; i--) {
            grouped.append(whole.charAt(i));
            if (++counter % 3 == 0 && i > 0) {
                grouped.append(' ');
            }
        }
        String result = grouped.reverse() + "," + fraction;
        return negative ? "−" + result : result;
    }

    /** Сумма с рублём: «1 234 567,89 ₽». */
    public static String rub(BigDecimal value) {
        return money(value) + " ₽";
    }

    /** «16,00 %». */
    public static String percent(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " %";
    }

    /** «01.02.2026». */
    public static String date(LocalDate value) {
        return value.format(DATE);
    }

    /** Разбирает число, введённое с запятой или пробелами. */
    public static BigDecimal parseNumber(String text, BigDecimal fallback) {
        if (text == null) {
            return fallback;
        }
        String cleaned = text.replaceAll("[\\s ]", "").replace(',', '.').replace("%", "");
        if (cleaned.isEmpty()) {
            return fallback;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Не удалось разобрать число: " + text);
        }
    }

    /** Правильное окончание: 1 день, 2 дня, 5 дней. */
    public static String plural(long n, String one, String few, String many) {
        long mod10 = n % 10;
        long mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return few;
        }
        return many;
    }
}
