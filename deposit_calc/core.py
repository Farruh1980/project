"""Расчётное ядро калькулятора вкладов.

Проценты начисляются ежедневно на текущий остаток по формуле

    процент за день = остаток * ставка / 100 / дней_в_году

где `дней_в_году` — 365 или 366 в зависимости от того, високосный ли год,
которому принадлежит день начисления (обычная практика российских банков).

Начисление идёт за каждый день периода [дата открытия, дата закрытия),
то есть ровно `срок в днях` дней. В даты капитализации накопленные проценты
округляются до копеек и присоединяются к остатку вклада.
"""

from __future__ import annotations

import calendar
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
from enum import Enum

__all__ = [
    "Capitalization",
    "CashFlow",
    "DepositParams",
    "DepositResult",
    "PeriodRow",
    "add_months",
    "calculate",
    "money",
    "periodic_cash_flows",
    "to_decimal",
]

CENT = Decimal("0.01")
DAY = timedelta(days=1)


def to_decimal(value) -> Decimal:
    """Аккуратно приводит число/строку к Decimal (float — через str)."""
    if isinstance(value, Decimal):
        return value
    if isinstance(value, float):
        return Decimal(str(value))
    return Decimal(value)


def money(value) -> Decimal:
    """Округляет сумму до копеек."""
    return to_decimal(value).quantize(CENT, rounding=ROUND_HALF_UP)


def days_in_year(day: date) -> int:
    return 366 if calendar.isleap(day.year) else 365


def add_months(start: date, months: int) -> date:
    """Прибавляет месяцы, «прижимая» число к последнему дню месяца.

    31 января + 1 месяц = 28 (или 29) февраля.
    """
    total = start.month - 1 + months
    year = start.year + total // 12
    month = total % 12 + 1
    day = min(start.day, calendar.monthrange(year, month)[1])
    return date(year, month, day)


class Capitalization(str, Enum):
    """Периодичность капитализации (присоединения процентов к вкладу)."""

    NONE = "none"
    DAILY = "daily"
    MONTHLY = "monthly"
    QUARTERLY = "quarterly"
    SEMIANNUAL = "semiannual"
    ANNUAL = "annual"

    @property
    def months(self) -> int | None:
        """Шаг капитализации в месяцах (None — для «нет» и «ежедневно»)."""
        return {
            Capitalization.MONTHLY: 1,
            Capitalization.QUARTERLY: 3,
            Capitalization.SEMIANNUAL: 6,
            Capitalization.ANNUAL: 12,
        }.get(self)

    @property
    def title(self) -> str:
        return {
            Capitalization.NONE: "без капитализации (проценты в конце срока)",
            Capitalization.DAILY: "ежедневная",
            Capitalization.MONTHLY: "ежемесячная",
            Capitalization.QUARTERLY: "ежеквартальная",
            Capitalization.SEMIANNUAL: "раз в полгода",
            Capitalization.ANNUAL: "ежегодная",
        }[self]

    @classmethod
    def parse(cls, value: "str | Capitalization") -> "Capitalization":
        if isinstance(value, cls):
            return value
        key = str(value).strip().lower()
        aliases = {
            "none": cls.NONE,
            "нет": cls.NONE,
            "без": cls.NONE,
            "end": cls.NONE,
            "в конце": cls.NONE,
            "daily": cls.DAILY,
            "день": cls.DAILY,
            "ежедневно": cls.DAILY,
            "ежедневная": cls.DAILY,
            "monthly": cls.MONTHLY,
            "месяц": cls.MONTHLY,
            "ежемесячно": cls.MONTHLY,
            "ежемесячная": cls.MONTHLY,
            "quarterly": cls.QUARTERLY,
            "квартал": cls.QUARTERLY,
            "ежеквартально": cls.QUARTERLY,
            "ежеквартальная": cls.QUARTERLY,
            "semiannual": cls.SEMIANNUAL,
            "полгода": cls.SEMIANNUAL,
            "полугодовая": cls.SEMIANNUAL,
            "annual": cls.ANNUAL,
            "yearly": cls.ANNUAL,
            "год": cls.ANNUAL,
            "ежегодно": cls.ANNUAL,
            "ежегодная": cls.ANNUAL,
        }
        if key not in aliases:
            raise ValueError(f"неизвестная периодичность капитализации: {value!r}")
        return aliases[key]


