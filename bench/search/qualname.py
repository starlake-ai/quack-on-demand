#!/usr/bin/env python3
"""Reads demangled C++ symbol names (Itanium or MSVC) on stdin and prints, sorted and unique, each
duckdb symbol's qualified name: template arguments, parameters, return types, access and calling
conventions removed; vtables as 'vtable for <class>'."""
import re
import sys


def strip_templates(s: str) -> str:
    out, depth = [], 0
    for ch in s:
        if ch == "<":
            depth += 1
        elif ch == ">" and depth:
            depth -= 1
        elif depth == 0:
            out.append(ch)
    return "".join(out)


def qual(line: str):
    s = line.strip()
    m = re.match(r"(vtable|typeinfo|typeinfo name) for (.+)$", s)
    if m:
        return f"{m.group(1)} for {strip_templates(m.group(2)).strip()}"
    m = re.search(r"(duckdb::[\w:~]+)::`vftable'", strip_templates(s))
    if m:
        return f"vtable for {m.group(1)}"
    s = strip_templates(s)
    head = s.split("(", 1)[0]
    m = re.findall(r"duckdb::[\w:~]+(?:operator\S*)?", head)
    return m[-1] if m else None


names = {q for q in (qual(l) for l in sys.stdin) if q}
print("\n".join(sorted(names)))
