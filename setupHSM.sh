#!/bin/bash

#Will initialize the cluster id passed as parameter to this file
#@author lddecaro@amazon.com

CLUSTER_ID=$1
REGION=$2

STATUS=`aws cloudhsmv2 describe-clusters --filter clusterIds=$CLUSTER_ID --region $REGION --query 'Clusters[0].State' --output text`
if [ "$STATUS" = "UNINITIALIZED" ]; then
	echo Initializing HSM with ClusterID $CLUSTER_ID
	echo Retrieving Certificate Sign Request
	aws cloudhsmv2 describe-clusters --filter clusterIds=$CLUSTER_ID --query 'Clusters[0].Certificates.ClusterCsr' --region $REGION --output text >> /tmp/ClusterCSR.csr
	echo Creating Private Key and Self Signed Certificate
	openssl req -x509 -newkey rsa:2048 -keyout /tmp/customerCA.key -out /tmp/customerCA.crt -subj "/C=BR/ST=SaoPaulo/L=SaoPaulo/O=amazon.com.br/OU=aws/CN=solutions-architects" -days 3652 -nodes
	echo Signing HSM Certificate Request
	openssl x509 -req -days 3652 -in /tmp/ClusterCSR.csr -CA /tmp/customerCA.crt -CAkey /tmp/customerCA.key -CAcreateserial -out /tmp/CustomerHsmCertificate.crt
	echo Uploading HSM Signed Certificate
	aws cloudhsmv2 initialize-cluster --cluster-id $CLUSTER_ID --signed-cert file:///tmp/CustomerHsmCertificate.crt --trust-anchor file:///tmp/customerCA.crt --region $REGION		
	STATUS=`aws cloudhsmv2 describe-clusters --filter clusterIds=$CLUSTER_ID --region $REGION --query 'Clusters[0].State' --output text`
	while [ "$STATUS" != "INITIALIZED" ]; do
		echo Waiting for initialization. Current Status: $STATUS
		sleep 20
		STATUS=`aws cloudhsmv2 describe-clusters --filter clusterIds=$CLUSTER_ID --region $REGION --query 'Clusters[0].State' --output text`
	done
	aws ssm put-parameter --name /$CLUSTER_ID/CodeBuild/CUST_CERTIFICATE --value file:///tmp/customerCA.crt --type SecureString --region $REGION
	echo HSM INITIALIZED.
else
	echo CloudHSM already initialized, geting certificate from Parameter Store
	aws ssm get-parameter --name /$CLUSTER_ID/CodeBuild/CUST_CERTIFICATE --with-decryption --region $REGION --query "Parameter.Value" --output text > /tmp/customerCA.crt
fi
