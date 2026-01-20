#!/bin/bash
# Batch decompile all classes with their inner classes
JAR="D:/Projects/mercenary-extracted/BOOT-INF/classes"
OUT="D:/Projects/mercenary/src/main/java"
CFR="D:/Projects/mercenary/cfr.jar"

# Find all main classes (not inner classes)
for classfile in $(find "$JAR" -name "*.class" ! -name "*\$*"); do
    pkg_path=$(dirname "$classfile" | sed "s|$JAR/||")
    classname=$(basename "$classfile" .class)
    inner_classes=$(find "$(dirname $classfile)" -name "${classname}\$*.class" 2>/dev/null | tr '\n' ' ')
    
    if [ -n "$inner_classes" ]; then
        echo "Decompiling $classname with inner classes..."
        java -jar "$CFR" "$classfile" $inner_classes > "${OUT}/${pkg_path}/${classname}.java" 2>/dev/null
    fi
done
echo "Done"
