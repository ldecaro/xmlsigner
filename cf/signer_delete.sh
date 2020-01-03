#!/bin/bash

# Set stack names and timers
echo Loading variables
source ./signer_setenv.sh
echo Deleting stacks in region $REGION with names $STACK_NAME_1, $STACK_NAME_2, $STACK_NAME_3

# Delete CloudHSM and ECR not removed by stack deletion
function delete_cloudhsm() {
    echo Deleting CloudHSM cluster
    CLUSTER_ID=`aws cloudhsmv2 describe-clusters --region $REGION --query "Clusters[0].ClusterId" --output text`
    HSM_ID=`aws cloudhsmv2 describe-clusters --region $REGION --query "Clusters[0].Hsms[0].HsmId" --output text`
    echo $HSM_ID
    aws cloudhsmv2 delete-hsm --cluster-id $CLUSTER_ID --hsm-id $HSM_ID --region $REGION
    HSM_STATE=`aws cloudhsmv2 describe-clusters --region $REGION --query "Clusters[0].Hsms[0].State" --output text`
    while [ "$HSM_STATE" != "None" ]; do 
        echo HSM deletion status: $HSM_STATE
        sleep $SLEEP_TIME
        HSM_STATE=`aws cloudhsmv2 describe-clusters --region $REGION --query "Clusters[0].Hsms[0].State" --output text`
    done
    say "H S M deletion completed"
    sleep 5
    aws cloudhsmv2 delete-cluster --cluster-id $CLUSTER_ID --region $REGION
    say "Cloud H S M cluster deletion started"
}

function delete_ecr_images() {
    echo Deleting ECR images
    ECS_REPO=`aws ecr describe-repositories --region $REGION --query "repositories[0].repositoryName" --output text`
    if [ "$ECS_REPO" = "None" ]; then
        echo No ECR repository found
    else
        IMAGES_LIST=`aws ecr list-images --repository-name $ECS_REPO --region $REGION --query "imageIds[?imageTag!='latest'].{imageDigest:imageDigest}" --output text`
        LIST=`printf 'imageTag=%s\n' $IMAGES_LIST | tr '\n' ','`
        # the following command has not been validated
        aws ecr batch-delete-image --repository-name $ECS_REPO --image-ids $LIST --region $REGION
    fi
    say "E C R images deleted"
}

function delete_ecr_repo() {
    echo Deleting ECR repository
    ECS_REPO=`aws ecr describe-repositories --region $REGION --query "repositories[0].repositoryName" --output text`
    if [ "$ECS_REPO" = "None" ]; then
        echo No ECR repository found
    else
        aws ecr delete-repository --repository-name $ECS_REPO --force --region $REGION
    fi
    say "E C R repository deleted"
}

function delete_codebuild_role() {
    echo Deleting Codebuild role
    CODEBUILD_ROLE=XMLSignerCodeBuildRole-SignerContainer
    POLICY_ARN_LIST=`aws iam list-attached-role-policies --role-name $CODEBUILD_ROLE --query "AttachedPolicies[].PolicyArn" --output text`
    for POLICY_ARN in $POLICY_ARN_LIST; do
        echo Detaching policy $POLICY_ARN from role
        aws iam detach-role-policy --role-name $CODEBUILD_ROLE --policy-arn $POLICY_ARN
    done
    ROLE_POLICY=`aws iam list-role-policies --role-name $CODEBUILD_ROLE --query "PolicyNames[]" --output text`
    [ -z "$ROLE_POLICY" ] && aws iam delete-role-policy --role-name $CODEBUILD_ROLE --policy-name $ROLE_POLICY
    aws iam delete-role --role-name $CODEBUILD_ROLE
    say "Code build role deleted"
}

# Delete stack 1
function delete_stack_3() {
    echo Deleting stack $STACK_NAME_3
    aws cloudformation delete-stack --stack-name $STACK_NAME_3 --region $REGION
    aws cloudformation wait stack-delete-complete --stack-name $STACK_NAME_3 --region $REGION
    say "Stack three delete completed"
}

# Delete stack 2
function delete_stack_2() {
    echo Deleting stack $STACK_NAME_2
    aws cloudformation delete-stack --stack-name $STACK_NAME_2 --region $REGION
    aws cloudformation wait stack-delete-complete --stack-name $STACK_NAME_2 --region $REGION
    say "Stack two delete completed"
}

# Delete stack 1
function delete_stack_1() {
    echo Deleting stack $STACK_NAME_1
    aws cloudformation delete-stack --stack-name $STACK_NAME_1 --region $REGION
    aws cloudformation wait stack-delete-complete --stack-name $STACK_NAME_1 --region $REGION
    say "Stack one delete completed"
}

function show_stacks_status() {
    aws cloudformation describe-stacks --region $REGION --query "Stacks[].{StackStatus:StackStatus,StackName:StackName}" --output table
}

case "$1" in
    all)
        delete_cloudhsm
        delete_codebuild_role
        delete_ecr_repo
        delete_stack_3
        delete_stack_2
        delete_stack_1
        ;;
    delete-cloudhsm)
        delete_cloudhsm
        ;;
    delete-codebuild-role)
        delete_codebuild_role
        ;;
    delete-ecr-images)
        delete_ecr_images
        ;;
    delete-ecr-repo)
        delete_ecr_repo
        ;;
    delete-stack-1)
        delete_stack_1
        ;;
    delete-stack-2)
        delete_stack_2
        ;;
    delete-stack-3)
        delete_stack_3
        ;;
    show-stacks-status)
        show_stacks_status
        ;;
    *)
        echo $"Usage: $0 {all|delete-cloudhsm|delete-codebuild-role|delete-ecr-images|delete-ecr-repo|delete-stack-1|delete-stack-2|delete-stack-3|show-stacks-status}"
        exit 1
esac