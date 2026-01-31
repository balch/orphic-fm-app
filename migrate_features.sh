#!/bin/bash

# Warps Migration
mkdir -p features/warps/src/commonMain/kotlin/org/balch/orpheus/features/warps
mv apps/composeApp/src/commonMain/kotlin/org/balch/orpheus/features/warps/* features/warps/src/commonMain/kotlin/org/balch/orpheus/features/warps/

# Drum Migration
# Note: Renaming package from drums808 to drum
mkdir -p features/drum/src/commonMain/kotlin/org/balch/orpheus/features/drum
mv apps/composeApp/src/commonMain/kotlin/org/balch/orpheus/features/drums808/* features/drum/src/commonMain/kotlin/org/balch/orpheus/features/drum/

# Update package declarations in Drum
for file in features/drum/src/commonMain/kotlin/org/balch/orpheus/features/drum/*.kt; do
    sed -i '' 's/package org.balch.orpheus.features.drums808/package org.balch.orpheus.features.drum/' "$file"
done

# Clean up old directories
rm -rf apps/composeApp/src/commonMain/kotlin/org/balch/orpheus/features/warps
rm -rf apps/composeApp/src/commonMain/kotlin/org/balch/orpheus/features/drums808

echo "Migration complete."
