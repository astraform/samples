#!/bin/sh
set -eu
starter_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$starter_dir/init.py" compose "$@"
