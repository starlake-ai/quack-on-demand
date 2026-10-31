# Starter policy for QoD's OPA authorization (package path qod/authz, the default
# opaPolicyPath). QoD queries two rules:
#   POST /v1/data/qod/authz/connect    pool access, once per session handshake
#   POST /v1/data/qod/authz/statement  table and file access, once per statement
# Each must evaluate to an object {"allow": true} or {"allow": false, "reason": "..."}.
# Anything else (undefined rule, non-boolean allow, row_filter / masks) is a deny.
#
# The statement rule answers two callers. The gateway (client.edge flightsql, quack, mcp,
# rest, dry-run) sends the tables QoD's SQL parser extracted. The node (client.edge "node")
# sends what DuckDB resolved: every access also carries "kind", "columns" (the columns read)
# and "via" (the outermost secure view it is read through, "catalog.schema.name", or null),
# and the input carries "locations", the files the statement reads or writes, each
# {"access": "read" | "write", "location": "...", "scope": "...",
#  "via": "<reader function>" | "copy_to"},
# which QoD has already admitted under one of the user's own S3 credential scopes or the
# tenant's authorized folders ("scope" names the one that admitted it), outside every database
# data folder. A policy must allow both shapes until the gateway's checks are retired.
#
# This policy reproduces QoD's own grant semantics from data you load into OPA:
#   data.qod.pool_grants[tenant][role]  = ["bi", "*"]
#       pool names the role may connect to, "*" = every pool of the tenant
#   data.qod.table_grants[tenant][role] = [{"catalog": "tpch", "schema": "*", "table": "*", "verb": "RO"}]
#       "*" matches any catalog / schema / table; a schema-wide access (kind "schema") has
#       table "*", so only a grant whose table is "*" covers it
#   data.qod.blocked_locations[tenant]  = ["s3://acme-etl/restricted/"]   (optional)
#       location prefixes this tenant's statements may never name, on top of QoD's checks
# Verbs: RO covers read; RW covers read and write; DDL covers ddl; ALL covers everything.
# A read through a secure view is covered by a read grant on the view, as in QoD.
# Access names in the input are lowercase; grant names are compared lowercased.
package qod.authz

import rego.v1

# ---------------------------------------------------------------- connect

default connect := {"allow": false, "reason": "no pool grant"}

connect := {"allow": true} if {
	some role in input.user.roles
	some p in data.qod.pool_grants[input.tenant][role]
	pool_matches(p)
}

pool_matches(p) if p == "*"

pool_matches(p) if p == input.pool

# A branch pool (__br_<id8>) inherits connect permission from any pool of its
# parent database: input.parentPool lists them, and is null for a regular pool.
pool_matches(p) if {
	is_array(input.parentPool)
	p in input.parentPool
}

# ---------------------------------------------------------------- statement

default statement := {"allow": false, "reason": "no table grant"}

# The three rules below are mutually exclusive, so the complete rule `statement`
# can never produce two different values.
statement := {"allow": true} if {
	count(denied) == 0
	count(denied_locations) == 0
}

statement := {
	"allow": false,
	"reason": "missing table grants",
	"denied": [a | some a in denied],
} if count(denied) > 0

statement := {"allow": false, "reason": "location not allowed by policy"} if {
	count(denied) == 0
	count(denied_locations) > 0
}

denied contains a if {
	some a in input.accesses
	not covered(a)
}

# A direct access (the gateway's, or a node access not reached through a secure view).
covered(a) if {
	not through_view(a)
	some role in input.user.roles
	some g in data.qod.table_grants[input.tenant][role]
	wild(g.catalog, a.catalog)
	wild(g.schema, a.schema)
	wild(g.table, a.table)
	verb_covers(upper(g.verb), a.verb)
}

# A read through a secure view (node input only): a read grant on the view covers it.
covered(a) if {
	a.verb == "read"
	through_view(a)
	view := split(a.via, ".")
	count(view) == 3
	every part in view {
		part != ""
	}
	some role in input.user.roles
	some g in data.qod.table_grants[input.tenant][role]
	wild(g.catalog, view[0])
	wild(g.schema, view[1])
	wild(g.table, view[2])
	verb_covers(upper(g.verb), "read")
}

# The access names a secure view it is read through. A rule rather than an inline negated
# is_string(a.via): negating a built-in call on an absent field (the gateway's input has no
# via) is undefined, not true.
through_view(a) if is_string(a.via)

# Node input only: the gateway's input has no locations, so this set is empty there.
denied_locations contains l if {
	some l in input.locations
	some prefix in data.qod.blocked_locations[input.tenant]
	startswith(l.location, prefix)
}

wild(g, _) if g == "*"

wild(g, v) if lower(g) == v

verb_covers(g, _) if g == "ALL"

verb_covers(g, v) if {
	g == "RO"
	v == "read"
}

verb_covers(g, v) if {
	g == "RW"
	v in {"read", "write"}
}

verb_covers(g, v) if {
	g == "DDL"
	v == "ddl"
}
