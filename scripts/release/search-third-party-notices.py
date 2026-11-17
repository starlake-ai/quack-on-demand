#!/usr/bin/env python3
"""Third-party notices for qod_search_core (search spec 13, change 44, Q10 of Phase 0).

Every binary that links qod_search_core (native/qod-search) ships the notices of the crates linked into it:
the vendored qod-node binaries and the wheel's copies, the Windows release assets and the node image. This
script writes them, from Cargo.lock, into native/qod-search/THIRD-PARTY-NOTICES.txt:

  1. `cargo +<channel> metadata --locked --format-version 1` in native/qod-search (the channel of its
     rust-toolchain.toml), whose packages carry each crate's license expression and the manifest path of its
     source in the local registry (~/.cargo/registry/src: run `cargo fetch --locked` there first).
  2. Every package reachable from qod_search_core through normal or build edges, over every target (dev
     edges are never followed; the crate itself is not listed).
  3. For each: name, version, license expression, authors and repository, and every license, copyright,
     authors and NOTICE file at the root of its source, each by the number of its text. A package that ships
     none takes those of a package from the same repository that does (Tantivy's sub-crates take Tantivy's),
     when that package's license covers its own (else the run fails); else the standard text of a license its
     expression allows alone and that names no licensor (Apache-2.0), from native/qod-search/license-texts/.
     The full texts follow, numbered, each written once.
  4. Part 2 ("Rust package:"): the standard library of that Rust release, which the staticlib carries (std,
     core, alloc, the runtime crates, compiler_builtins with compiler-rt's code, their crates.io
     dependencies), read from the toolchain's rust-src component (`rustup component add rust-src --toolchain
     <channel>`) and share/doc/rust: see "the Rust standard library of the pinned toolchain" below. No host
     path reaches the file: toolchain files are named relative to the sysroot or rust-src.

Every expression must be satisfiable by native/qod-search/allowed-licenses.txt alone: each identifier of an
AND, at least one branch of an OR (cargo's legacy "A/B" reads as "A OR B"), a WITH branch only when its
exception is listed too. Any other license fails the run, naming the package.

  --check   regenerate in memory and fail when the committed file differs or a license is not allowed.

Standard library only; run with `python3 -I`.
"""

import argparse
import html
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CRATE = REPO / "native" / "qod-search"
NOTICES = CRATE / "THIRD-PARTY-NOTICES.txt"
ALLOWED = CRATE / "allowed-licenses.txt"
TEXTS = CRATE / "license-texts"
ROOT_NAME = "qod_search_core"

# Files at a package's root that carry its license terms or the notices those terms require be kept.
NOTICE_FILE = re.compile(r"^(LICEN[CS]E|COPYING|COPYRIGHT|NOTICE|UNLICENSE|AUTHORS)([-._].*)?$", re.IGNORECASE)
# The licenses whose standard text can stand for a package that ships no license file: the text names no
# licensor. MIT, the BSDs, ISC and Zlib each open with a copyright line that only the package's authors can write.
STANDARD_TEXTS = ["Apache-2.0"]
RULE = "=" * 100


class NoticeError(Exception):
    pass


def toolchain_channel():
    text = (CRATE / "rust-toolchain.toml").read_text(encoding="utf-8")
    match = re.search(r'^channel\s*=\s*"([^"]+)"', text, re.MULTILINE)
    if not match:
        raise NoticeError("no channel in native/qod-search/rust-toolchain.toml")
    return match.group(1)


def cargo_metadata():
    command = ["cargo", "+" + toolchain_channel(), "metadata", "--locked", "--format-version", "1"]
    return json.loads(run(command, cwd=CRATE, what="in native/qod-search"))


def read_allowed():
    names = set()
    for line in ALLOWED.read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            names.add(line)
    if not names:
        raise NoticeError(f"{ALLOWED.relative_to(REPO)} lists no license")
    return names


# ---- SPDX expressions -------------------------------------------------------------------------------------

def tokenize(expression):
    tokens = []
    for token in re.findall(r"\(|\)|/|[^\s()/]+", expression):
        upper = token.upper()
        if upper in ("AND", "OR", "WITH"):
            tokens.append(upper)
        elif token == "/":
            tokens.append("OR")
        else:
            tokens.append(token)
    return tokens


