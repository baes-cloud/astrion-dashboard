#!/usr/bin/env python3
"""Fail if any Kotlin file under app/src imports a name it never uses.

Android lint has no check for this and the Kotlin compiler doesn't warn, so
CI runs this instead. Pass --fix to delete the unused lines in place.
"""
import pathlib
import re
import sys

# Operator and delegate functions are used without their name appearing.
IMPLICIT = {
    "getValue", "setValue", "provideDelegate", "component1", "component2", "component3",
    "plus", "minus", "times", "div", "invoke", "contains", "iterator", "compareTo",
    "rangeTo", "unaryMinus", "not", "get", "set", "plusAssign", "minusAssign",
}


def unused_imports(text: str) -> list[str]:
    lines = text.split("\n")
    body = "\n".join(l for l in lines if not l.startswith(("import ", "package ")))
    body = re.sub(r"//[^\n]*", "", body)
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    found = []
    for line in lines:
        m = re.match(r"import ([\w.]+)(?: as (\w+))?\s*$", line)
        if not m:
            continue
        name = m.group(2) or m.group(1).split(".")[-1]
        if name in IMPLICIT:
            continue
        if not re.search(r"(?<!\w)" + re.escape(name) + r"(?!\w)", body):
            found.append(line)
    return found


def main() -> int:
    fix = "--fix" in sys.argv
    total = 0
    for path in sorted(pathlib.Path("app/src").rglob("*.kt")):
        text = path.read_text()
        bad = unused_imports(text)
        if not bad:
            continue
        total += len(bad)
        for line in bad:
            print(f"{path}: unused {line}")
        if fix:
            path.write_text("\n".join(l for l in text.split("\n") if l not in bad))
    if total and not fix:
        print(f"{total} unused import(s); run scripts/check_unused_imports.py --fix")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
