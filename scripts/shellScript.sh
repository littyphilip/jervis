#!/bin/bash
set -e
export PATH=/home/jenkinsslave/aarive/apache-maven-3.5.0/bin:$PATH
BRN=${GIT_BRANCH##origin/}
echo BRN=$BRN > buildaarivedev.properties
pushd "${WORKSPACE}/api" 
mvn clean install -Pdev
cd ..
set +x
lftp -u ${USERNAME},${PASSWORD} waws-prod-ch1-005.ftp.azurewebsites.windows.net <<-EOF
set ssl:verify-certificate no
glob -a rm -r site/wwwroot/webapps/*
cd site/wwwroot/webapps
put api/target/api.war
exit 0
EOF
popd
exit 0