@dataclass(frozen=True)
class CashFlow:
    """Движение по вкладу: пополнение (+) или частичное снятие (−)."""

    date: date
    amount: Decimal
    comment: str = ""

    def __post_init__(self) -> None:
        object.__setattr__(self, "amount", money(self.amount))


def periodic_cash_flows(
    start: date,
    end: date,
    amount,
    step_months: int = 1,
    day: int | None = None,
    comment: str = "",
) -> list[CashFlow]:
    """Создаёт регулярные пополнения/снятия внутри срока вклада.

    Первое движение — через `step_months` месяцев после открытия, последнее —
    строго до даты закрытия (пополнение в день закрытия смысла не имеет).
    """
    if step_months < 1:
        raise ValueError("шаг регулярных операций должен быть не меньше 1 месяца")
    anchor = start if day is None else date(start.year, start.month, min(day, calendar.monthrange(start.year, start.month)[1]))
    flows: list[CashFlow] = []
    index = 1
    while True:
        current = add_months(anchor, step_months * index)
        if current >= end:
            break
        if current > start:
            flows.append(CashFlow(current, amount, comment))
        index += 1
    return flows


@dataclass
class DepositParams:
    """Условия вклада."""

    amount: Decimal
    annual_rate: Decimal
    start: date
    end: date
    capitalization: Capitalization = Capitalization.NONE
    cash_flows: list[CashFlow] = field(default_factory=list)
    min_balance: Decimal = Decimal("0")

    def __post_init__(self) -> None:
        self.amount = money(self.amount)
        self.annual_rate = to_decimal(self.annual_rate)
        self.capitalization = Capitalization.parse(self.capitalization)
        self.min_balance = money(self.min_balance)

        if self.amount <= 0:
            raise ValueError("сумма вклада должна быть больше нуля")
        if self.annual_rate < 0:
            raise ValueError("ставка не может быть отрицательной")
        if self.end <= self.start:
            raise ValueError("дата закрытия должна быть позже даты открытия")
        if self.amount < self.min_balance:
            raise ValueError("сумма вклада меньше неснижаемого остатка")
        for flow in self.cash_flows:
            if not self.start <= flow.date < self.end:
                raise ValueError(
                    f"операция {flow.date:%d.%m.%Y} выходит за срок вклада "
                    f"({self.start:%d.%m.%Y} … {self.end:%d.%m.%Y})"
                )

    @property
    def term_days(self) -> int:
        return (self.end - self.start).days


@dataclass
class PeriodRow:
    """Строка графика: один месяц (или его остаток) срока вклада."""

    start: date
    end: date
    opening: Decimal
    topups: Decimal
    withdrawals: Decimal
    interest: Decimal
    capitalized: Decimal
    closing: Decimal
    accrued_unpaid: Decimal


@dataclass
class DepositResult:
    """Итоги расчёта."""

    params: DepositParams
    rows: list[PeriodRow]
    total_topups: Decimal
    total_withdrawals: Decimal
    total_interest: Decimal
    balance_at_end: Decimal
    effective_rate: Decimal

    @property
    def invested(self) -> Decimal:
        """Сколько всего внесено собственных денег."""
        return self.params.amount + self.total_topups

    @property
    def payout(self) -> Decimal:
        """Сумма к выдаче в конце срока."""
        return self.balance_at_end

    @property
    def income(self) -> Decimal:
        """Доход по вкладу — начисленные проценты."""
        return self.total_interest

    @property
    def interest_share(self) -> Decimal:
        """Доля процентов в итоговой сумме, %."""
        if self.balance_at_end == 0:
            return Decimal("0")
        return (self.total_interest / self.balance_at_end * 100).quantize(CENT, ROUND_HALF_UP)


def _capitalization_dates(params: DepositParams) -> set[date]:
    """Даты, в которые накопленные проценты присоединяются к вкладу.

    Дата закрытия входит в множество для всех схем с капитализацией: в этот
    момент банк доначисляет проценты за последний неполный период.
    """
    cap = params.capitalization
    if cap is Capitalization.NONE:
        return set()
    if cap is Capitalization.DAILY:
        dates = set()
        current = params.start + DAY
        while current <= params.end:
            dates.add(current)
            current += DAY
        return dates

    step = cap.months
    assert step is not None
    dates = set()
    index = 1
    while True:
        current = add_months(params.start, step * index)
        if current >= params.end:
            break
        dates.add(current)
        index += 1
    dates.add(params.end)
    return dates


