//! The hand-written C header against the Rust exports (OWNER RULING 2026-11-16, D6: no cbindgen).
//! Both files are read with plain string scanning: every `#[no_mangle] pub extern "C" fn` of
//! src/lib.rs must be declared in include/qod_search.h with the same parameter count, parameter types
//! and return type (each Rust type spelled in C by the fixed table below), inside the `#ifdef` its
//! Cargo feature maps to; every `#[repr(C)]` struct must have the same field names, order and types;
//! every opaque `Qs*` type a typedef; every `QS_*` constant the same value. And the other way: the
//! header declares nothing Rust does not export.
use std::collections::BTreeMap;

const LIB: &str = include_str!("../src/lib.rs");
const HEADER: &str = include_str!("../include/qod_search.h");

/// Rust base types and their C spelling. A type outside this table (and not a `Qs*` type) fails the
/// test, so a new parameter type needs a deliberate entry.
const BASE_TYPES: &[(&str, &str)] = &[
    ("c_char", "char"),
    ("c_int", "int"),
    ("u8", "uint8_t"),
    ("i8", "int8_t"),
    ("u16", "uint16_t"),
    ("i16", "int16_t"),
    ("u32", "uint32_t"),
    ("i32", "int32_t"),
    ("u64", "uint64_t"),
    ("i64", "int64_t"),
    ("usize", "size_t"),
    ("isize", "ptrdiff_t"),
    ("f32", "float"),
    ("f64", "double"),
    ("()", "void"),
];

/// Cargo features and the header macro that gates their declarations.
const FEATURE_MACROS: &[(&str, &str)] = &[("measurement", "QOD_SEARCH_MEASUREMENT"), ("test-hooks", "QOD_SEARCH_TEST_HOOKS")];

/// The C spelling of a Rust type: pointers by the rule `*const T` = `const T *` (or `T const *`
/// when T is itself a pointer), `*mut T` = `T *`.
fn c_type(rust: &str) -> String {
    let t = rust.trim();
    if let Some(inner) = t.strip_prefix("*const ") {
        let inner = inner.trim();
        let c = c_type(inner);
        return if inner.starts_with('*') { format!("{c} const *") } else { format!("const {c} *") };
    }
    if let Some(inner) = t.strip_prefix("*mut ") {
        return format!("{} *", c_type(inner));
    }
    if let Some((_, c)) = BASE_TYPES.iter().find(|(r, _)| *r == t) {
        return c.to_string();
    }
    if t.starts_with("Qs") && t.chars().all(|c| c.is_ascii_alphanumeric()) {
        return t.to_string();
    }
    panic!("no C spelling for the Rust type {t:?}: add it to BASE_TYPES");
}

/// One spelling for comparison: single spaces, none around `*`.
fn norm(c: &str) -> String {
    let collapsed = c.split_whitespace().collect::<Vec<_>>().join(" ");
    collapsed.replace(" *", "*").replace("* ", "*")
}

#[derive(Debug, PartialEq, Eq, Clone)]
struct Func {
    params: Vec<String>,
    ret: String,
    gate: Option<String>,
}

/// Splits `text` on `sep` at bracket depth 0.
fn split_top(text: &str, sep: char) -> Vec<String> {
    let mut out = Vec::new();
    let mut depth = 0i32;
    let mut cur = String::new();
    for c in text.chars() {
        match c {
            '(' | '[' | '<' | '{' => depth += 1,
            ')' | ']' | '>' | '}' => depth -= 1,
            _ => {}
        }
        if c == sep && depth == 0 {
            out.push(std::mem::take(&mut cur));
        } else {
            cur.push(c);
        }
    }
    out.push(cur);
    out.into_iter().map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
}

/// The attribute lines right above line `i` (blank and doc-comment lines skipped).
fn attributes_above(lines: &[&str], i: usize) -> Vec<String> {
    let mut out = Vec::new();
    let mut j = i;
    while j > 0 {
        j -= 1;
        let l = lines[j].trim();
        if l.starts_with("#[") {
            out.push(l.to_string());
        } else if l.starts_with("///") || l.starts_with("//") || l.is_empty() {
            continue;
        } else {
            break;
        }
    }
    out
}

