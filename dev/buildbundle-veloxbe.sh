#!/bin/bash

BASEDIR=$(dirname $0)
source "$BASEDIR/builddeps-veloxbe.sh"

cd $GLUTEN_DIR
mvn clean package -Pbackends-velox -Pspark-3.2 -DskipTests  -Dcheckstyle.skip=true -DskipScalastyle=true