def parse(expression):
    """The expression as a tree: ("id", name), ("with", name, exception), ("and", [..]), ("or", [..])."""
    tokens = tokenize(expression)
    position = 0

    def peek():
        return tokens[position] if position < len(tokens) else None

    def take():
        nonlocal position
        if position >= len(tokens):
            raise NoticeError(f"license expression ends early: {expression!r}")
        position += 1
        return tokens[position - 1]

    def atom():
        token = take()
        if token == "(":
            node = disjunction()
            if take() != ")":
                raise NoticeError(f"unbalanced parentheses in {expression!r}")
            return node
        if token in ("AND", "OR", "WITH", ")"):
            raise NoticeError(f"unexpected {token!r} in license expression {expression!r}")
        if peek() == "WITH":
            take()
            return ("with", token, take())
        return ("id", token)

    def conjunction():
        parts = [atom()]
        while peek() == "AND":
            take()
            parts.append(atom())
        return parts[0] if len(parts) == 1 else ("and", parts)

    def disjunction():
        parts = [conjunction()]
        while peek() == "OR":
            take()
            parts.append(conjunction())
        return parts[0] if len(parts) == 1 else ("or", parts)

    tree = disjunction()
    if position != len(tokens):
        raise NoticeError(f"trailing text in license expression {expression!r}")
    return tree


def satisfied(tree, allowed):
    kind = tree[0]
    if kind == "id":
        return tree[1] in allowed
    if kind == "with":
        return tree[1] in allowed and tree[2] in allowed
    if kind == "and":
        return all(satisfied(part, allowed) for part in tree[1])
    return any(satisfied(part, allowed) for part in tree[1])


def identifiers(tree):
    if tree[0] == "id":
        return [tree[1]]
    if tree[0] == "with":
        return [tree[1], tree[2]]
    return [name for part in tree[1] for name in identifiers(part)]


# ---- the dependency graph ---------------------------------------------------------------------------------

def reachable(metadata, root):
    """Every package reachable from root through normal or build edges, root excluded."""
    nodes = {node["id"]: node for node in metadata["resolve"]["nodes"]}
    seen, stack = set(), [root]
    while stack:
        for dep in nodes[stack.pop()]["deps"]:
            if any(kind["kind"] in (None, "build") for kind in dep["dep_kinds"]) and dep["pkg"] not in seen:
                seen.add(dep["pkg"])
                stack.append(dep["pkg"])
    seen.discard(root)
    return seen


def by_name_version(packages):
    return sorted(packages, key=lambda p: (p["name"], version_key(p["version"]), p["id"]))


def shipped_packages(metadata):
    packages = {package["id"]: package for package in metadata["packages"]}
    root = metadata["resolve"]["root"]
    if root is None or packages[root]["name"] != ROOT_NAME:
        raise NoticeError(f"cargo metadata's root package is not {ROOT_NAME}")
    return by_name_version(packages[pid] for pid in reachable(metadata, root))


def version_key(version):
    return [int(part) if part.isdigit() else part for part in re.split(r"[.+-]", version)]


def read_text(path):
    text = path.read_bytes().decode("utf-8", errors="replace").replace("\r\n", "\n").replace("\r", "\n")
    return text.rstrip("\n") + "\n"


def notice_names(directory):
    return sorted(name for name in os.listdir(directory) if NOTICE_FILE.match(name) and (directory / name).is_file())


def notice_files(package):
    root = Path(package["manifest_path"]).parent
    if not root.is_dir():
        raise NoticeError(f"{package['name']} {package['version']}: no source at {root}; "
                          "run `cargo fetch --locked` in native/qod-search")
    names = set(notice_names(root))
    if package.get("license_file"):
        names.add(package["license_file"])
    return [(name, read_text(root / name)) for name in sorted(names)]


def authors_line(package):
    authors = [re.sub(r"\s*<[^>]*>", "", author).strip() for author in package.get("authors") or []]
    return ", ".join(author for author in authors if author)


