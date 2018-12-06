#!/bin/bash

current=`pwd`

rm -rf /tmp/changeSHARK
mkdir -p /tmp/changeSHARK

cd ..
./gradlew shadowJar
cp build/libs/*.jar /tmp/changeSHARK
cd $current
cp execute.sh /tmp/changeSHARK
cp info.json /tmp/changeSHARK
cp install.sh /tmp/changeSHARK
cp schema.json /tmp/changeSHARK

cd /tmp/changeSHARK
tar -cvf "$current/changeSHARK_plugin.tar" *
