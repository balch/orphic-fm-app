#!/bin/bash

# Find and replace in composeApp
find apps/composeApp/src/commonMain/kotlin -name "*.kt" -print0 | xargs -0 sed -i '' 's/import org.balch.orpheus.features.drums808/import org.balch.orpheus.features.drum/g'

echo "Imports updated."
