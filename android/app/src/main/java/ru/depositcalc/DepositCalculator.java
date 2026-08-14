package ru.depositcalc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Расчётное ядро калькулятора вкладов — порт deposit_calc/core.py на Java.
 *
 * Проценты начисляются ежедневно на текущий остаток:
 * процент за день = остаток * ставка / 100 / дней_в_году,
 * где дней_в_году — 365 или 366 в зависимости от того, високосный ли год,
 * которому принадлежит день начисления. Начисление идёт за каждый день
 * периода [дата открытия, дата закрытия).
 */
public final class DepositCalculator {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private DepositCalculator() {
    }

    /** Периодичность капитализации. */
    public enum Capitalization {
        NONE("без капитализации", 0),
        DAILY("ежедневная", -1),
        MONTHLY("ежемесячная", 1),
        QUARTERLY("ежеквартальная", 3),
        SEMIANNUAL("раз в полгода", 6),
        ANNUAL("ежегодная", 12);

        public final String title;
        public final int stepMonths;

        Capitalization(String title, int stepMonths) {
            this.title = title;
            this.stepMonths = stepMonths;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** Движение по вкладу: пополнение (+) или частичное снятие (−). */
    public static final class CashFlow implements Comparable<CashFlow> {
        public final LocalDate date;
        public final BigDecimal amount;

        public CashFlow(LocalDate date, BigDecimal amount) {
            this.date = date;
            this.amount = money(amount);
        }

        @Override
        public int compareTo(CashFlow other) {
            return date.compareTo(other.date);
        }
    }

    /** Условия вклада. */
    public static final class Params {
        public BigDecimal amount = BigDecimal.ZERO;
        public BigDecimal annualRate = BigDecimal.ZERO;
        public LocalDate start = LocalDate.now();
        public LocalDate end = LocalDate.now().plusYears(1);
        public Capitalization capitalization = Capitalization.NONE;
        public List<CashFlow> cashFlows = new ArrayList<>();
        public BigDecimal taxRate = BigDecimal.ZERO;
        public BigDecimal taxFreeIncome = BigDecimal.ZERO;
        public BigDecimal minBalance = BigDecimal.ZERO;

        public long termDays() {
            return ChronoUnit.DAYS.between(start, end);
        }

        /** Бросает IllegalArgumentException с понятным сообщением, если условия некорректны. */
        public void validate() {
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Сумма вклада должна быть больше нуля");
            }
            if (annualRate.signum() < 0) {
                throw new IllegalArgumentException("Ставка не может быть отрицательной");
            }
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("Дата закрытия должна быть позже даты открытия");
            }
            if (taxRate.signum() < 0 || taxRate.compareTo(HUNDRED) > 0) {
                throw new IllegalArgumentException("Ставка налога должна быть в диапазоне 0…100 %");
            }
            if (amount.compareTo(minBalance) < 0) {
                throw new IllegalArgumentException("Сумма вклада меньше неснижаемого остатка");
            }
            for (CashFlow flow : cashFlows) {
                if (flow.date.isBefore(start) || !flow.date.isBefore(end)) {
                    throw new IllegalArgumentException(
                            "Операция " + Formats.date(flow.date) + " выходит за срок вклада");
                }
            }
        }
    }

    /** Строка графика: один месяц срока (последний может быть неполным). */
    public static final class PeriodRow {
        public LocalDate start;
        public LocalDate end;
        public BigDecimal opening;
        public BigDecimal topups;
        public BigDecimal withdrawals;
        public BigDecimal interest;
        public BigDecimal capitalized;
        public BigDecimal closing;
        public BigDecimal accruedUnpaid;
    }

    /** Итоги расчёта. */
    public static final class Result {
        public Params params;
        public List<PeriodRow> rows = new ArrayList<>();
        public BigDecimal totalTopups = BigDecimal.ZERO;
        public BigDecimal totalWithdrawals = BigDecimal.ZERO;
        public BigDecimal totalInterest = BigDecimal.ZERO;
        public BigDecimal tax = BigDecimal.ZERO;
        public BigDecimal balanceAtEnd = BigDecimal.ZERO;
        public BigDecimal effectiveRate = BigDecimal.ZERO;

