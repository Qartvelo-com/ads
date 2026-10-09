"""Fails unless the admob POM declares play-services-ads with runtime scope.

Compile scope puts Google's classes (Kotlin 2.3 metadata in play-services-ads 25.4) on the compile
classpath of every module that depends on the adapter, which the Kotlin 2.1 compiler of React
Native 0.83 rejects. Usage: python3 scripts/check-admob-pom.py <path to admob-*.pom>
"""
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}

root = ET.parse(sys.argv[1]).getroot()
for dependency in root.findall("m:dependencies/m:dependency", NS):
    if dependency.findtext("m:artifactId", namespaces=NS) == "play-services-ads":
        scope = dependency.findtext("m:scope", namespaces=NS)
        if scope != "runtime":
            sys.exit(f"play-services-ads has scope {scope!r}, expected 'runtime'")
        print("play-services-ads: runtime scope")
        break
else:
    sys.exit("play-services-ads is missing from the admob POM")