/// The header macro gating an export, from its attributes. The only `cfg` form allowed above an
/// export is one plain `#[cfg(feature = "<a feature of FEATURE_MACROS>")]`: any other (`cfg_attr`,
/// `not`, `all`, `any`, a target, two of them) fails, since the header could not say when the
/// symbol exists.
fn feature_gate(attrs: &[String], name_line: usize) -> Option<String> {
    let mut gate = None;
    for a in attrs {
        if !a.starts_with("#[cfg") {
            continue;
        }
        let feature = a
            .strip_prefix("#[cfg(feature = \"")
            .and_then(|r| r.strip_suffix("\")]"))
            .filter(|f| !f.contains('"'))
            .unwrap_or_else(|| panic!("lib.rs line {name_line}: {a} is not the plain #[cfg(feature = \"...\")] form"));
        let m = FEATURE_MACROS.iter().find(|(f, _)| *f == feature).unwrap_or_else(|| panic!("feature {feature} has no header macro"));
        assert!(gate.is_none(), "lib.rs line {name_line}: two cfg attributes on one export");
        gate = Some(m.1.to_string());
    }
    gate
}

fn rust_functions() -> BTreeMap<String, Func> {
    let lines: Vec<&str> = LIB.lines().collect();
    let mut out = BTreeMap::new();
    for (i, line) in lines.iter().enumerate() {
        if line.trim() != "#[no_mangle]" {
            continue;
        }
        let gate = feature_gate(&attributes_above(&lines, i + 1), i + 1);
        // The signature runs from the next line to the opening brace.
        let rest: String = lines[i + 1..].join("\n");
        let sig = rest.split('{').next().unwrap();
        let sig = sig.trim();
        let after_fn = sig.split_once("extern \"C\" fn ").unwrap_or_else(|| panic!("not an extern \"C\" fn: {sig}")).1;
        assert!(sig.starts_with("pub "), "not public: {sig}");
        let (name, rest) = after_fn.split_once('(').unwrap();
        let close = rest.rfind(')').unwrap();
        let params_text = &rest[..close];
        let ret = rest[close + 1..].trim().strip_prefix("->").map(|r| r.trim().to_string()).unwrap_or_else(|| "()".into());
        let params = split_top(params_text, ',')
            .iter()
            .map(|p| norm(&c_type(p.split_once(':').unwrap_or_else(|| panic!("parameter {p}")).1)))
            .collect();
        let prev = out.insert(name.trim().to_string(), Func { params, ret: norm(&c_type(&ret)), gate });
        assert!(prev.is_none(), "{name} exported twice");
    }
    out
}

/// The `pub struct` items of lib.rs: repr(C) ones with their fields, and the opaque others.
fn rust_structs() -> (BTreeMap<String, Vec<(String, String)>>, Vec<String>) {
    let lines: Vec<&str> = LIB.lines().collect();
    let mut repr = BTreeMap::new();
    let mut opaque = Vec::new();
    for (i, line) in lines.iter().enumerate() {
        let Some(rest) = line.trim().strip_prefix("pub struct ") else { continue };
        let name: String = rest.chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '_').collect();
        if !name.starts_with("Qs") {
            continue;
        }
        let attrs = attributes_above(&lines, i);
        if !attrs.iter().any(|a| a == "#[repr(C)]") {
            opaque.push(name);
            continue;
        }
        let body: String = lines[i..].join("\n");
        let body = body.split_once('{').unwrap().1.split('}').next().unwrap();
        let fields = split_top(body, ',')
            .iter()
            .map(|f| {
                let f = f.lines().filter(|l| !l.trim().starts_with("//")).collect::<Vec<_>>().join(" ");
                let (n, t) = f.trim().strip_prefix("pub ").unwrap_or_else(|| panic!("private field {f} of {name}")).split_once(':').unwrap();
                (n.trim().to_string(), norm(&c_type(t)))
            })
            .collect();
        repr.insert(name, fields);
    }
    (repr, opaque)
}

fn rust_constants() -> BTreeMap<String, i64> {
    LIB.lines()
        .filter_map(|l| {
            let rest = l.trim().strip_prefix("pub const QS_")?;
            let (name, rest) = rest.split_once(':')?;
            let value = rest.split_once('=')?.1.trim().trim_end_matches(';').trim();
            Some((format!("QS_{name}"), value.parse().unwrap_or_else(|_| panic!("QS_{name} = {value} is not a literal"))))
        })
        .collect()
}

