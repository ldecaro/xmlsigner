#!/bin/bash

# Set stack names and timers
echo Loading variables
source ./signer_setenv.sh
echo Bulding on region $REGION with stack names $STACK_NAME_1, $STACK_NAME_2, $STACK_NAME_3

# Deploy stack #1
function deploy_stack_1() {
    echo Starting to deploy $STACK_NAME_1
    curl -s https://raw.githubusercontent.com/ldecaro/xmlsigner/master/cf/SignerHSM.yaml --output SignerHSM.yaml
    aws cloudformation create-stack --stack-name $STACK_NAME_1 --template-body file://SignerHSM.yaml --region $REGION --parameters ParameterKey=PrivateSubnetAZ,ParameterValue=$AZ --capabilities CAPABILITY_IAM
    STACK1_STATUS=`aws cloudformation describe-stacks --stack-name $STACK_NAME_1 --region $REGION --query "Stacks[0].StackStatus" --output text`
    while [ "$STACK1_STATUS" != "CREATE_COMPLETE" ]; do 
        echo Stack $STACK_NAME_1 build status: $STACK1_STATUS
        if [ "$STACK1_STATUS" = "ROLLBACK_IN_PROGRESS" ] || [ "$STACK1_STATUS" = "ROLLBACK_COMPLETE" ]; then
            echo Error creating stack $STACK_NAME_1
            say "Error creating first stack"
            exit 1
        fi
        sleep $SLEEP_TIME
        STACK1_STATUS=`aws cloudformation describe-stacks --stack-name $STACK_NAME_1 --region $REGION --query "Stacks[0].StackStatus" --output text`
    done
    say "Stack one build completed"
    STATES_ARN=`aws stepfunctions list-state-machines --region $REGION --query "stateMachines[0].stateMachineArn" --output text`
    echo $STATES_ARN
    EXEC_STATUS=`aws stepfunctions list-executions --state-machine-arn $STATES_ARN --region $REGION --query "executions[0].status" --output text`
    echo $EXEC_STATUS
    while [ "$EXEC_STATUS" != "SUCCEEDED" ]; do 
        echo CloudHSM creation workflow status: $EXEC_STATUS
        if [ "$EXEC_STATUS" = "FAILED" ]; then
            echo Error during workflow execution
            say "Error during workflow execution"
            exit 1
        fi
        sleep $SLEEP_TIME
        EXEC_STATUS=`aws stepfunctions list-executions --state-machine-arn $STATES_ARN --region $REGION --query "executions[0].status" --output text`
    done
    say "H S M creation completed"
}

# Deploy stack #2
function deploy_stack_2() {
    # Check if previous stack deployment finished successfully
    echo Checking if previous stack deployment finished successfully
    STATES_ARN=`aws stepfunctions list-state-machines --region $REGION --query "stateMachines[0].stateMachineArn" --output text`
    EXEC_STATUS=`aws stepfunctions list-executions --state-machine-arn $STATES_ARN --region $REGION --query "executions[0].status" --output text`
    if [ "$EXEC_STATUS" != "SUCCEEDED" ]; then
        echo CloudHSM cluster creation lambda has not finished successfully
        exit 1
    fi

    # Start deploying stack 2
    echo Starting to deploy $STACK_NAME_2
    CLUSTER_ID=`aws cloudhsmv2 describe-clusters --region $REGION --query "Clusters[0].ClusterId" --output text`
    echo CloudHSM Cluster Id: $CLUSTER_ID
    curl -s https://raw.githubusercontent.com/ldecaro/xmlsigner/master/cf/SignerContainer.yaml --output SignerContainer.yaml
    aws cloudformation create-stack --stack-name $STACK_NAME_2 --template-body file://SignerContainer.yaml --region $REGION --parameters ParameterKey=HSMClusterId,ParameterValue=$CLUSTER_ID ParameterKey=HSMPassword,ParameterValue=1234Qwer --capabilities CAPABILITY_NAMED_IAM
    STACK2_STATUS=`aws cloudformation describe-stacks --stack-name $STACK_NAME_2 --region $REGION --query "Stacks[0].StackStatus" --output text`
    while [ "$STACK2_STATUS" != "CREATE_COMPLETE" ]; do 
        echo Stack $STACK_NAME_2 build status: $STACK2_STATUS
        if [ "$STACK2_STATUS" = "ROLLBACK_IN_PROGRESS" ] || [ "$STACK2_STATUS" = "ROLLBACK_COMPLETE" ]; then
            echo Error creating stack $STACK_NAME_2
            say "Error creating second stack"
            exit 1
        fi
        sleep $SLEEP_TIME
        STACK2_STATUS=`aws cloudformation describe-stacks --stack-name $STACK_NAME_2 --region $REGION --query "Stacks[0].StackStatus" --output text`
    done
    say "Stack two build completed"
    aws codebuild start-build --project-name xmlsigner --region $REGION
    BUILD_ID=`aws codebuild list-builds --region $REGION --sort-order DESCENDING --query "ids[0]" --output text`
    echo $BUILD_ID
    BUILD_STATUS=`aws codebuild batch-get-builds --id $BUILD_ID --region $REGION --query "builds[0].buildStatus" --output text`
    while [ "$BUILD_STATUS" != "SUCCEEDED" ]; do 
        echo Container build status: $BUILD_STATUS
        if [ "$BUILD_STATUS" = "FAILED" ]; then
            echo Error building the container image
            say "Error building container image"
            exit 1
        fi
        sleep $SLEEP_TIME
        BUILD_STATUS=`aws codebuild batch-get-builds --id $BUILD_ID --region $REGION --query "builds[0].buildStatus" --output text`
    done
    say "Container build completed"
}