def _report_periods(params: DepositParams) -> list[tuple[date, date]]:
    """Границы строк графика — помесячно от даты открытия."""
    periods: list[tuple[date, date]] = []
    current = params.start
    index = 1
    while current < params.end:
        nxt = min(add_months(params.start, index), params.end)
        if nxt <= current:  # страховка от вырожденных случаев
            nxt = params.end
        periods.append((current, nxt))
        current = nxt
        index += 1
    return periods


def _xirr(flows: list[tuple[date, Decimal]]) -> Decimal:
    """Эффективная годовая ставка по денежному потоку (метод бисекции).

    Знак потока — с точки зрения вкладчика: взносы отрицательны, выплаты
    положительны. Дисконтирование — по фактическому числу дней / 365.
    """
    if not flows:
        return Decimal("0")
    base = min(day for day, _ in flows)
    points = [((day - base).days / 365.0, float(amount)) for day, amount in flows]

    def npv(rate: float) -> float:
        return sum(amount / (1.0 + rate) ** years for years, amount in points)

    low, high = -0.9999, 10.0
    if npv(low) * npv(high) > 0:
        return Decimal("0")
    for _ in range(200):
        mid = (low + high) / 2
        if npv(low) * npv(mid) <= 0:
            high = mid
        else:
            low = mid
    return (Decimal(str((low + high) / 2)) * 100).quantize(CENT, ROUND_HALF_UP)


def calculate(params: DepositParams) -> DepositResult:
    """Рассчитывает вклад: график, проценты и эффективную ставку."""
    flows_by_date: dict[date, list[CashFlow]] = defaultdict(list)
    for flow in params.cash_flows:
        flows_by_date[flow.date].append(flow)

    cap_dates = _capitalization_dates(params)
    daily_rate = params.annual_rate / Decimal(100)

    balance = params.amount
    accrued = Decimal(0)  # начисленные, но ещё не присоединённые проценты
    total_interest = Decimal(0)
    total_topups = Decimal(0)
    total_withdrawals = Decimal(0)

    investor_flows: list[tuple[date, Decimal]] = [(params.start, -params.amount)]
    rows: list[PeriodRow] = []

    for period_start, period_end in _report_periods(params):
        opening = balance
        row_topups = Decimal(0)
        row_withdrawals = Decimal(0)
        row_interest = Decimal(0)
        row_capitalized = Decimal(0)

        day = period_start
        while day < period_end:
            for flow in flows_by_date.get(day, ()):
                new_balance = money(balance + flow.amount)
                if new_balance < params.min_balance:
                    raise ValueError(
                        f"операция {day:%d.%m.%Y} на {flow.amount} нарушает "
                        f"неснижаемый остаток {params.min_balance}"
                    )
                balance = new_balance
                if flow.amount >= 0:
                    row_topups += flow.amount
                    total_topups += flow.amount
                else:
                    row_withdrawals += -flow.amount
                    total_withdrawals += -flow.amount
                investor_flows.append((day, -flow.amount))

            interest_today = balance * daily_rate / Decimal(days_in_year(day))
            accrued += interest_today
            row_interest += interest_today

            if day + DAY in cap_dates:
                credited = money(accrued)
                balance = money(balance + credited)
                total_interest += credited
                row_capitalized += credited
                accrued = Decimal(0)

            day += DAY

        rows.append(
            PeriodRow(
                start=period_start,
                end=period_end,
                opening=money(opening),
                topups=money(row_topups),
                withdrawals=money(row_withdrawals),
                interest=money(row_interest),
                capitalized=money(row_capitalized),
                closing=money(balance),
                accrued_unpaid=money(accrued),
            )
        )

    # Проценты за последний период без капитализации выплачиваются в конце срока.
    tail = money(accrued)
    if tail:
        total_interest += tail
    balance_at_end = money(balance + tail)

    total_interest = money(total_interest)
    investor_flows.append((params.end, balance_at_end))

    return DepositResult(
        params=params,
        rows=rows,
        total_topups=money(total_topups),
        total_withdrawals=money(total_withdrawals),
        total_interest=total_interest,
        balance_at_end=balance_at_end,
        effective_rate=_xirr(investor_flows),
    )
