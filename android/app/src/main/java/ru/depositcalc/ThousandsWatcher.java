package ru.depositcalc;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

/**
 * Расставляет разряды прямо во время ввода: «1000000» превращается
 * в «1 000 000». Дробная часть после запятой не группируется, позиция
 * курсора сохраняется — пользователь продолжает набор с того же места.
 */
public final class ThousandsWatcher implements TextWatcher {

    private final EditText field;
    private boolean editing;

    private ThousandsWatcher(EditText field) {
        this.field = field;
    }

    /** Включает разбивку по разрядам для поля и форматирует его текущий текст. */
    public static void attach(EditText field) {
        field.addTextChangedListener(new ThousandsWatcher(field));
        Editable text = field.getText();
        String formatted = format(text.toString());
        if (!formatted.equals(text.toString())) {
            field.setText(formatted);
        }
    }

    /** «1000000,5» → «1 000 000,5». Лишние символы отбрасываются. */
    public static String format(String input) {
        StringBuilder digits = new StringBuilder();
        String fraction = null;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= '0' && c <= '9') {
                if (fraction == null) {
                    digits.append(c);
                } else {
                    fraction = fraction + c;
                }
            } else if ((c == ',' || c == '.') && fraction == null) {
                fraction = "";
            }
        }

        // Ведущие нули не нужны: «007» — это 7, но одиночный «0» оставляем.
        int firstSignificant = 0;
        while (firstSignificant < digits.length() - 1 && digits.charAt(firstSignificant) == '0') {
            firstSignificant++;
        }
        String whole = digits.substring(firstSignificant);

        StringBuilder grouped = new StringBuilder();
        int counter = 0;
        for (int i = whole.length() - 1; i >= 0; i--) {
            grouped.append(whole.charAt(i));
            if (++counter % 3 == 0 && i > 0) {
                grouped.append(' ');
            }
        }
        String result = grouped.reverse().toString();
        return fraction == null ? result : result + "," + fraction;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
        if (editing) {
            return;
        }
        editing = true;
        try {
            String current = s.toString();
            int значимыхДоКурсора = countSignificant(current, field.getSelectionStart());
            String formatted = format(current);
            if (!formatted.equals(current)) {
                s.replace(0, s.length(), formatted);
            }
            field.setSelection(offsetAfter(formatted, значимыхДоКурсора));
        } finally {
            editing = false;
        }
    }

    /** Сколько цифр и запятых стоит левее позиции курсора. */
    private static int countSignificant(String text, int position) {
        int count = 0;
        for (int i = 0; i < Math.min(position, text.length()); i++) {
            if (text.charAt(i) != ' ') {
                count++;
            }
        }
        return count;
    }

    /** Позиция курсора после указанного количества значимых символов. */
    private static int offsetAfter(String text, int significant) {
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != ' ') {
                seen++;
            }
            if (seen == significant) {
                return i + 1;
            }
        }
        return text.length();
    }
}
