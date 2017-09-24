#!/bin/bash

AARFILE=`realpath $1`
echo "### Creating the temporary directory"
TEMPDIR=`mktemp -d`
cp ${AARFILE} ${TEMPDIR}
BASE=`basename ${AARFILE}`
pushd ${TEMPDIR}
echo "##### Unzipping the aar content"
unzip $BASE
rm $BASE

# unzip the classes and remove the offending java
mkdir t
pushd t
echo "####### Unzipping the jar content"
unzip ../classes.jar
echo "########## Purging the zip from the extra stuff"
rm ../classes.jar
rm -rf it/couchgames/lib/cjutils/R\$*.class
rm -rf it/couchgames/lib/cjutils/R.class
rm -rf it/couchgames/lib/cjutils/R.java
echo "####### Unzipping the jar content back"
zip -9 -r ../classes.jar *
popd
rm -rf ./t

# Back one level to re-zip everything
echo "### Recreating the original aar"
zip -9 -r $BASE *
mv $BASE ${AARFILE}
popd
