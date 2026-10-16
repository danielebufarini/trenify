#!/bin/sh
# Application artifact resource, deliberately independent of KMP compilation.
set -eu
destination="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/TrenifyBuildDate.txt"
mkdir -p "$(dirname "$destination")"
LC_ALL=C /bin/date -u '+%Y-%m-%d %H:%M UTC' > "$destination"