# Deploy stack #3
function deploy_stack_3() {
    # Check if previous stack deployment finished successfully
    echo Checking if previous stack deployment finished successfully
    BUILD_ID=`aws codebuild list-builds --region $REGION --sort-order DESCENDING --query "ids[0]" --output text`
    BUILD_STATUS=`aws codebuild batch-get-builds --id $BUILD_ID --region $REGION --query "builds[0].buildStatus" --output text`
    if [ "$BUILD_STATUS" != "SUCCEEDED" ]; then
        echo Container creation has not finished successfully
        exit 1
    fi

    # Start deploying stack 3
    echo Starting to deploy $STACK_NAME_3
    CLUSTER_ID=`aws cloudhsmv2 describe-clusters --region $REGION --query "Clusters[0].ClusterId" --output text`
    echo CloudHSM Cluster Id: $CLUSTER_ID
    curl -s https://raw.githubusercontent.com/ldecaro/xmlsigner/master/cf/SignerECS.yaml --output SignerECS.yaml
    aws cloudformation create-stack --stack-name $STACK_NAME_3 --template-body file://SignerECS.yaml --region $REGION --parameters ParameterKey=HSMClusterId,ParameterValue=$CLUSTER_ID ParameterKey=SourceIpCIDR,ParameterValue='0.0.0.0/0' --capabilities CAPABILITY_IAM
    STACK3_STATUS=`aws cloudformation describe-stacks --stack-name $STACK_NAME_3 --region $REGION --query "Stacks[0].StackStatus" --output text`
    while [ "$STACK3_STATUS" != "CREATE_COMPLETE" ]; do 
        echo Stack $STACK_NAME_3 build status: $STACK3_STATUS
        if [ "$STACK3_STATUS" == "ROLLBACK_IN_PROGRESS" ] || [ "$STACK3_STATUS" == "ROLLBACK_COMPLETE" ]; then
            echo Error creating stack $STACK_NAME_3
            say "Error creating third stack"
            exit 1
        fi
        sleep $SLEEP_TIME
        STACK3_STATUS=`aws cloudformation describe-stacks --stack-name $STACK_NAME_3 --region $REGION --query "Stacks[0].StackStatus" --output text`
    done
    say "Stack three build completed"
}

function verify_signer_working() {
    URL=`aws cloudformation describe-stacks --stack-name $STACK_NAME_3 --region $REGION --query "Stacks[0].Outputs[?OutputKey=='ExternalUrl'].OutputValue" --output text`
    echo $URL
    LIST_KEYS_OUTPUT=`curl -s $URL/xml/listKeys`
    echo $LIST_KEYS_OUTPUT
    export LC_CTYPE=C
    RANDOM=`LC_CTYPE=C; uuidgen | head -c16`
    KEY_CREATION_OUTPUT=`curl -s -X POST -H "Content-Type:text/plain" $URL/xml/create/testkey-$RANDOM --data "@certdata.json"`
    echo $KEY_CREATION_OUTPUT
    LIST_KEYS_OUTPUT=`curl -s $URL/xml/listKeys`
    echo $LIST_KEYS_OUTPUT
}

function show_stacks_status() {
    aws cloudformation describe-stacks --region $REGION --query "Stacks[].{StackStatus:StackStatus,StackName:StackName}" --output table
}

case "$1" in
    all)
        deploy_stack_1
        deploy_stack_2
        deploy_stack_3
        verify_signer_working
        ;;
    start-with-2)
        deploy_stack_2
        deploy_stack_3
        verify_signer_working
        ;;
    start-with-3)
        deploy_stack_3
        verify_signer_working
        ;;
    verify-only)
        show_stacks_status
        verify_signer_working
        ;;
    *)
        echo $"Usage: $0 {all|start-with-2|start-with-3|verify-only}"
        exit 1
esac
