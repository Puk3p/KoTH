#!/bin/bash

# Define the target directory from the first argument (defaults to current directory)
TARGET_DIR="${1:-.}"

# Define the output file in the directory where the script is executed
OUTPUT_FILE="$(pwd)/output.txt"

# Empty the output file if it already exists
> "$OUTPUT_FILE"

# Change to the target directory to easily get relative paths
cd "$TARGET_DIR" || { echo "Directory not found!"; exit 1; }

# Find all files recursively and process them
find . -type f | while read -r file; do
    # Remove the leading './' from the find output for a cleaner path
    clean_path="${file#./}"

    # Skip the output file itself so we don't create an infinite loop of reading/writing
    if [[ "$(realpath "$file")" == "$OUTPUT_FILE" ]]; then
        continue
    fi

    # Write the relative path
    echo "$clean_path" >> "$OUTPUT_FILE"

    # Write the opening backticks
    echo '```' >> "$OUTPUT_FILE"

    # Append the file content
    cat "$file" >> "$OUTPUT_FILE"

    # Ensure there is a newline before closing the backticks (in case the file doesn't end with one)
    echo "" >> "$OUTPUT_FILE"

    # Write the closing backticks and an extra blank line for spacing
    echo '```' >> "$OUTPUT_FILE"
    echo "" >> "$OUTPUT_FILE"
done

echo "Extraction complete! Data saved to $OUTPUT_FILE"