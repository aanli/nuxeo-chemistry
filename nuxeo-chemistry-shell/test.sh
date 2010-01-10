#!/bin/sh

echo "This shell script runs some tests against a Nuxeo instance"

./run.sh -b << EOF
;connect http://Administrator:Administrator@localhost:8080/nuxeo/site/cmis/repository  
connect http://Administrator:Administrator@cmis.demo.nuxeo.org/nuxeo/site/cmis/repository  

; Test main commands on root
id

; Don't work, maybe should
;ls
;tree
;props

; Local command
lpwd
lls
lpushd src
lpopd
lcd .

; Test main commands on 'default' object
cd default
id
ls
tree
props

; navigate around
cd default-domain
pwd
ls
cd workspaces
pwd
ls
cd ..
pwd
cd /default/default-domain/workspaces

; Create an object (a folder), test commands on it
mkdir testdir
ls
id testdir
ls testdir
tree testdir
props testdir

; Now a file
cd testdir
mkfile testfile
ls
id testfile
ls testfile
tree testfile
props testfile
;put test.sh testfile
cat testfile
rm testfile

; Clean up
cd ..
rm testdir
EOF