def standard_text(package, tree, allowed):
    for name in STANDARD_TEXTS:
        if name in allowed and satisfied(tree, {name}) and (TEXTS / f"{name}.txt").is_file():
            return name, read_text(TEXTS / f"{name}.txt")
    raise NoticeError(f"{package['name']} {package['version']} ships no license file, no package of its repository "
                      f"ships one under a license it carries, and its license ({package['license']}) offers none of "
                      f"{', '.join(STANDARD_TEXTS)} alone, whose standard text native/qod-search/license-texts/ holds: "
                      "find the package's license text")


# ---- the Rust standard library of the pinned toolchain ---------------------------------------------------
# qod_search_core is a staticlib: it carries the standard library it is built with (std, core, alloc, the
# panic runtimes, unwind, std_detect, compiler_builtins with compiler-rt's code, and their crates.io
# dependencies), and so does every binary linking it. Their notices come from the pinned toolchain itself:
#
#   - which crates: `cargo metadata --locked` over the rust-src component's library/ workspace (its Cargo.lock
#     is the one the release was built from), once per platform a node runs on (STD_TARGETS), every package
#     reachable from std through normal and build edges, std included. RUSTC_BOOTSTRAP=1 lets this stable
#     cargo read that workspace, which declares nightly cargo features; nothing is built.
#   - files: a crates.io dependency's own, from the registry as for qod_search_core's; an in-tree crate's from
#     the nearest folder of rust-src's library/ holding any (library/compiler-builtins/LICENSE.txt holds MIT,
#     Apache-2.0 and the LLVM exception, compiler-rt's terms; library/stdarch's cover std_detect), and for every
#     in-tree crate the standard library's in-tree notices (share/doc/rust/COPYRIGHT-library.html, which the
#     rustc component ships) with the texts of the licenses they name (share/doc/rust/licenses/, the Rust
#     repository's own LICENSES): rust-src carries no license text for std, core or alloc themselves.
STD_TARGETS = [
    "aarch64-apple-darwin", "aarch64-pc-windows-msvc", "aarch64-unknown-linux-gnu",
    "x86_64-apple-darwin", "x86_64-pc-windows-msvc", "x86_64-unknown-linux-gnu",
]


def run(command, cwd=None, env=None, what=None):
    try:
        done = subprocess.run(command, cwd=cwd, env=env, capture_output=True, text=True, check=False)
    except OSError as error:
        raise NoticeError(f"cannot run {command[0]}: {error}") from error
    if done.returncode != 0:
        raise NoticeError(f"`{' '.join(command)}` failed{' ' + what if what else ''}:\n{done.stderr.strip()}")
    return done.stdout


def toolchain_paths(channel):
    sysroot = Path(run(["rustc", "+" + channel, "--print", "sysroot"]).strip())
    rust_src = sysroot / "lib" / "rustlib" / "src" / "rust"
    docs = sysroot / "share" / "doc" / "rust"
    if not (rust_src / "library" / "Cargo.lock").is_file():
        raise NoticeError(f"the Rust {channel} toolchain has no rust-src component: "
                          f"rustup component add rust-src --toolchain {channel}")
    if not (docs / "COPYRIGHT-library.html").is_file() or not (docs / "licenses").is_dir():
        raise NoticeError(f"the Rust {channel} toolchain has no share/doc/rust/COPYRIGHT-library.html or licenses/ "
                          "(the rustc component ships them): reinstall the toolchain")
    return sysroot, rust_src, docs


def in_tree_notices(docs):
    """The standard library's own notices (everything COPYRIGHT-library.html says before its crates.io list), as
    text, and the licenses its in-tree entries name."""
    page = (docs / "COPYRIGHT-library.html").read_text(encoding="utf-8")
    try:
        start = page.index('<h2 id="short-version">')
        end = page.index('<h2 id="out-of-tree-dependencies">')
    except ValueError as error:
        raise NoticeError("share/doc/rust/COPYRIGHT-library.html has not the sections this script reads") from error
    body = re.sub(r"<(h2|p|div|li|br)\b[^>]*>", "\n", page[start:end])
    body = html.unescape(re.sub(r"<[^>]+>", "", body))
    lines = []
    for line in body.split("\n"):
        line = " ".join(line.split())
        if line:
            lines.append(line)
        elif lines and lines[-1]:
            lines.append("")
    text = "\n".join(lines).strip("\n") + "\n"
    names = set()
    for expression in re.findall(r"^License: (.+)$", text, re.MULTILINE):
        names.update(identifiers(parse(expression)))
    default = re.search(r"^File/Directory: \.\n\nLicense: (.+)$", text, re.MULTILINE)
    if not default:
        raise NoticeError("share/doc/rust/COPYRIGHT-library.html names no license for the whole tree (File/Directory: .)")
    return text, sorted(names), default.group(1)


