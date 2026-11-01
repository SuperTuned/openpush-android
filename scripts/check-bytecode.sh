#!/usr/bin/env bash
# Asserts the release AARs are consumable by Unity 2022.3 (AGP 7.1.2 D8): no class above the
# module's ceiling, no PermittedSubclasses attribute, no nameless MethodParameters entry.
# Ceilings: Java 11 (major 55) for the Kotlin modules; the Java-only live-updates module must be
# exactly Java 8 (major 52), which the Unity build script also gates on.
set -euo pipefail
# Here-strings, not `printf | awk/grep -q`: an early-exiting reader breaks the pipe on large
# javap dumps, which pipefail turns into an abort or a silently missed match.
root="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
javap="$JAVA_HOME/bin/javap"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/openpush-bytecode.XXXXXX")"
trap 'rm -rf "$scratch"' EXIT
status=0
for entry in openpush:55 openpush-fcm:55 openpush-in-app-messages:55 openpush-onesignal-compat:55 \
             openpush-live-updates:=52; do
  module="${entry%%:*}"; ceiling="${entry#*:}"
  exact=false; [ "${ceiling#=}" != "$ceiling" ] && { exact=true; ceiling="${ceiling#=}"; }
  aar="$root/$module/build/outputs/aar/$module-release.aar"
  if [ ! -f "$aar" ]; then echo "missing $aar (run assembleRelease)"; status=1; continue; fi
  mkdir -p "$scratch/$module/classes"
  unzip -qo "$aar" classes.jar -d "$scratch/$module"
  unzip -qo "$scratch/$module/classes.jar" -d "$scratch/$module/classes"
  while IFS= read -r cls; do
    dump="$("$javap" -v "$cls")"
    major="$(awk '/major version/ {print $3; exit}' <<<"$dump")"
    if [ "$exact" = true ] && [ "$major" -ne "$ceiling" ]; then
      echo "$module: ${cls#"$scratch/"} is major $major (must be exactly $ceiling)"; status=1
    elif [ "$major" -gt "$ceiling" ]; then
      echo "$module: ${cls#"$scratch/"} is major $major (> $ceiling)"; status=1
    fi
    if grep -q 'PermittedSubclasses' <<<"$dump"; then
      echo "$module: ${cls#"$scratch/"} carries PermittedSubclasses"; status=1
    fi
    if awk '/MethodParameters:/ {m=1; next} m && /^ *$/ {m=0} m' <<<"$dump" | grep -q '<no name>'; then
      echo "$module: ${cls#"$scratch/"} carries a nameless MethodParameters entry"; status=1
    fi
  done < <(find "$scratch/$module/classes" -name '*.class')
done
[ "$status" -eq 0 ] && echo "✓ bytecode is within each module's ceiling (55, live-updates exactly 52) with no sealed or nameless-parameter attributes"
exit "$status"
