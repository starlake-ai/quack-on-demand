Quack from duckdb allow to run multiple quack servers connected to a shared metastore. 
I want to build a distributed query manager that starts a specific number of preconfigured set of Quack instances  
and is able to handle requests from any client. The goal here is to route queries the passive Quack servers whenever possible 

A good starting point would be /Users/hayssams/git/public/gizmo-on-demand


Quack-on-Demand - gestionnaire de requêtes distribué pour DuckDB Quack

Pourquoi: 
DuckDB Quack est livré comme un serveur HTTP
minimaliste (auth par token aléatoire, localhost-only par
défaut, pas de TLS). La doc officielle indique
explicitement de placer un reverse proxy devant pour gérer
TLS, auth externe et autorisation. quack-on-demand est ce
proxy avec multi-tenancy et routing intelligent intégrés.

Topologie:
tenant -> pool nommé -> N noeuds Quack. 
Chaque noeud étiqueté est READONLY / WRITEONLY / DUAL. 
Tous les noeuds d'un pool partagent un seul metastore DuckLake (métadonnées Postgres + données S3 ou FS).

Coeur de routing :
- Par instructiontype de requête: 
    SELECT → RO|DUAL
    DDL/DML/BEGIN -> WO|DUAL
- pinning transactionnel: BEGIN fixe la session à un noeud writer jusqu'à COMMIT/ROLLBACK
- Score de charge: nombre de requêtes en cours (inflight queries)
- Plafond de nombre de requêtes par noeud (0 = illimité)

Deux protocoles:
• Client → manager: FlightSQL + TLS (avec stack auth
vendored: DB/Keycloak/Google/Azure/AWS/JWT, ACL au niveau
table avec hot-reload, allowlist SQL basée sur JSQLParser)
• Manager → Quack: HTTP, le manager détient le token d'auth
de chaque nœud et l'injecte à chaque forward

Sélecteur de tenant: claim JWT + header Flight X-Pool
privilégié; fallback sur convention username
tenant/pool/user pour les pilotes plus anciens qui ne
savent pas passer de headers custom. Fonctionne avec
n'importe quel driver FlightSQL JDBC/ADBC.

Runtime: sous-processus OS locaux ou pods+services
Kubernetes (Fabric8), pluggable. Une seule JVM héberge
l'API REST de management + l'edge FlightSQL + l'UI admin
React/Vite sous /ui - le tout dans un uber-jar. Le
restart-policy K8s / compose gère la mort du process.

Cycle de vie élastique: POST /api/pool/create avec
distribution de rôles + maxConcurrentPerNode; scale up/down
avec flag force optionnel (skip du drain gracieux); tuning
par nœud via POST /api/node/setMaxConcurrent.

Stack: Scala 3.7 / SBT / Tapir + HTTP4s Ember / Arrow
Flight SQL 14 / Fabric8 K8s 7 / React 18 + Vite.
Réutilisation vendored de la stack de sécurité de
gizmo-on-demand.
  