def std_packages(channel):
    sysroot, rust_src, docs = toolchain_paths(channel)
    manifest = rust_src / "library" / "Cargo.toml"
    env = dict(os.environ, RUSTC_BOOTSTRAP="1")
    found = {}
    for target in STD_TARGETS:
        metadata = json.loads(run(["cargo", "+" + channel, "metadata", "--locked", "--format-version", "1",
                                   "--manifest-path", str(manifest), "--filter-platform", target],
                                  env=env, what=f"on rust-src's library/ for {target}"))
        packages = {package["id"]: package for package in metadata["packages"]}
        roots = [pid for pid, package in packages.items() if package["name"] == "std" and package["source"] is None]
        if len(roots) != 1:
            raise NoticeError(f"rust-src's library/ workspace has no single std package for {target}")
        for pid in reachable(metadata, roots[0]) | set(roots):
            found.setdefault((packages[pid]["name"], packages[pid]["version"], packages[pid]["source"]), packages[pid])
    library = (rust_src / "library").resolve()
    notices, named, tree_license = in_tree_notices(docs)
    shared = [("share/doc/rust/COPYRIGHT-library.html, in-tree files (as text)", notices)]
    for name in named:
        path = docs / "licenses" / f"{name}.txt"
        if not path.is_file():
            raise NoticeError(f"share/doc/rust/licenses/ has no {name}.txt, which COPYRIGHT-library.html names")
        shared.append((f"share/doc/rust/licenses/{name}.txt", read_text(path)))
    entries = []
    for package in by_name_version(found.values()):
        if package["source"] is not None:
            entries.append((package, notice_files(package), None))
            continue
        directory = Path(package["manifest_path"]).parent.resolve()
        own = []
        while directory != library and library in directory.parents:
            names = notice_names(directory)
            if names:
                relative = directory.relative_to(rust_src.resolve()).as_posix()
                own = [(f"{relative}/{name}", read_text(directory / name)) for name in names]
                break
            directory = directory.parent
        source = f"in-tree, Rust {channel} rust-src"
        if not package.get("license"):
            # The tree's license covers a crate whose Cargo.toml names none (COPYRIGHT-library.html, File/Directory: .).
            package = dict(package, license=tree_license)
            source += f"; its Cargo.toml names no license, so the whole tree's, {tree_license}"
        entries.append((package, own + shared, source))
    return entries


# ---- the notices ------------------------------------------------------------------------------------------

def check_licenses(packages, allowed):
    refused = []
    for package in packages:
        expression = package.get("license")
        if not expression:
            refused.append(f"{package['name']} {package['version']}: no SPDX license expression")
            continue
        tree = parse(expression)
        if not satisfied(tree, allowed):
            outside = sorted(set(identifiers(tree)) - allowed)
            refused.append(f"{package['name']} {package['version']}: {expression} "
                           f"(not in allowed-licenses.txt: {', '.join(outside)})")
    if refused:
        raise NoticeError("licenses outside native/qod-search/allowed-licenses.txt:\n  " + "\n  ".join(refused))


