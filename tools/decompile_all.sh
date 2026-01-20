#!/bin/bash
cd "D:/Projects/mercenary-extracted/BOOT-INF/classes"
CFR="D:/Projects/mercenary/cfr.jar"
OUT="D:/Projects/mercenary/src/main/java"

find . -name "*.class" ! -name "*\$*" | while read classfile; do
    classname=$(echo "$classfile" | sed 's|^./||' | sed 's|/|.|g' | sed 's|.class$||')
    echo "Decompiling: $classname"
    java -jar "$CFR" "$classname" --extraclasspath . --outputdir "$OUT" 2>/dev/null
done
echo "Complete!"
