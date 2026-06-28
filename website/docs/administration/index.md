---
id: index
title: Administration
---

Administration is split between two roles. A **superuser** (`qodstate_user.tenant IS NULL`) owns the entire control plane: creating tenants, assigning tenant admins, and operating the infrastructure. A **tenant admin** governs a single tenant: its databases, pools, and who can query them. Both roles work through the same [RBAC model](/operating/rbac-model); the difference is scope. See [Authentication](/operating/authentication) for how sessions and API keys are issued to each role.

All administrative tasks are available through the React console at `http://<host>:20900/ui/`. The [Admin UI guide](/operating/admin-ui) covers every screen in detail; this page does not repeat that tour.

## Task map

Use the table below to jump straight to the playbook for what you need to do.

**Onboarding**

| Task | Playbook |
|------|----------|
| Sign in to the console | [Sign in](/administration/onboarding#sign-in) |
| Create a tenant | [Create a tenant](/administration/onboarding#create-a-tenant) |
| Add a database to a tenant | [Add a database](/administration/onboarding#add-a-database) |
| Create and size a pool | [Create and size a pool](/administration/onboarding#create-and-size-a-pool) |
| Confirm nodes are live | [Confirm nodes are live](/administration/onboarding#confirm-nodes-are-live) |
| Hand off a connection string to users | [Hand off a connection string](/administration/onboarding#hand-off-a-connection-string) |

**Access control**

| Task | Playbook |
|------|----------|
| Give a team read access to a pool | [Grant a team read access](/administration/access-control#grant-a-team-read-access) |
| Extend a grant to DML or DDL | [Grant DML or DDL](/administration/access-control#grant-dml-or-ddl) |
| Remove a grant | [Revoke access](/administration/access-control#revoke-access) |
| Confirm a principal can or cannot query a table | [Verify access](/administration/access-control#verify-access) |

**Day-2 operations**

| Task | Playbook |
|------|----------|
| Monitor node health | [Watch the Nodes board](/administration/day-2-operations#watch-the-nodes-board) |
| Scale a pool up or down | [Scale a pool](/administration/day-2-operations#scale-a-pool) |
| Drain or force-stop a node | [Drain vs force-stop](/administration/day-2-operations#drain-vs-force-stop) |
| Audit recent queries | [Read statement history](/administration/day-2-operations#read-statement-history) |
| Diagnose common failures | [Common failure modes](/administration/day-2-operations#common-failure-modes) |

**Lifecycle and config**

| Task | Playbook |
|------|----------|
| Attach an external data source | [Attach an external catalog](/administration/lifecycle-config#attach-an-external-catalog) |
| Rotate a federated secret | [Rotate a federated secret](/administration/lifecycle-config#rotate-a-federated-secret) |
| Back up and restore the control plane | [Back up and restore](/administration/lifecycle-config#back-up-and-restore) |
| Decommission a tenant or pool | [Decommission](/administration/lifecycle-config#decommission) |
