"""Командный интерфейс калькулятора вкладов."""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation

from .core import (
    Capitalization,
    CashFlow,
    DepositParams,
    DepositResult,
    add_months,
    calculate,
    money,
    periodic_cash_flows,
    to_decimal,
)

DATE_FORMATS = ("%d.%m.%Y", "%Y-%m-%d", "%d/%m/%Y")


# --------------------------------------------------------------------------- #
#  Разбор пользовательского ввода
# --------------------------------------------------------------------------- #

def parse_date(value: str) -> date:
    text = value.strip().lower()
    if text in ("сегодня", "today"):
        return date.today()
    for fmt in DATE_FORMATS:
        try:
            return datetime.strptime(text, fmt).date()
        except ValueError:
            continue
    raise argparse.ArgumentTypeError(
        f"не удалось разобрать дату {value!r} (ожидается ДД.ММ.ГГГГ или ГГГГ-ММ-ДД)"
    )


def parse_number(value: str) -> Decimal:
    text = re.sub(r"\s", "", str(value)).replace(",", ".")
    text = text.rstrip("%")
    try:
        return to_decimal(text)
    except (InvalidOperation, ArithmeticError, ValueError):
        raise argparse.ArgumentTypeError(f"не удалось разобрать число: {value!r}")


TERM_RE = re.compile(r"^(\d+)\s*([a-zа-я]*)$", re.IGNORECASE)
TERM_UNITS = {
    "": "m",
    "m": "m", "мес": "m", "м": "m", "month": "m", "months": "m", "месяц": "m",
    "месяца": "m", "месяцев": "m",
    "d": "d", "д": "d", "day": "d", "days": "d", "дн": "d", "день": "d", "дней": "d",
    "y": "y", "г": "y", "год": "y", "года": "y", "лет": "y", "year": "y", "years": "y",
}


def parse_term(value: str) -> tuple[int, str]:
    """«18м» → (18, 'm'), «540д» → (540, 'd'), «3г» → (36, 'm')."""
    match = TERM_RE.match(str(value).strip())
    if not match:
        raise argparse.ArgumentTypeError(
            f"не удалось разобрать срок {value!r} (примеры: 12, 18м, 540д, 3г)"
        )
    count, raw_unit = int(match.group(1)), match.group(2).lower()
    unit = TERM_UNITS.get(raw_unit)
    if unit is None or count <= 0:
        raise argparse.ArgumentTypeError(f"не удалось разобрать срок {value!r}")
    if unit == "y":
        return count * 12, "m"
    return count, unit


def parse_flow(value: str) -> CashFlow:
    """«01.02.2026:+50000» — пополнение, «…:-30000» — частичное снятие."""
    if ":" not in value:
        raise argparse.ArgumentTypeError(
            f"операция {value!r} должна иметь вид ДАТА:СУММА, например 01.02.2026:50000"
        )
    raw_date, raw_amount = value.rsplit(":", 1)
    amount = parse_number(raw_amount)
    if amount == 0:
        raise argparse.ArgumentTypeError("сумма операции не может быть нулевой")
    kind = "пополнение" if amount > 0 else "снятие"
    return CashFlow(parse_date(raw_date), amount, kind)


# --------------------------------------------------------------------------- #
#  Форматирование вывода
# --------------------------------------------------------------------------- #

def fmt_money(value: Decimal, currency: str = "") -> str:
    text = f"{money(value):,.2f}".replace(",", " ").replace(".", ",")
    return f"{text} {currency}".rstrip()


def fmt_rate(value: Decimal) -> str:
    return f"{value:.2f}".replace(".", ",") + " %"


def plural(n: int, one: str, few: str, many: str) -> str:
    if n % 10 == 1 and n % 100 != 11:
        return one
    if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
        return few
    return many