def render(metadata, allowed, channel, std_entries):
    packages = shipped_packages(metadata)
    check_licenses(packages + [package for package, _, _ in std_entries], allowed)
    files = {package["id"]: notice_files(package) for package in packages}

    by_repository = {}
    for package in packages:
        repository = (package.get("repository") or "").rstrip("/")
        if repository and files[package["id"]] and repository not in by_repository:
            by_repository[repository] = package

    out = [
        "Third-party notices for qod_search_core (native/qod-search), the search library qod-node links.",
        "Generated from native/qod-search/Cargo.lock and the pinned Rust toolchain by",
        "scripts/release/search-third-party-notices.py; do not edit.",
        "",
        f"Part 1, {len(packages)} packages (\"Package:\"): every crate reachable from qod_search_core through normal",
        "and build dependencies, over every target.",
        f"Part 2, {len(std_entries)} packages (\"Rust package:\"): the Rust {channel} standard library qod_search_core",
        "is built with and carries (std, core, alloc and the runtime crates, compiler_builtins with compiler-rt's",
        "code, and their crates.io dependencies), for the six platforms qod-node runs on.",
        "Each entry gives the license expression and the license, copyright, authors and NOTICE files that cover it;",
        "the full text of each file follows the entries, under its number, written once however many carry it.",
        "",
    ]
    texts = {}
    carriers = {}

    def number(text, label):
        if text not in texts:
            texts[text] = len(texts) + 1
            carriers[text] = []
        carriers[text].append(label)
        return texts[text]

    def entry(prefix, package, label, entries, source):
        out.extend([RULE, f"{prefix}: {label}", f"License: {package['license']}"])
        if authors_line(package):
            out.append(f"Authors: {authors_line(package)}")
        if package.get("repository"):
            out.append(f"Repository: {package['repository']}")
        if source:
            out.append(f"Source: {source}")
        for name, text in entries:
            out.append(f"File: {name} (text {number(text, f'{name} of {label}')})")
        out.append("")

    for package in packages:
        tree = parse(package["license"])
        label = f"{package['name']} {package['version']}"
        entries = files[package["id"]]
        source = None
        repository = (package.get("repository") or "").rstrip("/")
        if not entries and repository in by_repository:
            donor = by_repository[repository]
            covered = set(identifiers(parse(donor["license"]))) & allowed
            if not satisfied(tree, covered):
                raise NoticeError(f"{label} ships no license file and borrows those of {donor['name']} "
                                  f"{donor['version']} (same repository), whose license ({donor['license']}) does not "
                                  f"cover its own ({package['license']}): find the package's license text")
            source = (f"no such file in this package; those of {donor['name']} {donor['version']}, "
                      f"from the same repository, under {donor['license']}")
            entries = files[donor["id"]]
        elif not entries:
            name, text = standard_text(package, tree, allowed)
            source = (f"no license file in this package, whose Cargo.toml names {package['license']}; "
                      f"the standard {name} text, a license it is offered under, which names no licensor")
            entries = [(f"{name} (standard text)", text)]
        entry("Package", package, label, entries, source)

    for package, entries, source in std_entries:
        if not entries:
            raise NoticeError(f"Rust {channel}: {package['name']} {package['version']} carries no license file")
        entry("Rust package", package, f"{package['name']} {package['version']}", entries, source)

    for text, index in texts.items():
        holders = carriers[text]
        more = f", and {len(holders) - 1} more" if len(holders) > 1 else ""
        out += [RULE, f"Text {index}: {holders[0]}{more}", "", text.rstrip("\n"), ""]
    return "\n".join(out).rstrip("\n") + "\n", len(packages), len(std_entries)


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true",
                        help="fail when the committed notices differ from what Cargo.lock and the toolchain give, "
                             "or a license is not allowed")
    args = parser.parse_args(argv)
    try:
        channel = toolchain_channel()
        text, count, std_count = render(cargo_metadata(), read_allowed(), channel, std_packages(channel))
    except NoticeError as error:
        print(f"search notices: {error}", file=sys.stderr)
        return 1
    relative = NOTICES.relative_to(REPO)
    summary = f"{count} packages and {std_count} of the Rust {channel} standard library, every license allowed"
    if args.check:
        committed = NOTICES.read_text(encoding="utf-8") if NOTICES.is_file() else None
        if committed != text:
            state = "missing" if committed is None else "stale"
            print(f"search notices: {relative} is {state} against Cargo.lock and the Rust {channel} toolchain: "
                  "run python3 -I scripts/release/search-third-party-notices.py and commit it", file=sys.stderr)
            return 1
        print(f"search notices OK: {relative} matches ({summary})")
        return 0
    NOTICES.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {relative} ({summary})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
