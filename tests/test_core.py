"""Тесты расчётного ядра."""

from datetime import date
from decimal import Decimal

import pytest

from deposit_calc import (
    Capitalization,
    CashFlow,
    DepositParams,
    add_months,
    calculate,
    periodic_cash_flows,
)


def params(**kwargs) -> DepositParams:
    base = dict(
        amount=Decimal("100000"),
        annual_rate=Decimal("10"),
        start=date(2023, 1, 1),
        end=date(2024, 1, 1),
        capitalization=Capitalization.NONE,
    )
    base.update(kwargs)
    return DepositParams(**base)


# --------------------------------------------------------------------------- #
#  Базовые проценты
# --------------------------------------------------------------------------- #

def test_простые_проценты_за_год():
    """365 дней невисокосного года по 10 % — ровно 10 000."""
    result = calculate(params())
    assert result.total_interest == Decimal("10000.00")
    assert result.payout == Decimal("110000.00")


def test_високосный_год_делится_на_366():
    result = calculate(params(start=date(2024, 1, 1), end=date(2025, 1, 1)))
    assert result.params.term_days == 366
    assert result.total_interest == Decimal("10000.00")


def test_период_через_границу_високосного_года():
    """Дни 2023 года делятся на 365, дни 2024-го — на 366."""
    result = calculate(params(start=date(2023, 12, 1), end=date(2024, 3, 1)))
    expected = Decimal("100000") * Decimal("0.1") * (
        Decimal(31) / Decimal(365) + Decimal(60) / Decimal(366)
    )
    assert abs(result.total_interest - expected) < Decimal("0.01")


def test_доход_равен_начисленным_процентам():
    result = calculate(params())
    assert result.income == result.total_interest
    assert result.payout == result.balance_at_end


def test_нулевая_ставка_не_даёт_дохода():
    result = calculate(params(annual_rate=0))
    assert result.total_interest == Decimal("0.00")
    assert result.payout == Decimal("100000.00")


# --------------------------------------------------------------------------- #
#  Капитализация
# --------------------------------------------------------------------------- #

def test_капитализация_выгоднее_простых_процентов():
    простые = calculate(params()).payout
    ежемесячно = calculate(params(capitalization=Capitalization.MONTHLY)).payout
    ежедневно = calculate(params(capitalization=Capitalization.DAILY)).payout
    assert простые < ежемесячно < ежедневно


def test_ежемесячная_капитализация_совпадает_с_формулой():
    """Ставка постоянна, поэтому итог близок к 100000*(1+r/12)^12."""
    result = calculate(params(capitalization=Capitalization.MONTHLY))
    ожидание = Decimal("100000") * (1 + Decimal("0.1") / 12) ** 12
    assert abs(result.payout - ожидание) < Decimal("15")


def test_ежедневная_капитализация_близка_к_непрерывной():
    """При ежедневном присоединении итог стремится к 100000 * e^0.1."""
    result = calculate(params(capitalization=Capitalization.DAILY))
    assert Decimal("110510") < result.payout < Decimal("110525")


def test_годовая_капитализация_равна_простым_процентам_за_год():
    """За один год единственная капитализация приходится на конец срока."""
    годовая = calculate(params(capitalization=Capitalization.ANNUAL))
    простые = calculate(params())
    assert годовая.payout == простые.payout


def test_квартальная_капитализация_между_годовой_и_месячной():
    квартал = calculate(params(capitalization=Capitalization.QUARTERLY)).payout
    месяц = calculate(params(capitalization=Capitalization.MONTHLY)).payout
    год = calculate(params(capitalization=Capitalization.ANNUAL)).payout
    assert год < квартал < месяц


# --------------------------------------------------------------------------- #
#  Пополнения и снятия
# --------------------------------------------------------------------------- #

def test_пополнение_увеличивает_остаток_и_проценты():
    без = calculate(params())
    с_пополнением = calculate(params(cash_flows=[CashFlow(date(2023, 7, 1), Decimal("50000"))]))
    assert с_пополнением.total_topups == Decimal("50000.00")
    assert с_пополнением.invested == Decimal("150000.00")
    assert с_пополнением.total_interest > без.total_interest


def test_пополнение_в_последний_день_почти_не_даёт_процентов():
    result = calculate(params(cash_flows=[CashFlow(date(2023, 12, 31), Decimal("100000"))]))
    прирост = result.total_interest - Decimal("10000")
    assert Decimal("0") < прирост < Decimal("30")


