"""Калькулятор банковских вкладов.

Библиотека и консольная программа для расчёта доходности вклада:
простые проценты и капитализация, пополнения и частичные снятия,
налог на процентный доход и эффективная годовая ставка.
"""

from .core import (
    Capitalization,
    CashFlow,
    DepositParams,
    DepositResult,
    PeriodRow,
    add_months,
    calculate,
    money,
    periodic_cash_flows,
)

__version__ = "1.0.0"

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
    "__version__",
]
