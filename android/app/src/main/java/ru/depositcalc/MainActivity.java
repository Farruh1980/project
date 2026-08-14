package ru.depositcalc;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import ru.depositcalc.DepositCalculator.Capitalization;
import ru.depositcalc.DepositCalculator.CashFlow;
import ru.depositcalc.DepositCalculator.Params;
import ru.depositcalc.DepositCalculator.PeriodRow;
import ru.depositcalc.DepositCalculator.Result;

/** Единственный экран: условия вклада сверху, результат и график снизу. */
public class MainActivity extends Activity {

    private static final Capitalization[] CAPS = Capitalization.values();
    private static final String[] TERM_UNITS = {"месяцев", "дней", "лет"};

    private EditText amountField;
    private EditText rateField;
    private EditText termField;
    private Spinner termUnitSpinner;
    private Button startDateButton;
    private Spinner capSpinner;
    private EditText topupField;

    private View resultCard;
    private TextView payoutView;
    private TextView summaryView;
    private TextView errorView;
    private TableLayout scheduleTable;
    private View scheduleCard;
    private TextView compareView;
    private View compareCard;

    private LocalDate startDate = LocalDate.now();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        amountField = findViewById(R.id.amount);
        rateField = findViewById(R.id.rate);
        termField = findViewById(R.id.term);
        termUnitSpinner = findViewById(R.id.term_unit);
        startDateButton = findViewById(R.id.start_date);
        capSpinner = findViewById(R.id.capitalization);
        topupField = findViewById(R.id.topup);

        resultCard = findViewById(R.id.result_card);
        payoutView = findViewById(R.id.payout);
        summaryView = findViewById(R.id.summary);
        errorView = findViewById(R.id.error);
        scheduleTable = findViewById(R.id.schedule);
        scheduleCard = findViewById(R.id.schedule_card);
        compareView = findViewById(R.id.compare);
        compareCard = findViewById(R.id.compare_card);

        termUnitSpinner.setAdapter(adapter(TERM_UNITS));
        List<String> capTitles = new ArrayList<>();
        for (Capitalization cap : CAPS) {
            capTitles.add(cap.title);
        }
        capSpinner.setAdapter(adapter(capTitles.toArray(new String[0])));
        capSpinner.setSelection(indexOf(Capitalization.MONTHLY));

        updateStartDateButton();
        startDateButton.setOnClickListener(v -> pickStartDate());

        findViewById(R.id.calculate).setOnClickListener(v -> calculate());

        // Пересчитываем сразу, как только пользователь меняет выпадающие списки.
        AdapterView.OnItemSelectedListener recalc = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (resultCard.getVisibility() == View.VISIBLE) {
                    calculate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        capSpinner.setOnItemSelectedListener(recalc);
        termUnitSpinner.setOnItemSelectedListener(recalc);
    }

