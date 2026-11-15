"""Gate cells of QoD Search Phase 0 part 2 (spec 11; plan "Gate cells")."""
from dataclasses import dataclass

QUERIES = {"common": "w0", "or": "w1 w10"}
FILTERS = {"100%": None, "10%": "f10 = 0", "1%": "f100 = 0"}
POLICY_USER = {"10%": "rls10", "1%": "rls1"}
SIZES = ("1m", "10m")


@dataclass(frozen=True)
class Cell:
    size: str
    input: str
    form: str  # "where" or "policy"
    query: str  # key of QUERIES

    @property
    def user(self) -> str:
        return POLICY_USER[self.input] if self.form == "policy" else "plain"


CELLS = [Cell(s, i, f, q) for s in SIZES for i in FILTERS for f in ("where", "policy")
         for q in QUERIES if not (f == "policy" and i == "100%")]


def paradedb_sql(cell: Cell) -> str:
    """ParadeDB's top 10 with its own fetch; the match operator and score function are those of
    its public docs (record the page in P4 if they differ from these)."""
    where = f"body ||| '{QUERIES[cell.query]}'"
    if FILTERS[cell.input]:
        where += f" AND {FILTERS[cell.input]}"
    return (f"SELECT id, title, pdb.score(id) AS score FROM pages_{cell.size} WHERE {where} "
            f"ORDER BY score DESC, id LIMIT 10")


def qod_sql(cell: Cell, catalog: str = "acme_lake") -> str:
    """QoD's top 10 and the join-back fetch in one statement (spec 4.1, 7.7). The policy form
    writes no filter: the rewriter wraps the input and the join-back (rule 3)."""
    table = f"{catalog}.main.pages_{cell.size}"
    filt = FILTERS[cell.input] if cell.form == "where" else None
    inner = f"SELECT rowid FROM {table}" + (f" WHERE {filt}" if filt else "")
    return (f"SELECT p.id, p.title, h.score FROM qod_search(({inner}), '{table}', ['body'], "
            f"'{QUERIES[cell.query]}', k := 10) h JOIN {table} p ON p.rowid = h.rowid "
            f"ORDER BY h.score DESC, h.rowid")
