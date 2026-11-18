# One manager on one Linux host, with side-by-side versions

This folder runs the quack-on-demand manager under systemd with every installed release side by
side and one pin file choosing which one runs. Switching versions, and switching back, is "write
the pin, restart": no unit edit, no `daemon-reload`, and no network at restart.

## Layout

| Path | Owner | What |
|---|---|---|
| `/opt/qod/versions/<version>/` | root | the qod CLI of that release, in its own virtualenv |
| `/opt/qod/deploy/` | root | `install.sh`, `switch.sh` (this folder) |
| `/etc/qod/version.env` | root, 0644 | the pin: `QOD_VERSION=<version>` |
| `/etc/qod/manager.env` | root, 0600 | the manager's settings (`QOD_PG_*`, ...), optional |
| `/etc/systemd/system/qod-manager.service` | root | the unit, never edited after install |
| `~qod/.cache/qod/` | qod | jars (`jars/quack-on-demand-assembly-<version>.jar`), a Java runtime when the host has none (`jre-25/`), DuckDB (`duckdb/<release>/`) |
| `~qod/.duckdb/extensions/` | qod | the DuckDB extensions nodes install |
| `~qod/.local/share/qod/` | qod | the manager's state (DuckLake data, certs) |

The service user is `qod`, home `/var/lib/qod`.

Each version has its own CLI install because the CLI and the jar must move together: the CLI
carries the node spawn script the jar runs, and a newer CLI with an older jar boots a manager that
answers `/ready` while every node fails (`unknown option '--spill-dir'`). The unit runs
`/opt/qod/versions/$QOD_VERSION/bin/qod start --version $QOD_VERSION`, so one pin picks both.

Why a virtualenv per version rather than `uv tool` or pipx: both keep one install per package
name, so two versions of `qod` cannot sit side by side; `python3 -m venv` needs nothing beyond
Python (on Debian and Ubuntu, the `python3-venv` package).

## Install

Once per host, as root, from a checkout or the files of this folder at a release tag:

```sh
install -d -m 0755 /opt/qod/deploy
install -m 0755 deploy/host/install.sh deploy/host/switch.sh /opt/qod/deploy/
install -m 0644 deploy/host/qod-manager.service /etc/systemd/system/
install -d -m 0755 /etc/qod
install -m 0600 deploy/host/manager.env.example /etc/qod/manager.env   # then edit it
systemctl daemon-reload
systemctl enable qod-manager
```

Then install a release and start it:

```sh
/opt/qod/deploy/install.sh 0.13.1
systemctl start qod-manager
```

`install.sh VERSION` creates the `qod` user and `/etc/qod` when missing, installs `qod==VERSION`
from PyPI into `/opt/qod/versions/VERSION`, and runs that CLI's
`qod start --version VERSION --download-only` as `qod`, which downloads and checks (sha256) the
manager jar, a Java 25 runtime when the host has none, DuckDB, and the DuckDB extensions nodes
install, into `qod`'s cache. The first install also writes the pin. Running it again for an
installed version only checks the cache. A version that PyPI or the GitHub release does not have
is refused, and nothing of it is kept. Other versions are never touched.

The first boot needs `QOD_ADMIN_PASSWORD` in `/etc/qod/manager.env` (a service has no terminal to
ask on); remove it afterwards. `qod setup`, run as `qod`
(`runuser -u qod -- /opt/qod/versions/VERSION/bin/qod setup`), is the other place settings can
live; a value in `manager.env` wins over it.

## Switch, and roll back

```sh
/opt/qod/deploy/install.sh 0.14.0     # ahead of time, with the network
/opt/qod/deploy/switch.sh 0.14.0      # any time later, no network needed
/opt/qod/deploy/switch.sh 0.13.1      # back
```

`switch.sh VERSION` refuses a version that is not installed, checks its cache (downloading
nothing when it is complete), writes `/etc/qod/version.env` and restarts `qod-manager`. It prints
the previous version. `--no-restart` writes the pin only. By hand, the same thing is:

```sh
echo QOD_VERSION=0.14.0 > /etc/qod/version.env
systemctl restart qod-manager
```

Rolling back the binaries does not roll back the databases. A release can migrate the control
plane (Liquibase) and DuckLake catalogs, one way, at its first boot, and an earlier release may
then not run against them. Read the release's upgrade notes, and back up the control-plane and
catalog databases before switching to a newer release.

`KillMode=mixed` sends the stop's SIGTERM to `qod` alone, which stops the manager and then its
nodes, as `qod stop` does. That stop takes about 102 s at worst with the defaults: the manager's
drain of running statements and its exit (`QOD_DRAIN_TIMEOUT_SEC`, 60 s, plus 20 s), the nodes'
stop (`FORCE_AFTER`, 10 s, plus 2 s) and the CLI's wait for the JVM to exit (`FORCE_AFTER`
again). `TimeoutStopSec=150` covers it, so systemd does not kill a stop midway. Raise it if you
raise either.

## An upgrade agent

The agent needs to run `install.sh` and `switch.sh` as root, nothing else; both take one release
version and refuse anything else. For example, in `/etc/sudoers.d/qod-upgrade`:

```
qod-upgrade ALL=(root) NOPASSWD: /opt/qod/deploy/install.sh, /opt/qod/deploy/switch.sh
```

## What needs the network

Only `install.sh`: PyPI for the CLI, GitHub for the jar, Adoptium for a Java runtime when the
host has none, GitHub for DuckDB, the DuckDB extension repository for the extensions. `switch.sh`
and every start of an installed version use the cache. Behind a proxy, set `HTTPS_PROXY` in
`/etc/qod/manager.env` (install.sh hands it to the download run) and in root's environment for
pip.

Not covered by the cache: an extension that a database's or a pool's initSql installs (it is
downloaded when a node first starts), and a different `DUCKDB_VERSION` or Java set in
`manager.env` after the install (run `install.sh VERSION` again).
