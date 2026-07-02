# HA failover check (kind)

Manual end-to-end verification of manager HA. Prereq: the local-stack rig.

1. Boot with 2 replicas:
   `BUILD=1 ./run-local-stack-k8s.sh` then
   `helm upgrade qod ../.. -n qod --reuse-values --set replicaCount=2 --set sessionJwtSecret=$(openssl rand -hex 32)`
   Wait: `kubectl -n qod get pods -l app.kubernetes.io/name=quack-on-demand` shows 2/2 Ready.
2. Find the leader: `kubectl -n qod logs <pod> | grep "ha: acquired leadership"` (exactly one pod).
3. Start the load test (see skills/quack-on-demand/SKILL.md, load-test invocation)
   against the FlightSQL NodePort.
4. Kill the leader mid-run: `kubectl -n qod delete pod <leader> --grace-period=0 --force`.
5. Assert:
   - The load test reports only a bounded burst of failures (connections that
     were on the killed pod), then recovers; new statements succeed throughout.
   - The survivor logs "ha: acquired leadership" within ~3s.
   - Kill a quack node pod: the surviving leader's reconcile respawns it.
6. RBAC propagation: create a role via replica A's REST API, verify a handshake
   through replica B sees it without waiting 60s.
7. Revocation: log in via A, logout (revoke) via A, verify the same token is
   rejected by B within one leaderRetrySec tick.
8. Scale down: `--set replicaCount=1` rolls back to Recreate semantics on the
   next full upgrade; confirm the single manager still passes /health.