/// The header without comments, as (gate, text) statements.
fn header_statements() -> Vec<(Option<String>, String)> {
    let mut text = String::new();
    let mut rest = HEADER;
    while let Some(at) = rest.find("/*") {
        text.push_str(&rest[..at]);
        rest = &rest[at + rest[at..].find("*/").unwrap() + 2..];
    }
    text.push_str(rest);
    let mut gate: Vec<Option<String>> = Vec::new();
    let mut out = Vec::new();
    let mut cur = String::new();
    for line in text.lines() {
        let line = line.split("//").next().unwrap().trim();
        if let Some(m) = line.strip_prefix("#ifdef ") {
            gate.push(Some(m.trim().to_string()).filter(|m| m != "__cplusplus"));
            continue;
        }
        if line.starts_with("#endif") {
            gate.pop();
            continue;
        }
        if line.starts_with('#') || line.starts_with("extern \"C\"") || line == "}" {
            if let Some(v) = line.strip_prefix("#define QS_") {
                let (n, v) = v.split_once(' ').unwrap();
                out.push((None, format!("#define QS_{n} {}", v.trim())));
            }
            continue;
        }
        cur.push(' ');
        cur.push_str(line);
        // A statement ends at a ';' outside braces.
        let depth = cur.matches('{').count() as i32 - cur.matches('}').count() as i32;
        if line.ends_with(';') && depth == 0 {
            let g = gate.iter().rev().find_map(|g| g.clone());
            out.push((g, std::mem::take(&mut cur).trim().to_string()));
        }
    }
    out
}

/// A C declaration `type name` (stars belong to the type): (type, name).
fn c_decl(decl: &str) -> (String, String) {
    let d = decl.trim();
    let name_start = d.rfind(|c: char| !(c.is_ascii_alphanumeric() || c == '_')).map(|i| i + 1).unwrap_or(0);
    (norm(&d[..name_start]), d[name_start..].to_string())
}

struct Header {
    functions: BTreeMap<String, Func>,
    structs: BTreeMap<String, Vec<(String, String)>>,
    opaque: Vec<String>,
    constants: BTreeMap<String, i64>,
}

fn header() -> Header {
    let mut h = Header { functions: BTreeMap::new(), structs: BTreeMap::new(), opaque: Vec::new(), constants: BTreeMap::new() };
    for (gate, s) in header_statements() {
        let s = s.trim_end_matches(';').trim().to_string();
        if let Some(rest) = s.strip_prefix("#define ") {
            let (n, v) = rest.split_once(' ').unwrap();
            h.constants.insert(n.to_string(), v.parse().unwrap());
        } else if let Some(rest) = s.strip_prefix("enum {") {
            for item in split_top(rest.trim_end_matches('}'), ',') {
                let (n, v) = item.split_once('=').unwrap_or_else(|| panic!("enum constant {item} has no value"));
                h.constants.insert(n.trim().to_string(), v.trim().parse().unwrap());
            }
        } else if let Some(rest) = s.strip_prefix("typedef struct ") {
            if let Some((name, body)) = rest.split_once('{') {
                let name = name.trim().to_string();
                let (body, alias) = body.rsplit_once('}').unwrap();
                assert_eq!(alias.trim(), name, "typedef struct {name} names another type");
                let mut fields = Vec::new();
                for decl in split_top(body, ';') {
                    let parts = split_top(&decl, ',');
                    let (first_ty, first_name) = c_decl(&parts[0]);
                    let base = first_ty.trim_end_matches('*').to_string();
                    fields.push((first_name, first_ty));
                    for more in &parts[1..] {
                        let stars = more.chars().take_while(|c| *c == '*' || c.is_whitespace()).filter(|c| *c == '*').count();
                        fields.push((more.trim_start_matches(['*', ' ']).to_string(), norm(&format!("{base}{}", "*".repeat(stars)))));
                    }
                }
                h.structs.insert(name, fields);
            } else {
                let (a, b) = rest.split_once(' ').unwrap();
                assert_eq!(a.trim(), b.trim(), "opaque typedef {s}");
                h.opaque.push(a.trim().to_string());
            }
        } else if s.contains('(') {
            let (head, params) = s.split_once('(').unwrap();
            let params = params.trim_end_matches(')');
            let (ret, name) = c_decl(head);
            let params = if params.trim() == "void" {
                Vec::new()
            } else {
                split_top(params, ',').iter().map(|p| c_decl(p).0).collect()
            };
            let prev = h.functions.insert(name.clone(), Func { params, ret, gate });
            assert!(prev.is_none(), "{name} declared twice");
        } else {
            panic!("an unread header statement: {s}");
        }
    }
    h
}

