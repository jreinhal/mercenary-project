#!/usr/bin/env python3
"""
Remove CFR decompilation headers from Java source files.
Safe operation - only removes comment blocks at the start of files.
"""

import os
import re
import sys

def remove_decompile_header(filepath):
    """Remove the decompilation header from a single file."""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Pattern matches the CFR decompilation comment block at the start
    # Includes the "Decompiled with CFR" and "Could not load" sections
    pattern = r'^/\*\s*\n\s*\*\s*Decompiled with CFR.*?\*/\s*\n'

    new_content = re.sub(pattern, '', content, flags=re.DOTALL)

    if new_content != content:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(new_content)
        return True
    return False

def main():
    src_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           'src', 'main', 'java')

    if not os.path.exists(src_dir):
        print(f"Source directory not found: {src_dir}")
        sys.exit(1)

    modified = 0
    total = 0

    for root, dirs, files in os.walk(src_dir):
        for file in files:
            if file.endswith('.java'):
                total += 1
                filepath = os.path.join(root, file)
                if remove_decompile_header(filepath):
                    modified += 1
                    print(f"Cleaned: {filepath}")

    print(f"\nProcessed {total} files, modified {modified}")

if __name__ == '__main__':
    main()