    private ArrayAdapter<String> adapter(String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private int indexOf(Capitalization capitalization) {
        for (int i = 0; i < CAPS.length; i++) {
            if (CAPS[i] == capitalization) {
                return i;
            }
        }
        return 0;
    }

    private void pickStartDate() {
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    startDate = LocalDate.of(year, month + 1, dayOfMonth);
                    updateStartDateButton();
                    if (resultCard.getVisibility() == View.VISIBLE) {
                        calculate();
                    }
                },
                startDate.getYear(),
                startDate.getMonthValue() - 1,
                startDate.getDayOfMonth());
        dialog.show();
    }

    private void updateStartDateButton() {
        startDateButton.setText(Formats.date(startDate));
    }

    private void calculate() {
        try {
            Params params = readParams();
            Result result = DepositCalculator.calculate(params);
            showResult(result);
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
        }
    }

    private Params readParams() {
        Params params = new Params();
        params.amount = required(amountField, "Введите сумму вклада");
        params.annualRate = required(rateField, "Введите ставку");
        params.start = startDate;

        BigDecimal termValue = required(termField, "Введите срок");
        int term = termValue.intValue();
        if (term <= 0) {
            throw new IllegalArgumentException("Срок должен быть больше нуля");
        }
        switch (termUnitSpinner.getSelectedItemPosition()) {
            case 1:
                params.end = startDate.plusDays(term);
                break;
            case 2:
                params.end = startDate.plusYears(term);
                break;
            default:
                params.end = startDate.plusMonths(term);
                break;
        }

        params.capitalization = CAPS[capSpinner.getSelectedItemPosition()];

        BigDecimal topup = Formats.parseNumber(topupField.getText().toString(), BigDecimal.ZERO);
        if (topup.signum() > 0) {
            params.cashFlows = DepositCalculator.periodicCashFlows(
                    params.start, params.end, topup, 1);
        }

        return params;
    }

    private BigDecimal required(EditText field, String message) {
        String text = field.getText().toString();
        if (TextUtils.isEmpty(text.trim())) {
            throw new IllegalArgumentException(message);
        }
        return Formats.parseNumber(text, null);
    }

    private void showError(String message) {
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
        resultCard.setVisibility(View.GONE);
        scheduleCard.setVisibility(View.GONE);
        compareCard.setVisibility(View.GONE);
    }

    private void showResult(Result result) {
        errorView.setVisibility(View.GONE);
        resultCard.setVisibility(View.VISIBLE);
        scheduleCard.setVisibility(View.VISIBLE);
        compareCard.setVisibility(View.VISIBLE);

        Params p = result.params;
        payoutView.setText(Formats.sum(result.payout()));

        long days = p.termDays();
        StringBuilder text = new StringBuilder();
        text.append("Срок: ").append(Formats.date(p.start)).append(" — ")
                .append(Formats.date(p.end)).append(", ").append(days).append(' ')
                .append(Formats.plural(days, "день", "дня", "дней")).append('\n');
        text.append("Вложено собственных: ").append(Formats.sum(result.invested())).append('\n');
        if (result.totalTopups.signum() > 0) {
            text.append("В том числе пополнений: ")
                    .append(Formats.sum(result.totalTopups)).append('\n');
        }
        text.append("Начислено процентов: ")
                .append(Formats.sum(result.totalInterest)).append('\n');
        text.append("Эффективная ставка: ")
                .append(Formats.percent(result.effectiveRate)).append(" годовых");
        summaryView.setText(text.toString());

        fillSchedule(result);
        fillComparison(result);
    }

    private void fillSchedule(Result result) {
        scheduleTable.removeAllViews();
        boolean compounding = result.params.capitalization != Capitalization.NONE;
        boolean hasFlows = result.totalTopups.signum() > 0;

        List<String> headers = new ArrayList<>();
        headers.add("Месяц");
        headers.add("На начало");
        if (hasFlows) {
            headers.add("Пополнено");
        }
        headers.add("Проценты");
        headers.add(compounding ? "На конец" : "Накоплено %");
        scheduleTable.addView(buildRow(headers, true));

        int index = 1;
        for (PeriodRow row : result.rows) {
            List<String> cells = new ArrayList<>();
            cells.add(index++ + ". " + Formats.date(row.start));
            cells.add(Formats.money(row.opening));
            if (hasFlows) {
                cells.add(row.topups.signum() > 0 ? Formats.money(row.topups) : "—");
            }
            cells.add(Formats.money(row.interest));
            cells.add(Formats.money(compounding ? row.closing : row.accruedUnpaid));
            scheduleTable.addView(buildRow(cells, false));
        }

        List<String> total = new ArrayList<>();
        total.add("Итого");
        total.add("");
        if (hasFlows) {
            total.add(Formats.money(result.totalTopups));
        }
        total.add(Formats.money(result.totalInterest));
        total.add(Formats.money(result.balanceAtEnd));
        scheduleTable.addView(buildRow(total, true));
    }

    private TableRow buildRow(List<String> cells, boolean bold) {
        TableRow row = new TableRow(this);
        for (int i = 0; i < cells.size(); i++) {
            TextView view = new TextView(this);
            view.setText(cells.get(i));
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            view.setPadding(dp(6), dp(5), dp(6), dp(5));
            view.setGravity(i == 0 ? Gravity.START : Gravity.END);
            view.setTextColor(bold ? Color.parseColor("#1B4332") : Color.parseColor("#333333"));
            if (bold) {
                view.setTypeface(Typeface.DEFAULT_BOLD);
            }
            row.addView(view);
        }
        return row;
    }

    private void fillComparison(Result result) {
        Params p = result.params;
        StringBuilder text = new StringBuilder();
        BigDecimal baseline = result.payout();
        for (Capitalization option : CAPS) {
            Params variant = new Params();
            variant.amount = p.amount;
            variant.annualRate = p.annualRate;
            variant.start = p.start;
            variant.end = p.end;
            variant.capitalization = option;
            variant.cashFlows = new ArrayList<>(p.cashFlows);
            variant.minBalance = p.minBalance;

            BigDecimal payout;
            try {
                payout = DepositCalculator.calculate(variant).payout();
            } catch (IllegalArgumentException e) {
                continue;
            }
            BigDecimal delta = payout.subtract(baseline);

            text.append(option.title).append(": ").append(Formats.sum(payout));
            if (option == p.capitalization) {
                text.append("  ← выбрано");
            } else if (delta.signum() > 0) {
                text.append("  (+").append(Formats.money(delta)).append(')');
            } else if (delta.signum() < 0) {
                text.append("  (").append(Formats.money(delta)).append(')');
            }
            text.append('\n');
        }
        compareView.setText(text.toString().trim());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