def test_частичное_снятие_уменьшает_проценты():
    без = calculate(params())
    со_снятием = calculate(params(cash_flows=[CashFlow(date(2023, 7, 1), Decimal("-40000"))]))
    assert со_снятием.total_withdrawals == Decimal("40000.00")
    assert со_снятием.total_interest < без.total_interest


def test_снятие_ниже_неснижаемого_остатка_запрещено():
    with pytest.raises(ValueError, match="неснижаемый остаток"):
        calculate(params(
            min_balance=Decimal("50000"),
            cash_flows=[CashFlow(date(2023, 7, 1), Decimal("-60000"))],
        ))


def test_регулярные_пополнения_создаются_внутри_срока():
    flows = periodic_cash_flows(date(2023, 1, 1), date(2024, 1, 1), Decimal("10000"))
    assert len(flows) == 11  # с 01.02 по 01.12, дата закрытия не включается
    assert flows[0].date == date(2023, 2, 1)
    assert flows[-1].date == date(2023, 12, 1)
    assert all(f.amount == Decimal("10000.00") for f in flows)


def test_регулярные_пополнения_с_шагом_в_квартал():
    flows = periodic_cash_flows(date(2023, 1, 1), date(2024, 1, 1), 5000, step_months=3)
    assert [f.date for f in flows] == [date(2023, 4, 1), date(2023, 7, 1), date(2023, 10, 1)]


def test_операция_вне_срока_вклада_отклоняется():
    with pytest.raises(ValueError, match="выходит за срок"):
        params(cash_flows=[CashFlow(date(2025, 1, 1), Decimal("1000"))])


# --------------------------------------------------------------------------- #
#  Эффективная ставка и график
# --------------------------------------------------------------------------- #

def test_эффективная_ставка_без_капитализации_равна_номинальной():
    result = calculate(params())
    assert abs(result.effective_rate - Decimal("10")) < Decimal("0.05")


def test_эффективная_ставка_выше_номинальной_при_капитализации():
    result = calculate(params(capitalization=Capitalization.MONTHLY))
    assert result.effective_rate > Decimal("10.4")


def test_график_покрывает_весь_срок_без_разрывов():
    result = calculate(params(capitalization=Capitalization.MONTHLY))
    assert len(result.rows) == 12
    assert result.rows[0].start == date(2023, 1, 1)
    assert result.rows[-1].end == date(2024, 1, 1)
    for предыдущая, следующая in zip(result.rows, result.rows[1:]):
        assert предыдущая.end == следующая.start
        assert предыдущая.closing == следующая.opening


def test_сумма_строк_графика_равна_итогу():
    result = calculate(params(
        capitalization=Capitalization.MONTHLY,
        cash_flows=periodic_cash_flows(date(2023, 1, 1), date(2024, 1, 1), 10000),
    ))
    assert sum(r.capitalized for r in result.rows) == result.total_interest
    assert sum(r.topups for r in result.rows) == result.total_topups
    assert result.rows[-1].closing == result.balance_at_end


def test_неполный_последний_месяц_попадает_в_график():
    result = calculate(params(end=date(2023, 3, 15)))
    assert [(r.start, r.end) for r in result.rows] == [
        (date(2023, 1, 1), date(2023, 2, 1)),
        (date(2023, 2, 1), date(2023, 3, 1)),
        (date(2023, 3, 1), date(2023, 3, 15)),
    ]


# --------------------------------------------------------------------------- #
#  Вспомогательные функции и валидация
# --------------------------------------------------------------------------- #

def test_прибавление_месяцев_прижимает_к_концу_месяца():
    assert add_months(date(2023, 1, 31), 1) == date(2023, 2, 28)
    assert add_months(date(2024, 1, 31), 1) == date(2024, 2, 29)
    assert add_months(date(2023, 12, 15), 3) == date(2024, 3, 15)
    assert add_months(date(2023, 5, 10), 0) == date(2023, 5, 10)


def test_разбор_названий_капитализации():
    assert Capitalization.parse("ежемесячно") is Capitalization.MONTHLY
    assert Capitalization.parse("Quarterly") is Capitalization.QUARTERLY
    assert Capitalization.parse("нет") is Capitalization.NONE
    with pytest.raises(ValueError):
        Capitalization.parse("иногда")


@pytest.mark.parametrize(
    "kwargs, сообщение",
    [
        (dict(amount=0), "больше нуля"),
        (dict(annual_rate=-1), "отрицательной"),
        (dict(end=date(2022, 1, 1)), "позже даты открытия"),
        (dict(min_balance=Decimal("200000")), "меньше неснижаемого остатка"),
    ],
)
def test_некорректные_условия_отклоняются(kwargs, сообщение):
    with pytest.raises(ValueError, match=сообщение):
        params(**kwargs)
