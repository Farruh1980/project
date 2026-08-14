package ru.depositcalc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import ru.depositcalc.DepositCalculator.Capitalization;
import ru.depositcalc.DepositCalculator.CashFlow;
import ru.depositcalc.DepositCalculator.Params;
import ru.depositcalc.DepositCalculator.Result;

/** Тесты расчётного ядра Android-приложения. Ожидания совпадают с Python-версией. */
public class DepositCalculatorTest {

    private Params params(Capitalization capitalization, int months) {
        Params p = new Params();
        p.amount = new BigDecimal("100000");
        p.annualRate = new BigDecimal("10");
        p.start = LocalDate.of(2023, 1, 1);
        p.end = p.start.plusMonths(months);
        p.capitalization = capitalization;
        return p;
    }

    @Test
    public void простыеПроцентыЗаГод() {
        Result r = DepositCalculator.calculate(params(Capitalization.NONE, 12));
        assertEquals("10000.00", r.totalInterest.toPlainString());
        assertEquals("110000.00", r.payout().toPlainString());
    }

    @Test
    public void високосныйГодДелитсяНа366() {
        Params p = params(Capitalization.NONE, 12);
        p.start = LocalDate.of(2024, 1, 1);
        p.end = LocalDate.of(2025, 1, 1);
        assertEquals(366, p.termDays());
        assertEquals("10000.00", DepositCalculator.calculate(p).totalInterest.toPlainString());
    }

    @Test
    public void капитализацияВыгоднееПростыхПроцентов() {
        BigDecimal простые = DepositCalculator.calculate(params(Capitalization.NONE, 12)).payout();
        BigDecimal месяц = DepositCalculator.calculate(params(Capitalization.MONTHLY, 12)).payout();
        BigDecimal день = DepositCalculator.calculate(params(Capitalization.DAILY, 12)).payout();
        assertTrue(простые.compareTo(месяц) < 0);
        assertTrue(месяц.compareTo(день) < 0);
    }

    @Test
    public void ежемесячнаяКапитализацияСовпадаетСPython() {
        Result r = DepositCalculator.calculate(params(Capitalization.MONTHLY, 12));
        assertEquals("10471.28", r.totalInterest.toPlainString());
        assertEquals("110471.28", r.payout().toPlainString());
    }

    @Test
    public void годоваяКапитализацияЗаГодРавнаПростымПроцентам() {
        assertEquals(
                DepositCalculator.calculate(params(Capitalization.NONE, 12)).payout(),
                DepositCalculator.calculate(params(Capitalization.ANNUAL, 12)).payout());
    }

    @Test
    public void пополненияУвеличиваютДоход() {
        Params p = params(Capitalization.MONTHLY, 12);
        p.cashFlows = DepositCalculator.periodicCashFlows(
                p.start, p.end, new BigDecimal("10000"), 1);
        Result r = DepositCalculator.calculate(p);
        assertEquals(11, p.cashFlows.size());
        assertEquals("110000.00", r.totalTopups.toPlainString());
        assertEquals("210000.00", r.invested().toPlainString());
        assertTrue(r.totalInterest.compareTo(new BigDecimal("10471.28")) > 0);
    }

    @Test
    public void снятиеНижеНеснижаемогоОстаткаЗапрещено() {
        Params p = params(Capitalization.NONE, 12);
        p.minBalance = new BigDecimal("50000");
        p.cashFlows = Collections.singletonList(
                new CashFlow(LocalDate.of(2023, 7, 1), new BigDecimal("-60000")));
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> DepositCalculator.calculate(p));
        assertTrue(e.getMessage().contains("неснижаемый остаток"));
    }

    @Test
    public void доходРавенНачисленнымПроцентам() {
        Result r = DepositCalculator.calculate(params(Capitalization.MONTHLY, 12));
        assertEquals(r.totalInterest, r.income());
        assertEquals(r.balanceAtEnd, r.payout());
    }

    @Test
    public void эффективнаяСтавкаБезКапитализацииРавнаНоминальной() {
        Result r = DepositCalculator.calculate(params(Capitalization.NONE, 12));
        assertEquals("10.00", r.effectiveRate.toPlainString());
    }

    @Test
    public void графикПокрываетВесьСрокБезРазрывов() {
        Result r = DepositCalculator.calculate(params(Capitalization.MONTHLY, 12));
        List<DepositCalculator.PeriodRow> rows = r.rows;
        assertEquals(12, rows.size());
        assertEquals(LocalDate.of(2023, 1, 1), rows.get(0).start);
        assertEquals(LocalDate.of(2024, 1, 1), rows.get(rows.size() - 1).end);
        for (int i = 1; i < rows.size(); i++) {
            assertEquals(rows.get(i - 1).end, rows.get(i).start);
            assertEquals(rows.get(i - 1).closing, rows.get(i).opening);
        }
        assertEquals(rows.get(rows.size() - 1).closing, r.balanceAtEnd);
    }

    @Test
    public void некорректныеУсловияОтклоняются() {
        Params нулевая = params(Capitalization.NONE, 12);
        нулевая.amount = BigDecimal.ZERO;
        assertThrows(IllegalArgumentException.class, нулевая::validate);

        Params обратныйСрок = params(Capitalization.NONE, 12);
        обратныйСрок.end = обратныйСрок.start.minusDays(1);
        assertThrows(IllegalArgumentException.class, обратныйСрок::validate);
    }

    @Test
    public void форматированиеСумм() {
        assertEquals("1 234 567,89", Formats.money(new BigDecimal("1234567.891")));
        assertEquals("1 234 567,89 сўм", Formats.sum(new BigDecimal("1234567.891")));
        assertEquals("0,00", Formats.money(BigDecimal.ZERO));
        assertEquals("−500,00", Formats.money(new BigDecimal("-500")));
        assertEquals("16,00 %", Formats.percent(new BigDecimal("16")));
    }

    @Test
    public void разборЧиселСЗапятойИПробелами() {
        assertEquals(new BigDecimal("1000000"),
                Formats.parseNumber("1 000 000", BigDecimal.ZERO));
        assertEquals(new BigDecimal("12.5"), Formats.parseNumber("12,5", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> Formats.parseNumber("много", BigDecimal.ZERO));
    }
}
