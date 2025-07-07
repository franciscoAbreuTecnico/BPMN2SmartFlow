#!/bin/bash

BASE_DIR="src/main/resources/jsons"

find "$BASE_DIR" -type f -name "*.bpmn" | while read -r bpmnfile; do
    pngfile="${bpmnfile%.bpmn}.png"
    echo "Generating image: $pngfile"
    npx bpmn-to-image "${bpmnfile};${pngfile}"
done
