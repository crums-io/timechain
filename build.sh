#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

for mod in timechain notary; do
    echo "=== $mod: mvn clean install ==="
    (cd "$mod" && mvn clean install)
done

for mod in crum ergd; do
    echo "=== $mod: mvn clean package appassembler:assemble ==="
    (cd "$mod" && mvn clean package appassembler:assemble)
done
