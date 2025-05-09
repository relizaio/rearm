#!/bin/bash

# Directory to process (current directory by default)
DIR="${1:-.}"

# Loop through each file in the directory
for file in "$DIR"/*; do
  # Skip if it's a directory
  [ -f "$file" ] || continue

  # Get the base name of the file (without path)
  filename=$(basename "$file")

  # Construct the new filename
  newname="Tea$filename"

  # Rename the file
  mv "$file" "$DIR/$newname"
done
