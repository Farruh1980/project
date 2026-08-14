package ru.depositcalc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Тесты разбивки вводимых сумм по разрядам. */
public class ThousandsWatcherTest {

    @Test
    public void разрядыРасставляютсяПоТриЦифры() {
        assertEquals("1", ThousandsWatcher.format("1"));
        assertEquals("999", ThousandsWatcher.format("999"));
        assertEquals("1 000", ThousandsWatcher.format("1000"));
        assertEquals("10 000", ThousandsWatcher.format("10000"));
        assertEquals("1 000 000", ThousandsWatcher.format("1000000"));
        assertEquals("1 000 000 000 000", ThousandsWatcher.format("1000000000000"));
    }

    @Test
    public void уженабранныеПробелыНеДублируются() {
        assertEquals("1 000 000", ThousandsWatcher.format("1 000 000"));
        assertEquals("1 000 000", ThousandsWatcher.format("1 0 0 0 0 0 0"));
    }

    @Test
    public void дробнаяЧастьНеГруппируется() {
        assertEquals("1 234 567,89", ThousandsWatcher.format("1234567,89"));
        assertEquals("1 234 567,89", ThousandsWatcher.format("1234567.89"));
        assertEquals("50 000,", ThousandsWatcher.format("50000,"));
    }

    @Test
    public void ведущиеНулиУбираются() {
        assertEquals("7", ThousandsWatcher.format("007"));
        assertEquals("0", ThousandsWatcher.format("0"));
        assertEquals("0,5", ThousandsWatcher.format("0,5"));
    }

    @Test
    public void пустаяСтрокаОстаётсяПустой() {
        assertEquals("", ThousandsWatcher.format(""));
        assertEquals("", ThousandsWatcher.format("   "));
    }

    @Test
    public void посторонниеСимволыОтбрасываются() {
        assertEquals("500 000", ThousandsWatcher.format("500000 сўм"));
    }

    @Test
    public void отформатированноеЗначениеРазбираетсяОбратно() {
        assertEquals(new java.math.BigDecimal("1000000000000"),
                Formats.parseNumber(ThousandsWatcher.format("1000000000000"), null));
        assertEquals(new java.math.BigDecimal("1234567.89"),
                Formats.parseNumber(ThousandsWatcher.format("1234567,89"), null));
    }
}
