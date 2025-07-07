#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# Usage:
#   ./run_app_validate.sh "<src1>,<src2>[,...]" \
#                        "<in1>,<in2>[,...]" \
#                        [<rmlTemplate>] \
#                        [<smartFlowTTL>] \
#                        [<bpmnTTL>] \
#                        [<mappingOwl>]
# -----------------------------------------------------------------------------

if [ "$#" -lt 2 ]; then
  cat <<EOF
Usage: $0 <SOURCE_PATHS> <INPUT_FOLDERS> [<rmlTemplate> [<smartFlowTTL> [<bpmnTTL> [<mappingOwl>]]]]

  SOURCE_PATHS   comma-separated list of code roots
  INPUT_FOLDERS  comma-separated list of JSON folders

  (all other args are optional; they map to your main() args for RML & TTL files)
EOF
  exit 1
fi

SOURCE_PATHS="$1"
INPUT_FOLDERS="$2"
RML_TEMPLATE="${3:-src/main/resources/rml/flowTemplate.rml.ttl}"
SMARTFLOW_TTL="${4:-src/main/resources/ontologies/smartFlow.ttl}"
BPMN_TTL="${5:-src/main/resources/ontologies/bpmn/bpmn.ttl}"
MAPPING_OWL="${6:-src/main/resources/ontologies/mapping.ttl}"

export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
export PATH="$JAVA_HOME/bin:$PATH"

echo "=== mvn clean package ==="
mvn clean package

JAR="target/BPMN2SmartFlow-1.0-SNAPSHOT.jar"
echo "=== java -jar $JAR ==="
java -jar "$JAR" \
  "$SOURCE_PATHS" \
  "$INPUT_FOLDERS" \
  "$RML_TEMPLATE" \
  "$SMARTFLOW_TTL" \
  "$BPMN_TTL" \
  "$MAPPING_OWL"