def render_summary(result: DepositResult, currency: str) -> str:
    p = result.params
    months = 0
    while add_months(p.start, months + 1) <= p.end:
        months += 1
    term_text = f"{p.term_days} {plural(p.term_days, 'день', 'дня', 'дней')}"
    if months:
        term_text += f" (≈ {months} {plural(months, 'месяц', 'месяца', 'месяцев')})"

    lines = [
        "УСЛОВИЯ ВКЛАДА",
        f"  Первоначальный взнос : {fmt_money(p.amount, currency)}",
        f"  Ставка               : {fmt_rate(p.annual_rate)} годовых",
        f"  Срок                 : {p.start:%d.%m.%Y} — {p.end:%d.%m.%Y}, {term_text}",
        f"  Капитализация        : {p.capitalization.title}",
    ]
    if p.min_balance:
        lines.append(f"  Неснижаемый остаток  : {fmt_money(p.min_balance, currency)}")
    if result.total_topups:
        lines.append(f"  Пополнения           : {fmt_money(result.total_topups, currency)}")
    if result.total_withdrawals:
        lines.append(f"  Снятия               : {fmt_money(result.total_withdrawals, currency)}")

    lines += [
        "",
        "РЕЗУЛЬТАТ",
        f"  Вложено собственных  : {fmt_money(result.invested, currency)}",
        f"  Начислено процентов  : {fmt_money(result.total_interest, currency)}",
        f"  Сумма в конце срока  : {fmt_money(result.payout, currency)}",
        f"  Эффективная ставка   : {fmt_rate(result.effective_rate)} годовых",
        f"  Доля процентов в итоговой сумме: {fmt_rate(result.interest_share)}",
    ]
    return "\n".join(lines)


def render_schedule(result: DepositResult, currency: str) -> str:
    show_flows = bool(result.total_topups or result.total_withdrawals)
    show_accrued = result.params.capitalization is Capitalization.NONE

    headers = ["№", "Период", "Остаток на начало"]
    if show_flows:
        headers += ["Пополнения", "Снятия"]
    headers += ["Начислено %"]
    if not show_accrued:
        headers += ["Капитализировано"]
    headers += ["Накоплено %" if show_accrued else "Остаток на конец"]

    rows: list[list[str]] = []
    for index, row in enumerate(result.rows, start=1):
        cells = [
            str(index),
            f"{row.start:%d.%m.%Y} — {row.end:%d.%m.%Y}",
            fmt_money(row.opening),
        ]
        if show_flows:
            cells += [
                fmt_money(row.topups) if row.topups else "—",
                fmt_money(row.withdrawals) if row.withdrawals else "—",
            ]
        cells += [fmt_money(row.interest)]
        if not show_accrued:
            cells += [fmt_money(row.capitalized)]
        cells += [fmt_money(row.accrued_unpaid if show_accrued else row.closing)]
        rows.append(cells)

    widths = [
        max(len(headers[i]), *(len(r[i]) for r in rows)) if rows else len(headers[i])
        for i in range(len(headers))
    ]

    def line(cells: list[str]) -> str:
        out = [cells[0].rjust(widths[0]), cells[1].ljust(widths[1])]
        out += [cells[i].rjust(widths[i]) for i in range(2, len(cells))]
        return "  ".join(out)

    separator = "─" * (sum(widths) + 2 * (len(widths) - 1))
    body = "\n".join(line(r) for r in rows)
    total = (
        f"Итого начислено процентов: {fmt_money(result.total_interest, currency)}"
        f"  →  к выдаче {fmt_money(result.payout, currency)}"
    )
    return f"ГРАФИК\n{line(headers)}\n{separator}\n{body}\n{separator}\n{total}"