        /** Всего внесено собственных денег. */
        public BigDecimal invested() {
            return money(params.amount.add(totalTopups));
        }

        /** Сумма к выдаче в конце срока после удержания налога. */
        public BigDecimal payout() {
            return money(balanceAtEnd.subtract(tax));
        }

        /** Чистый доход после налога. */
        public BigDecimal income() {
            return money(totalInterest.subtract(tax));
        }

        /** Доля процентов в итоговой сумме, %. */
        public BigDecimal interestShare() {
            if (balanceAtEnd.signum() == 0) {
                return BigDecimal.ZERO;
            }
            return totalInterest.multiply(HUNDRED)
                    .divide(balanceAtEnd, 2, RoundingMode.HALF_UP);
        }
    }

    /** Округляет сумму до копеек. */
    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static int daysInYear(LocalDate day) {
        return day.isLeapYear() ? 366 : 365;
    }

    /** Прибавляет месяцы, «прижимая» число к последнему дню месяца. */
    public static LocalDate addMonths(LocalDate start, int months) {
        return start.plusMonths(months);
    }

    /** Создаёт регулярные пополнения внутри срока вклада. */
    public static List<CashFlow> periodicCashFlows(
            LocalDate start, LocalDate end, BigDecimal amount, int stepMonths) {
        List<CashFlow> flows = new ArrayList<>();
        if (stepMonths < 1 || amount.signum() == 0) {
            return flows;
        }
        for (int index = 1; ; index++) {
            LocalDate current = start.plusMonths((long) stepMonths * index);
            if (!current.isBefore(end)) {
                break;
            }
            flows.add(new CashFlow(current, amount));
        }
        return flows;
    }

    private static List<LocalDate> capitalizationDates(Params p) {
        List<LocalDate> dates = new ArrayList<>();
        if (p.capitalization == Capitalization.NONE) {
            return dates;
        }
        if (p.capitalization == Capitalization.DAILY) {
            for (LocalDate d = p.start.plusDays(1); !d.isAfter(p.end); d = d.plusDays(1)) {
                dates.add(d);
            }
            return dates;
        }
        for (int index = 1; ; index++) {
            LocalDate current = p.start.plusMonths((long) p.capitalization.stepMonths * index);
            if (!current.isBefore(p.end)) {
                break;
            }
            dates.add(current);
        }
        dates.add(p.end);
        return dates;
    }