#[test]
fn every_export_is_declared_alike() {
    let rust = rust_functions();
    let h = header();
    assert!(rust.len() >= 19, "only {} exports found: the scanner lost some", rust.len());
    for (name, f) in &rust {
        let d = h.functions.get(name).unwrap_or_else(|| panic!("{name} is exported but not in the header"));
        assert_eq!(d.params.len(), f.params.len(), "{name}: parameter count");
        assert_eq!(d.params, f.params, "{name}: parameter types");
        assert_eq!(d.ret, f.ret, "{name}: return type");
        assert_eq!(d.gate, f.gate, "{name}: the header gates it otherwise than its Cargo feature");
    }
    for name in h.functions.keys() {
        assert!(rust.contains_key(name), "{name} is declared but not exported");
    }
}

#[test]
fn every_struct_matches() {
    let (repr, opaque) = rust_structs();
    let h = header();
    assert!(repr.len() >= 5, "only {} repr(C) structs found", repr.len());
    for (name, fields) in &repr {
        let d = h.structs.get(name).unwrap_or_else(|| panic!("struct {name} is not in the header"));
        assert_eq!(d, fields, "struct {name}: field names, order or types");
    }
    for name in h.structs.keys() {
        assert!(repr.contains_key(name), "struct {name} is declared but not repr(C) in lib.rs");
    }
    let mut o = opaque.clone();
    o.sort();
    let mut ho = h.opaque.clone();
    ho.sort();
    assert_eq!(o, ho, "opaque types");
}

#[test]
fn every_constant_matches() {
    let rust = rust_constants();
    let h = header();
    assert!(rust.contains_key("QS_BUNDLE_FORMAT_VERSION") && rust.contains_key("QS_PATH_EXHAUSTIVE"));
    assert_eq!(rust, h.constants);
}

#[test]
fn the_type_table_spells_pointers_as_c_does() {
    assert_eq!(norm(&c_type("*const *const u64")), norm("const uint64_t *const *"));
    assert_eq!(norm(&c_type("*mut *mut c_char")), "char**");
    assert_eq!(norm(&c_type("*const QsSource")), "const QsSource*");
    assert_eq!(norm(&c_type("usize")), "size_t");
}

/// Every `.rs` file under src/, recursively.
fn source_files(dir: &std::path::Path, out: &mut Vec<std::path::PathBuf>) {
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            source_files(&path, out);
        } else if path.extension().map(|e| e == "rs").unwrap_or(false) {
            out.push(path);
        }
    }
}

#[test]
fn exports_only_in_the_scanned_form() {
    // A symbol exported any other way (`#[unsafe(no_mangle)]`, `export_name`, a `cfg_attr`, an
    // indented attribute inside a module, another file) would escape the scan above, so none is.
    let src = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let mut files = Vec::new();
    source_files(&src, &mut files);
    let lib = src.join("lib.rs");
    assert!(files.contains(&lib) && files.len() >= 8, "the scan found {files:?}");
    assert_eq!(std::fs::read_to_string(&lib).unwrap(), LIB, "lib.rs changed under the test");
    let mut exports = 0;
    for path in &files {
        let text = std::fs::read_to_string(path).unwrap();
        for (n, line) in text.lines().enumerate() {
            if line.trim_start().starts_with("//") || !(line.contains("no_mangle") || line.contains("export_name")) {
                continue;
            }
            assert!(
                *path == lib && line == "#[no_mangle]",
                "{}:{}: an export outside the form the parity scan reads: {}",
                path.display(),
                n + 1,
                line.trim()
            );
            exports += 1;
        }
    }
    assert_eq!(exports, rust_functions().len());
}
