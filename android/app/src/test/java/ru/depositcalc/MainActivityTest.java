package ru.depositcalc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.EditText;
import android.widget.TableLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Проверяет, что экран запускается и кнопка «Рассчитать» выдаёт результат. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityTest {

    private MainActivity открытьЭкран() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    private void заполнить(MainActivity activity, String сумма, String ставка, String срок) {
        ((EditText) activity.findViewById(R.id.amount)).setText(сумма);
        ((EditText) activity.findViewById(R.id.rate)).setText(ставка);
        ((EditText) activity.findViewById(R.id.term)).setText(срок);
    }

    @Test
    public void экранОткрываетсяБезОшибок() {
        MainActivity activity = открытьЭкран();
        assertEquals("Калькулятор вкладов", activity.getString(R.string.app_name));
        // До расчёта карточки результата скрыты.
        assertEquals(View.GONE, activity.findViewById(R.id.result_card).getVisibility());
    }

    @Test
    public void расчётПоказываетСуммуГрафикИСравнение() {
        MainActivity activity = открытьЭкран();
        заполнить(activity, "100000", "10", "12");
        activity.findViewById(R.id.calculate).performClick();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.result_card).getVisibility());
        String выплата = ((TextView) activity.findViewById(R.id.payout)).getText().toString();
        assertTrue("получено: " + выплата, выплата.startsWith("110 4"));
        assertTrue("получено: " + выплата, выплата.endsWith("сўм"));

        TableLayout график = activity.findViewById(R.id.schedule);
        assertEquals(14, график.getChildCount()); // шапка + 12 месяцев + итог

        String сравнение = ((TextView) activity.findViewById(R.id.compare)).getText().toString();
        assertTrue(сравнение.contains("ежедневная"));
        assertTrue(сравнение.contains("← выбрано"));
    }

    @Test
    public void пустаяСуммаПоказываетОшибку() {
        MainActivity activity = открытьЭкран();
        заполнить(activity, "", "10", "12");
        activity.findViewById(R.id.calculate).performClick();

        View ошибка = activity.findViewById(R.id.error);
        assertEquals(View.VISIBLE, ошибка.getVisibility());
        assertEquals("Введите сумму вклада", ((TextView) ошибка).getText().toString());
        assertEquals(View.GONE, activity.findViewById(R.id.result_card).getVisibility());
    }

    @Test
    public void нецифровойВводНеПопадаетВПолеСуммы() {
        MainActivity activity = открытьЭкран();
        заполнить(activity, "сто тысяч", "10", "12");
        // Разбивка по разрядам отбрасывает всё, кроме цифр, — поле остаётся пустым.
        assertEquals("", ((EditText) activity.findViewById(R.id.amount)).getText().toString());

        activity.findViewById(R.id.calculate).performClick();
        View ошибка = activity.findViewById(R.id.error);
        assertEquals(View.VISIBLE, ошибка.getVisibility());
        assertEquals("Введите сумму вклада", ((TextView) ошибка).getText().toString());
    }

    @Test
    public void нечисловаяСтавкаНеРоняетПриложение() {
        MainActivity activity = открытьЭкран();
        заполнить(activity, "1000000", "много", "12");
        activity.findViewById(R.id.calculate).performClick();

        View ошибка = activity.findViewById(R.id.error);
        assertEquals(View.VISIBLE, ошибка.getVisibility());
        assertTrue(((TextView) ошибка).getText().toString().contains("Не удалось разобрать"));
    }

    @Test
    public void вводСуммыРазбиваетсяПоРазрядам() {
        MainActivity activity = открытьЭкран();
        EditText сумма = activity.findViewById(R.id.amount);
        сумма.setText("1000000000000");
        assertEquals("1 000 000 000 000", сумма.getText().toString());
        assertEquals("курсор должен стоять в конце",
                сумма.getText().length(), сумма.getSelectionStart());

        сумма.setText("50000");
        assertEquals("50 000", сумма.getText().toString());
    }

    @Test
    public void расчётПонимаетСуммуСРазрядами() {
        MainActivity activity = открытьЭкран();
        заполнить(activity, "1000000", "10", "12");
        assertEquals("1 000 000",
                ((EditText) activity.findViewById(R.id.amount)).getText().toString());
        activity.findViewById(R.id.calculate).performClick();

        // Эталон считаем ядром на тех же условиях: экран открывается на сегодняшней
        // дате, поэтому жёсткое число здесь зависело бы от дня запуска теста.
        DepositCalculator.Params эталон = new DepositCalculator.Params();
        эталон.amount = new java.math.BigDecimal("1000000");
        эталон.annualRate = new java.math.BigDecimal("10");
        эталон.start = java.time.LocalDate.now();
        эталон.end = эталон.start.plusMonths(12);
        эталон.capitalization = DepositCalculator.Capitalization.MONTHLY;

        String выплата = ((TextView) activity.findViewById(R.id.payout)).getText().toString();
        assertEquals(Formats.sum(DepositCalculator.calculate(эталон).payout()), выплата);
        assertTrue("получено: " + выплата, выплата.startsWith("1 10"));
    }

    @Test
    public void полеСтавкиНеРазбиваетсяПоРазрядам() {
        MainActivity activity = открытьЭкран();
        EditText ставка = activity.findViewById(R.id.rate);
        ставка.setText("24,5");
        assertEquals("24,5", ставка.getText().toString());
    }

    @Test
    public void пополнениеУчитываетсяВРасчёте() {
        MainActivity activity = открытьЭкран();
        заполнить(activity, "100000", "10", "12");
        EditText пополнение = activity.findViewById(R.id.topup);
        пополнение.setText("10000");
        assertEquals("10 000", пополнение.getText().toString());
        activity.findViewById(R.id.calculate).performClick();

        String итог = ((TextView) activity.findViewById(R.id.summary)).getText().toString();
        assertTrue(итог.contains("В том числе пополнений: 110 000,00 сўм"));
        assertTrue(итог.contains("Вложено собственных: 210 000,00 сўм"));
    }
}