    /** Рассчитывает вклад: график, проценты, налог и эффективную ставку. */
    public static Result calculate(Params p) {
        p.validate();
        List<CashFlow> flows = new ArrayList<>(p.cashFlows);
        Collections.sort(flows);

        List<LocalDate> capList = capitalizationDates(p);
        java.util.Set<LocalDate> capDates = new java.util.HashSet<>(capList);

        BigDecimal dailyRateBase = p.annualRate.divide(HUNDRED, MC);
        BigDecimal balance = money(p.amount);
        BigDecimal accrued = BigDecimal.ZERO;

        Result result = new Result();
        result.params = p;

        List<LocalDate> flowDates = new ArrayList<>();
        List<BigDecimal> flowAmounts = new ArrayList<>();
        flowDates.add(p.start);
        flowAmounts.add(money(p.amount).negate());

        int flowIndex = 0;
        LocalDate periodStart = p.start;
        int monthIndex = 1;

        while (periodStart.isBefore(p.end)) {
            LocalDate periodEnd = p.start.plusMonths(monthIndex);
            if (periodEnd.isAfter(p.end)) {
                periodEnd = p.end;
            }
            if (!periodEnd.isAfter(periodStart)) {
                periodEnd = p.end;
            }

            PeriodRow row = new PeriodRow();
            row.start = periodStart;
            row.end = periodEnd;
            row.opening = money(balance);
            BigDecimal rowTopups = BigDecimal.ZERO;
            BigDecimal rowWithdrawals = BigDecimal.ZERO;
            BigDecimal rowInterest = BigDecimal.ZERO;
            BigDecimal rowCapitalized = BigDecimal.ZERO;

            for (LocalDate day = periodStart; day.isBefore(periodEnd); day = day.plusDays(1)) {
                while (flowIndex < flows.size() && flows.get(flowIndex).date.equals(day)) {
                    CashFlow flow = flows.get(flowIndex++);
                    BigDecimal updated = money(balance.add(flow.amount));
                    if (updated.compareTo(p.minBalance) < 0) {
                        throw new IllegalArgumentException("Операция " + Formats.date(day)
                                + " нарушает неснижаемый остаток "
                                + Formats.money(p.minBalance));
                    }
                    balance = updated;
                    if (flow.amount.signum() >= 0) {
                        rowTopups = rowTopups.add(flow.amount);
                        result.totalTopups = result.totalTopups.add(flow.amount);
                    } else {
                        rowWithdrawals = rowWithdrawals.subtract(flow.amount);
                        result.totalWithdrawals = result.totalWithdrawals.subtract(flow.amount);
                    }
                    flowDates.add(day);
                    flowAmounts.add(flow.amount.negate());
                }

                BigDecimal interestToday = balance.multiply(dailyRateBase, MC)
                        .divide(BigDecimal.valueOf(daysInYear(day)), MC);
                accrued = accrued.add(interestToday);
                rowInterest = rowInterest.add(interestToday);

                if (capDates.contains(day.plusDays(1))) {
                    BigDecimal credited = money(accrued);
                    balance = money(balance.add(credited));
                    result.totalInterest = result.totalInterest.add(credited);
                    rowCapitalized = rowCapitalized.add(credited);
                    accrued = BigDecimal.ZERO;
                }
            }

            row.topups = money(rowTopups);
            row.withdrawals = money(rowWithdrawals);
            row.interest = money(rowInterest);
            row.capitalized = money(rowCapitalized);
            row.closing = money(balance);
            row.accruedUnpaid = money(accrued);
            result.rows.add(row);

            periodStart = periodEnd;
            monthIndex++;
        }

        // Проценты за последний период без капитализации выплачиваются в конце срока.
        BigDecimal tail = money(accrued);
        if (tail.signum() != 0) {
            result.totalInterest = result.totalInterest.add(tail);
        }
        result.balanceAtEnd = money(balance.add(tail));
        result.totalInterest = money(result.totalInterest);
        result.totalTopups = money(result.totalTopups);
        result.totalWithdrawals = money(result.totalWithdrawals);

        BigDecimal taxable = result.totalInterest.subtract(p.taxFreeIncome);
        if (taxable.signum() < 0) {
            taxable = BigDecimal.ZERO;
        }
        result.tax = money(taxable.multiply(p.taxRate).divide(HUNDRED, MC));

        flowDates.add(p.end);
        flowAmounts.add(result.balanceAtEnd.subtract(result.tax));
        result.effectiveRate = xirr(flowDates, flowAmounts);
        return result;
    }

    /**
     * Эффективная годовая ставка по денежному потоку вкладчика (метод бисекции).
     * Знак потока: взносы отрицательны, выплаты положительны.
     */
    private static BigDecimal xirr(List<LocalDate> dates, List<BigDecimal> amounts) {
        if (dates.isEmpty()) {
            return BigDecimal.ZERO;
        }
        LocalDate base = dates.get(0);
        for (LocalDate d : dates) {
            if (d.isBefore(base)) {
                base = d;
            }
        }
        int size = dates.size();
        double[] years = new double[size];
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            years[i] = ChronoUnit.DAYS.between(base, dates.get(i)) / 365.0;
            values[i] = amounts.get(i).doubleValue();
        }

        double low = -0.9999;
        double high = 10.0;
        if (npv(low, years, values) * npv(high, years, values) > 0) {
            return BigDecimal.ZERO;
        }
        for (int i = 0; i < 200; i++) {
            double mid = (low + high) / 2;
            if (npv(low, years, values) * npv(mid, years, values) <= 0) {
                high = mid;
            } else {
                low = mid;
            }
        }
        return BigDecimal.valueOf((low + high) / 2 * 100).setScale(2, RoundingMode.HALF_UP);
    }

    private static double npv(double rate, double[] years, double[] values) {
        double sum = 0;
        for (int i = 0; i < years.length; i++) {
            sum += values[i] / Math.pow(1 + rate, years[i]);
        }
        return sum;
    }
}
