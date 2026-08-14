"""Тесты консольного интерфейса."""

import json
from datetime import date
from decimal import Decimal

import pytest

from deposit_calc.cli import (
    fmt_money,
    main,
    parse_date,
    parse_flow,
    parse_number,
    parse_term,
)


def test_разбор_дат():
    assert parse_date("01.02.2026") == date(2026, 2, 1)
    assert parse_date("2026-02-01") == date(2026, 2, 1)
    assert parse_date("сегодня") == date.today()
    with pytest.raises(Exception):
        parse_date("вчера")


def test_разбор_чисел_с_запятой_и_пробелами():
    assert parse_number("1 000 000") == Decimal("1000000")
    assert parse_number("12,5") == Decimal("12.5")
    assert parse_number("16%") == Decimal("16")
    with pytest.raises(Exception):
        parse_number("много")


def test_разбор_срока():
    assert parse_term("12") == (12, "m")
    assert parse_term("18м") == (18, "m")
    assert parse_term("540д") == (540, "d")
    assert parse_term("3г") == (36, "m")
    with pytest.raises(Exception):
        parse_term("скоро")


def test_разбор_операции():
    пополнение = parse_flow("01.03.2026:50000")
    assert пополнение.date == date(2026, 3, 1)
    assert пополнение.amount == Decimal("50000.00")
    снятие = parse_flow("01.06.2026:-20000")
    assert снятие.amount == Decimal("-20000.00")
    with pytest.raises(Exception):
        parse_flow("01.03.2026")


def test_форматирование_суммы():
    assert fmt_money(Decimal("1234567.891"), "₽") == "1 234 567,89 ₽"
    assert fmt_money(Decimal("0")) == "0,00"


def test_запуск_расчёта(capsys):
    код = main([
        "-a", "100000", "-r", "10",
        "--start", "01.01.2023", "--term", "12",
        "--schedule",
    ])
    вывод = capsys.readouterr().out
    assert код == 0
    assert "110 000,00 ₽" in вывод
    assert "ГРАФИК" in вывод


def test_вывод_json(capsys):
    код = main([
        "-a", "100000", "-r", "10", "--start", "01.01.2023",
        "--term", "12", "--cap", "ежемесячно", "--json",
    ])
    данные = json.loads(capsys.readouterr().out)
    assert код == 0
    assert данные["условия"]["капитализация"] == "monthly"
    assert Decimal(данные["итоги"]["начислено_процентов"]) > Decimal("10000")
    assert len(данные["график"]) == 12


def test_сравнение_схем(capsys):
    main(["-a", "100000", "-r", "10", "--start", "01.01.2023", "--term", "12", "--compare"])
    вывод = capsys.readouterr().out
    assert "СРАВНЕНИЕ СХЕМ КАПИТАЛИЗАЦИИ" in вывод
    assert "ежедневная" in вывод


def test_срок_днями_и_дата_закрытия_дают_один_результат(capsys):
    main(["-a", "100000", "-r", "10", "--start", "01.01.2023", "--term", "365д", "--json"])
    по_дням = json.loads(capsys.readouterr().out)
    main(["-a", "100000", "-r", "10", "--start", "01.01.2023", "--end", "01.01.2024", "--json"])
    по_дате = json.loads(capsys.readouterr().out)
    assert по_дням["итоги"] == по_дате["итоги"]


def test_ошибка_в_условиях_возвращает_код_2(capsys):
    код = main(["-a", "100000", "-r", "10", "--start", "01.01.2023", "--end", "01.01.2022"])
    assert код == 2
    assert "Ошибка" in capsys.readouterr().err


def test_регулярные_пополнения_из_командной_строки(capsys):
    main([
        "-a", "100000", "-r", "10", "--start", "01.01.2023", "--term", "12",
        "--topup", "10000", "--json",
    ])
    данные = json.loads(capsys.readouterr().out)
    assert Decimal(данные["итоги"]["пополнения"]) == Decimal("110000.00")
    assert Decimal(данные["итоги"]["вложено"]) == Decimal("210000.00")