def render_comparison(result: DepositResult, currency: str) -> str:
    p = result.params
    rows: list[tuple[Capitalization, Decimal, Decimal]] = []
    for option in Capitalization:
        variant = DepositParams(
            amount=p.amount,
            annual_rate=p.annual_rate,
            start=p.start,
            end=p.end,
            capitalization=option,
            cash_flows=list(p.cash_flows),
            min_balance=p.min_balance,
        )
        computed = calculate(variant)
        rows.append((option, computed.payout, computed.effective_rate))

    baseline = next(payout for option, payout, _ in rows if option is p.capitalization)
    lines = [
        "СРАВНЕНИЕ СХЕМ КАПИТАЛИЗАЦИИ",
        f"  (разница — к выбранной схеме: {p.capitalization.title})",
    ]
    width = max(len(option.title) for option, *_ in rows)
    for option, payout, eff in rows:
        delta = payout - baseline
        if option is p.capitalization:
            note = "  ← выбрано"
        elif delta > 0:
            note = f"  +{fmt_money(delta, currency)}"
        elif delta < 0:
            note = f"  −{fmt_money(-delta, currency)}"
        else:
            note = "  без разницы"
        lines.append(
            f"  {option.title.ljust(width)}  {fmt_money(payout, currency):>18}"
            f"  эфф. {fmt_rate(eff):>9}{note}"
        )
    return "\n".join(lines)


def result_to_dict(result: DepositResult, currency: str) -> dict:
    p = result.params
    return {
        "валюта": currency,
        "условия": {
            "сумма": str(p.amount),
            "ставка": str(p.annual_rate),
            "дата_открытия": p.start.isoformat(),
            "дата_закрытия": p.end.isoformat(),
            "срок_дней": p.term_days,
            "капитализация": p.capitalization.value,
            "неснижаемый_остаток": str(p.min_balance),
        },
        "итоги": {
            "вложено": str(result.invested),
            "пополнения": str(result.total_topups),
            "снятия": str(result.total_withdrawals),
            "начислено_процентов": str(result.total_interest),
            "сумма_в_конце_срока": str(result.payout),
            "эффективная_ставка": str(result.effective_rate),
        },
        "график": [
            {
                "период_с": row.start.isoformat(),
                "период_по": row.end.isoformat(),
                "остаток_на_начало": str(row.opening),
                "пополнения": str(row.topups),
                "снятия": str(row.withdrawals),
                "начислено": str(row.interest),
                "капитализировано": str(row.capitalized),
                "остаток_на_конец": str(row.closing),
                "накоплено_процентов": str(row.accrued_unpaid),
            }
            for row in result.rows
        ],
    }


# --------------------------------------------------------------------------- #
#  Интерактивный режим
# --------------------------------------------------------------------------- #

def ask(prompt: str, default: str | None = None, parser=None):
    suffix = f" [{default}]" if default is not None else ""
    while True:
        raw = input(f"{prompt}{suffix}: ").strip()
        if not raw:
            if default is None:
                print("  Нужно ввести значение.")
                continue
            raw = default
        if parser is None:
            return raw
        try:
            return parser(raw)
        except (argparse.ArgumentTypeError, ValueError) as exc:
            print(f"  {exc}")


def interactive(args: argparse.Namespace) -> argparse.Namespace:
    print("Калькулятор вкладов — интерактивный режим (Enter принимает значение по умолчанию)\n")
    args.amount = ask("Сумма вклада, сўм", "10000000", parse_number)
    args.rate = ask("Ставка, % годовых", "24", parse_number)
    args.start = ask("Дата открытия", date.today().strftime("%d.%m.%Y"), parse_date)
    term, unit = ask("Срок (например 12, 18м, 540д, 3г)", "12", parse_term)
    args.months, args.days = (term, None) if unit == "m" else (None, term)
    args.end = None
    args.cap = ask(
        "Капитализация (нет / ежедневно / ежемесячно / ежеквартально / полгода / ежегодно)",
        "ежемесячно",
        Capitalization.parse,
    )
    topup = ask("Ежемесячное пополнение, сўм (0 — без пополнений)", "0", parse_number)
    args.topup = topup if topup else None
    args.topup_period = 1
    args.schedule = ask("Показать график? (д/н)", "д").lower().startswith(("д", "y"))
    args.compare = ask("Сравнить схемы капитализации? (д/н)", "н").lower().startswith(("д", "y"))
    print()
    return args


# --------------------------------------------------------------------------- #
#  Сборка параметров и точка входа
# --------------------------------------------------------------------------- #

