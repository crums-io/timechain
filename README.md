# Crums Timechain (Alpha)

This is the source repo for the Crums timechain. I'm developing a new version
of the timechain. Unlike its previous incarnation, this repo now includes both
client- *and* server-side code. (The client for the legacy chain is archived 
under the [TC-1](https://github.com/crums-io/crums-pub/tree/main/TC-1) subdirectory.)

The [project documentation page](https://crums-io.github.io/crums-pub/) details
a conceptual overview of timechains and their proof structures.

The implementation is organized in 3 modules, each layered atop the other.

1. [timechain](https://github.com/crums-io/crums-pub/tree/main/timechain) - Defines
the basic timechain, and its proof structures. Additionally, it provides client-side
code for accessing a timechain.
2. [notary](https://github.com/crums-io/crums-pub/tree/main/notary) - This module
implements the background daemon service that collects crums (witnessed hashes)
and publishes their collective hash per block (bin) interval as the cargo hash of
the corresponding timechain block. The notary is designed to be safe under concurrent
read/write access from multiple *processes* (not just threads).
3. [ergd](https://github.com/crums-io/crums-pub/tree/main/ergd) - This is a standaolone,
embedded HTTP REST server.

## Project Status

The first (alpha) version is nearing release. It works.

### What's missing

The most glaring TODOs:

* Client-side storage and archival of crumtrails (witness proofs) needs work. As a chain evolves (as it accumulates new blocks) 
* Need to work out details about how otherwise independent timechains on the network can choose to record one another's state in order to assert each others' bona fides.
* Broken landing page (To be fixed before release).
* Snapshot build script.

## Building the SNAPSHOT

The project's principal build tool is maven. Presently, SNAPSHOT versions are not published
anywhere. To build this project, you'll have to clone and build a number of dependencies
yourself. Here's the suggested build order, roughly in order of dependency.


1. [merkle-tree](https://github.com/crums-io/merkle-tree) - Merkle tree implementation. Dependencies: none.
1. [io-util](https://github.com/crums-io/io-util) - Small, multi-module, utility library. Dependencies: none.
1. [stowkwik](https://github.com/crums-io/stowkwik) - Simple file-per-object store, indexed by hash. Dependencies: `io-util`.
1. [skipledger](https://github.com/crums-io/skipledger) - Base module defining the data
structure and other modules for packaging proofs from general ledgers. This project used to
know about this repo. The code was refactored so that the base layer no longer knows about
this project (the relationship is in fact now reversed). Dependencies: `merkle-tree`, `io-util`.
1. Clone *this* project and build:

    1. [timechain](https://github.com/crums-io/crums-pub/tree/main/timechain). Dependencies: `skipledger-base`.
    2. [notary](https://github.com/crums-io/crums-pub/tree/main/notary). Dependencies: `timechain`.
    3. [ergd](https://github.com/crums-io/crums-pub/tree/main/ergd). Dependencies: `notary`.

~ Babak
July 2024


