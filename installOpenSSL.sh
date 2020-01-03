#!/bin/bash

STATUS=`aws cloudhsmv2 describe-clusters --filter clusterIds=$1 --query 'Clusters[].State' --output text`
    if [ "$STATUS" == "UNINITIALIZED" ]; then
   		echo We need openssl. Installing...
		yum install make gcc perl pcre-devel zlib-devel
		yum install wget
		echo Downloading OpenSSL...
		wget https://ftp.openssl.org/source/old/1.1.1/openssl-1.1.1.tar.gz -O openssl.tar.gz > /dev/null
		tar xvf openssl*.tar.gz > /dev/null
		cd openssl-*
		./config --prefix=/usr --openssldir=/etc/ssl --libdir=lib no-shared zlib-dynamic
		make > /dev/null
		make install > /dev/null
		echo OpenSSL Installed!
    else
    	echo No need to configure openssl as HSM is already initialized and configured.
    fi