def build_params(args: argparse.Namespace) -> DepositParams:
    start = args.start or date.today()
    if args.end:
        end = args.end
    elif args.days:
        end = start + timedelta(days=args.days)
    else:
        end = add_months(start, args.months or 12)

    flows: list[CashFlow] = list(args.flow or [])
    if args.topup:
        flows += periodic_cash_flows(
            start,
            end,
            args.topup,
            step_months=args.topup_period,
            day=args.topup_day,
            comment="регулярное пополнение",
        )
    flows.sort(key=lambda f: f.date)

    return DepositParams(
        amount=args.amount,
        annual_rate=args.rate,
        start=start,
        end=end,
        capitalization=args.cap,
        cash_flows=flows,
        min_balance=args.min_balance or 0,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="deposit-calc",
        description="Калькулятор банковских вкладов: проценты, капитализация, "
                    "пополнения, снятия и эффективная ставка.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""примеры:
  deposit-calc -a 10000000 -r 24 --term 12 --cap ежемесячно --schedule
  deposit-calc -a 5000000 -r 22 --term 540д --topup 500000 --compare
  deposit-calc -a 50000000 -r 26 --term 3г --cap ежеквартально
  deposit-calc -a 20000000 -r 23 --term 12 --flow 01.03.2026:5000000 --flow 01.06.2026:-2000000
  deposit-calc -i          # диалоговый режим
""",
    )
    parser.add_argument("-a", "--amount", type=parse_number, help="сумма вклада")
    parser.add_argument("-r", "--rate", type=parse_number, help="ставка, %% годовых")
    parser.add_argument("--start", type=parse_date, help="дата открытия (по умолчанию сегодня)")

    term = parser.add_mutually_exclusive_group()
    term.add_argument("--term", help="срок: 12, 18м, 540д, 3г")
    term.add_argument("--end", type=parse_date, help="дата закрытия вклада")

    parser.add_argument(
        "-c", "--cap", "--capitalization", dest="cap", default="none",
        type=Capitalization.parse,
        help="капитализация: нет | ежедневно | ежемесячно | ежеквартально | полгода | ежегодно",
    )
    parser.add_argument("--topup", type=parse_number, help="сумма регулярного пополнения")
    parser.add_argument("--topup-period", type=int, default=1,
                        help="периодичность пополнений в месяцах (по умолчанию 1)")
    parser.add_argument("--topup-day", type=int, help="число месяца для пополнений")
    parser.add_argument("--flow", action="append", type=parse_flow, metavar="ДАТА:СУММА",
                        help="разовая операция: «+» — пополнение, «−» — снятие (можно повторять)")
    parser.add_argument("--min-balance", type=parse_number, help="неснижаемый остаток")
    parser.add_argument("--currency", default="сўм",
                        help="обозначение валюты (по умолчанию сўм)")
    parser.add_argument("-s", "--schedule", action="store_true", help="показать график по месяцам")
    parser.add_argument("--compare", action="store_true",
                        help="сравнить все схемы капитализации")
    parser.add_argument("--json", action="store_true", help="вывести результат в JSON")
    parser.add_argument("-i", "--interactive", action="store_true", help="диалоговый режим")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    args.months, args.days = None, None
    if args.term:
        count, unit = parse_term(args.term)
        args.months, args.days = (count, None) if unit == "m" else (None, count)

    needs_input = args.amount is None or args.rate is None
    if args.interactive or (needs_input and sys.stdin.isatty()):
        args = interactive(args)
    elif needs_input:
        parser.error("укажите --amount и --rate (или запустите с -i)")

    try:
        params = build_params(args)
        result = calculate(params)
    except ValueError as exc:
        print(f"Ошибка: {exc}", file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(result_to_dict(result, args.currency), ensure_ascii=False, indent=2))
        return 0

    print(render_summary(result, args.currency))
    if args.schedule:
        print()
        print(render_schedule(result, args.currency))
    if args.compare:
        print()
        print(render_comparison(result, args.currency))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
