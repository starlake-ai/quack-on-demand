# Changelog

## 0.3.2-SNAPSHOT
- Per-pool node placement (cohorts) A pool can now be defined as a
  list of cohorts, Operators can express
  layouts such as "2 writers on nodes tagged `disktype=ssd` and 1 reader
  + 1 writer on nodes tagged `disktype=hdd`" against a single pool.
- JDK floor raised to 21.